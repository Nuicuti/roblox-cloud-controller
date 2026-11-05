#!/data/data/com.termux/files/usr/bin/python
"""Noka controller agent. Fixed actions only; no remote shell endpoint."""
import argparse
import base64
from collections import deque
import hashlib
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import re
import secrets
import shlex
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

PREFIX = '/data/data/com.termux/files/usr'
ROOT = 'nokaController/v1'
PRIVATE = Path('/data/data/com.termux/files/home/.noka-controller')
PACKAGE = re.compile(r'free\.noka[A-Za-z0-9_.]*\Z')
LOG = logging.getLogger('noka')

class AgentError(Exception):
    pass

def atomic_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + '.tmp')
    with temporary.open('w', encoding='utf-8') as stream:
        os.chmod(temporary, 0o600)
        json.dump(value, stream, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)

def read_json(path, default):
    path = Path(path)
    return json.loads(path.read_text(encoding='utf-8')) if path.exists() else default

def http(url, method='GET', payload=None, form=False):
    data = None
    if payload is not None:
        data = urllib.parse.urlencode(payload).encode() if form else json.dumps(payload).encode()
    headers = {'Content-Type': 'application/x-www-form-urlencoded' if form else 'application/json'}
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            body = response.read(4_000_000)
            return json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        # Never include the URL: RTDB REST authentication is carried in its query.
        raise AgentError(f'Dịch vụ trả HTTP {error.code}; kiểm tra quyền và cấu hình.') from None
    except (urllib.error.URLError, TimeoutError):
        raise AgentError('Không kết nối được Firebase. Agent sẽ thử lại.') from None

class Firebase:
    def __init__(self, private=PRIVATE):
        self.private = Path(private)
        self.config = read_json(self.private/'config.json', {})
        self.auth = read_json(self.private/'auth.json', {})
        self.token = ''
        self.expiry = 0
        self.uid = self.auth.get('uid', '')

    def authenticate(self):
        if time.monotonic() < self.expiry:
            return
        api_key = self.config['apiKey']
        if self.auth.get('refreshToken'):
            data = http('https://securetoken.googleapis.com/v1/token?key='+urllib.parse.quote(api_key),
                        'POST', {'grant_type': 'refresh_token', 'refresh_token': self.auth['refreshToken']}, form=True)
            self.token, self.uid = data['id_token'], data['user_id']
            refresh, duration = data['refresh_token'], data['expires_in']
        else:
            data = http('https://identitytoolkit.googleapis.com/v1/accounts:signUp?key='+urllib.parse.quote(api_key),
                        'POST', {'returnSecureToken': True})
            self.token, self.uid = data['idToken'], data['localId']
            refresh, duration = data['refreshToken'], data['expiresIn']
        self.auth = {'uid': self.uid, 'refreshToken': refresh}
        atomic_json(self.private/'auth.json', self.auth)
        self.expiry = time.monotonic() + int(duration)-120

    def request(self, path, method='GET', payload=None):
        self.authenticate()
        path = '/'.join(urllib.parse.quote(part, safe='') for part in (ROOT+'/'+path).split('/'))
        url = self.config['databaseURL'].rstrip('/')+'/'+path+'.json?'+urllib.parse.urlencode({'auth': self.token})
        return http(url, method, payload)

    def device(self, suffix, method='GET', payload=None):
        self.authenticate()
        return self.request(f'devices/{self.uid}/{suffix}', method, payload)

SU_BIN = next((p for p in ['/system/xbin/su', '/system/bin/su', '/sbin/su', 'su'] if os.path.exists(p)), 'su')

def root_command(args, *, stdin=None, check=True):
    cmd = [SU_BIN, '-c', shlex.join([str(a) for a in args])]
    result = subprocess.run(cmd, input=stdin,
                            capture_output=True, text=True, timeout=30)
    output = (result.stdout+'\n'+result.stderr).strip()
    failed = result.returncode != 0 or bool(re.search(r'(?m)^(Error:|Exception |java\.|SecurityException)', output))
    if failed and check:
        # This output is Android diagnostics only; credential helper never prints inputs.
        raise AgentError(output[:500] or 'Lệnh Android không thành công.')
    return result.returncode, output

def parse_tasks(text):
    """Use live Hist records, not stale recents or substring package matches."""
    result = {}
    for line in text.splitlines():
        if not re.search(r'\bHist\s+#\d+:\s+ActivityRecord\{', line):
            continue
        match = re.search(r'\bu0\s+(free\.noka[A-Za-z0-9_.]*)/[^\s}]+\s+t(\d+)\b', line)
        if match:
            result.setdefault(match[1], int(match[2]))
    return result

def task_bounds(text, task):
    chunks = re.split(r'(?m)(?=^[ \t]*\*[ \t]*(?:Task|TaskRecord)\{)', text)
    for chunk in chunks:
        first = chunk.splitlines()[0] if chunk.splitlines() else ''
        if not re.search(r'#'+str(task)+r'\b', first):
            continue
        # Task configuration precedes its activity history. Avoid using child activity bounds.
        header = chunk.split('Hist #', 1)[0]
        bounds = re.search(r'(?:mBounds|bounds)=Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)', header)
        mode = 'freeform' in header or bool(re.search(r'(?:windowingMode|mode)=5\b', header))
        return (tuple(map(int, bounds.groups())) if bounds else None), mode
    return None, False

def grid_bounds(width, height, rows, cols, gap, top, bottom):
    if not (1 <= rows <= 6 and 1 <= cols <= 6 and 0 <= gap <= 40 and 0 <= top <= 250 and 0 <= bottom <= 250):
        raise AgentError('Thông số lưới không hợp lệ.')
    available_w, available_h = width-gap*(cols+1), height-top-bottom-gap*(rows+1)
    if available_w//cols < 160 or available_h//rows < 120:
        raise AgentError('Ô lưới quá nhỏ. Giảm hàng/cột hoặc khoảng cách.')
    cells = []
    for row in range(rows):
        for col in range(cols):
            left = gap+col*gap+available_w*col//cols
            right = gap+col*gap+available_w*(col+1)//cols
            upper = top+gap+row*gap+available_h*row//rows
            lower = top+gap+row*gap+available_h*(row+1)//rows
            cells.append((left, upper, right, lower))
    return cells

class Android:
    def packages(self):
        code, out = root_command(['/system/bin/pm', 'list', 'packages', '--user', '0'], check=False)
        if code != 0 or not out:
            _, out = root_command(['/system/bin/pm', 'list', 'packages'], check=False)
        return sorted({line[8:] for line in out.splitlines() if line.startswith('package:') and PACKAGE.fullmatch(line[8:])})

    def running(self, package):
        code, output = root_command(['/system/bin/pidof', package], check=False)
        if code not in (0, 1):
            raise AgentError('Không kiểm tra được tiến trình Android.')
        return code == 0 and bool(re.fullmatch(r'[\d\s]+', output))

    def tasks(self):
        return root_command(['/system/bin/dumpsys', 'activity', 'activities'], check=False)[1]

    def size(self):
        _, output = root_command(['/system/bin/wm', 'size'])
        sizes = re.findall(r'(?:Physical|Override) size:\s*(\d+)x(\d+)', output)
        if not sizes:
            raise AgentError('Không đọc được kích thước màn hình.')
        width, height = map(int, sizes[-1])
        # wm size is the natural orientation; Noka can be displayed in landscape.
        _, display = root_command(['/system/bin/dumpsys', 'display'])
        match = re.search(r'mDisplayInfo=DisplayInfo\{[^\n]*?\breal\s+(\d+)\s*x\s*(\d+)', display)
        return tuple(map(int, match.groups())) if match else (width, height)

    def resolve(self, package):
        _, output = root_command(['/system/bin/cmd', 'package', 'resolve-activity', '--brief', '--user', '0',
                                  '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-p', package])
        for line in reversed(output.splitlines()):
            if re.fullmatch(re.escape(package)+r'/[A-Za-z0-9_.$]+', line.strip()):
                return line.strip()
        raise AgentError('Không tìm thấy màn hình mở ứng dụng: '+package)

    def open(self, package, freeform=False):
        component = self.resolve(package)
        args = ['/system/bin/am', 'start', '-W', '--user', '0']
        if freeform:
            args += ['--windowingMode', '5']
        args += ['-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-n', component]
        root_command(args)

    def close(self, package):
        root_command(['/system/bin/am', 'force-stop', '--user', '0', package])

    def freeform(self):
        try:
            _, features = root_command(['/system/bin/pm', 'list', 'features'], check=False)
            _, setting = root_command(['/system/bin/settings', 'get', 'global', 'enable_freeform_support'], check=False)
            _, help_text = root_command(['/system/bin/am', 'help'], check=False)
            return (('android.software.freeform_window_management' in features or setting.strip() == '1')
                    and '--windowingMode' in help_text and 'resize <TASK_ID>' in help_text)
        except Exception:
            return False

    def resize(self, package, bounds, launch=True):
        if launch:
            self.open(package, freeform=True)
        deadline = time.monotonic()+12
        while time.monotonic() < deadline:
            task = parse_tasks(self.tasks()).get(package)
            if task is not None:
                break
            time.sleep(.5)
        else:
            raise AgentError('Chưa tìm thấy cửa sổ đang hoạt động của '+package)
        root_command(['/system/bin/am', 'task', 'resize', str(task), *map(str, bounds)], check=False)
        time.sleep(.6)
        actual, freeform = task_bounds(self.tasks(), task)
        if not freeform or actual is None or max(abs(a-b) for a, b in zip(actual, bounds)) > 24:
            comma_bounds = ','.join(map(str, bounds))
            root_command(['/system/bin/am', 'task', 'resize', str(task), comma_bounds], check=False)
            time.sleep(.6)
            actual, freeform = task_bounds(self.tasks(), task)
        if not freeform or actual is None:
            raise AgentError('Android chưa xác nhận cửa sổ freeform; cần kiểm tra hỗ trợ đa cửa sổ trên máy.')
        if max(abs(a-b) for a, b in zip(actual, bounds)) > 24:
            raise AgentError('Android điều chỉnh ô khác vị trí yêu cầu; thử giảm hàng/cột.')
        return task

class Manager:
    def __init__(self, private=PRIVATE, android=None):
        self.private = Path(private)
        self.android = android or Android()
        self.state = read_json(self.private/'state.json', {'profiles': {}, 'watch': False, 'grid': None})
        self.accounts = read_json(self.private/'accounts.json', {})
        self.health = {}
        self.public_key = None

    def save(self):
        atomic_json(self.private/'state.json', self.state)

    def key(self):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        path = self.private/'device-key.pem'
        if path.exists():
            key = serialization.load_pem_private_key(path.read_bytes(), password=None)
        else:
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            raw = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
            fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(fd, 'wb') as out:
                out.write(raw)
        numbers = key.public_key().public_numbers()
        def b64int(number):
            return base64.urlsafe_b64encode(number.to_bytes((number.bit_length()+7)//8, 'big')).rstrip(b'=').decode()
        self.public_key = {'kty': 'RSA', 'n': b64int(numbers.n), 'e': b64int(numbers.e),
                           'alg': 'RSA-OAEP-256', 'ext': True, 'key_ops': ['encrypt']}
        return key

    def scan(self):
        installed = self.android.packages()
        profiles = self.state['profiles']
        # Preserve the ordering already used in the existing controller: C,D / A,B.
        known_slots = {'free.nokaA': 3, 'free.nokaB': 4, 'free.nokaC': 1, 'free.nokaD': 2}
        occupied = {item['slot'] for item in profiles.values()}
        for package in installed:
            if package in profiles:
                continue
            desired = known_slots.get(package)
            slot = desired if desired and desired not in occupied else next(i for i in range(1, 100) if i not in occupied)
            if slot > 36:
                continue
            occupied.add(slot)
            suffix = package.removeprefix('free.noka')
            label = 'Noka '+str(ord(suffix)-64) if len(suffix) == 1 and 'A' <= suffix <= 'Z' else package
            profiles[package] = {'package': package, 'label': label, 'username': '', 'slot': slot, 'enabled': True, 'autoReopen': True}
        for p in profiles.values():
            p.setdefault('autoReopen', True)
        self.save()
        return f'Phát hiện {len(installed)} bản Noka. Cấu hình cũ được giữ nguyên.'

    def profile(self, package):
        if not PACKAGE.fullmatch(package) or package not in self.state['profiles']:
            raise AgentError('Bản Noka chưa được quét và thêm vào quản lý.')
        return self.state['profiles'][package]

    def layout(self, settings, launch=True):
        if not self.android.freeform():
            raise AgentError('Máy chưa hỗ trợ/bật freeform. Dùng Bật cửa sổ tự do, rồi kiểm tra lại.')
        width, height = self.android.size()
        cells = grid_bounds(width, height, **settings)
        selected = [p for p in self.state['profiles'].values() if p['enabled']]
        if not selected:
            raise AgentError('Chưa có Noka nào bật quản lý.')
        if any(p['slot'] > len(cells) for p in selected):
            raise AgentError('Có Noka nằm ngoài số ô lưới; chỉnh vị trí hoặc tăng hàng/cột.')
        failures = []
        for profile in sorted(selected, key=lambda p: p['slot']):
            if not launch and not self.android.running(profile['package']):
                continue
            try:
                self.android.resize(profile['package'], cells[profile['slot']-1], launch=launch)
                profile.pop('error', None)
            except AgentError as error:
                profile['error'] = str(error)
                failures.append(profile['label']+': '+str(error))
        self.save()
        if failures:
            raise AgentError('; '.join(failures))

    def import_accounts(self, sealed):
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        try:
            key = self.key().decrypt(base64.b64decode(sealed['key'], validate=True),
                padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
            raw = AESGCM(key).decrypt(base64.b64decode(sealed['iv'], validate=True), base64.b64decode(sealed['data'], validate=True), None)
            if len(raw) > 120000:
                raise ValueError()
            records = json.loads(raw)
            if not isinstance(records, list) or not 1 <= len(records) <= 36:
                raise ValueError()
            updated = dict(self.accounts)
            for record in records:
                package = record['package']
                self.profile(package)
                if set(record) != {'package', 'username', 'password', 'cookie'}:
                    raise ValueError()
                if not all(isinstance(record[k], str) for k in record):
                    raise ValueError()
                if not 1 <= len(record['username']) <= 80 or not 1 <= len(record['cookie']) <= 20000 or len(record['password']) > 200:
                    raise ValueError()
                updated[package] = record
        except Exception:
            raise AgentError('Không giải mã được tài khoản. Quét lại trạng thái máy và gửi lại.') from None
        # Validate every record before saving any of them.
        atomic_json(self.private/'accounts.json', updated)
        self.accounts = updated
        for record in records:
            self.state['profiles'][record['package']]['username'] = record['username']
        self.save()
        return f'Đã lưu {len(records)} tài khoản riêng trên máy.'

    def open_profile(self, package):
        profile = self.profile(package)
        profile['enabled'] = True
        self.health.pop(package, None)
        self.save()
        if self.state.get('grid'):
            settings = self.state['grid']
            if not self.android.freeform():
                raise AgentError('Không khôi phục được freeform. Tắt cấu hình lưới hoặc kiểm tra máy.')
            cells = grid_bounds(*self.android.size(), **settings)
            if profile['slot'] > len(cells):
                raise AgentError('Vị trí Noka nằm ngoài lưới.')
            self.android.resize(package, cells[profile['slot']-1])
        else:
            self.android.open(package)

    def execute(self, command):
        action = command['action']
        if action == 'scan':
            return self.scan()
        if action == 'set_accounts':
            return self.import_accounts(command['sealed'])
        if action == 'profile':
            item = command['profile']
            self.profile(item['package'])
            valid_keys = {'package', 'label', 'username', 'slot', 'enabled', 'autoReopen'}
            if not set(item).issubset(valid_keys) or not {'package', 'label', 'username', 'slot', 'enabled'}.issubset(item) or type(item['slot']) is not int or not 1 <= item['slot'] <= 36 or type(item['enabled']) is not bool:
                raise AgentError('Thông tin tài khoản không hợp lệ.')
            if 'autoReopen' in item and type(item['autoReopen']) is not bool:
                raise AgentError('Cài đặt tự mở lại không hợp lệ.')
            previous_slot = self.state['profiles'][item['package']]['slot']
            for other in self.state['profiles'].values():
                if other['package'] != item['package'] and other['slot'] == item['slot']:
                    other['slot'] = previous_slot
            self.state['profiles'][item['package']].update(item)
            self.save()
            return 'Đã lưu tên tài khoản và vị trí.'
        if action == 'set_watch':
            if type(command['enabled']) is not bool:
                raise AgentError('Trạng thái tự mở lại không hợp lệ.')
            self.state['watch'] = command['enabled']
            self.health.clear()
            self.save()
            return 'Đã bật tự mở lại.' if command['enabled'] else 'Đã tắt tự mở lại.'
        if action == 'close':
            profile = self.profile(command['package'])
            profile['enabled'] = False
            profile['autoReopen'] = False
            self.save()
            self.android.close(profile['package'])
            return 'Đã đóng Noka và tạm dừng tự mở lại cho bản này.'
        if action == 'open':
            profile = self.profile(command['package'])
            profile['enabled'] = True
            profile['autoReopen'] = True
            self.open_profile(command['package'])
            return 'Đã mở Noka.'
        if action == 'open_all':
            for package, profile in self.state['profiles'].items():
                if profile['enabled']:
                    self.open_profile(package)
            try:
                from noka_login import tap_login_buttons
                time.sleep(2)
                tap_login_buttons(root_command)
            except Exception:
                pass
            return 'Đã mở các bản Noka đang bật quản lý.'
        if action == 'grid':
            settings = {k: command[k] for k in ('rows', 'cols', 'gap', 'top', 'bottom')}
            self.layout(settings)
            self.state['grid'] = settings
            self.save()
            return 'Đã xếp lưới và đối chiếu vị trí Android báo lại.'
        if action in ('enable_freeform', 'restore_windowing'):
            names = ('enable_freeform_support', 'force_resizable_activities')
            if action == 'enable_freeform':
                if 'windowBackup' not in self.state:
                    self.state['windowBackup'] = {name: root_command(['/system/bin/settings', 'get', 'global', name])[1] for name in names}
                    self.save()
                for name in names:
                    root_command(['/system/bin/settings', 'put', 'global', name, '1'])
                return 'Đã bật cờ cửa sổ tự do. Một số máy cần khởi động lại Redfinger để áp dụng.'
            if 'windowBackup' not in self.state:
                raise AgentError('Chưa có cấu hình cửa sổ cũ để khôi phục.')
            for name, value in self.state['windowBackup'].items():
                args = ['delete', 'global', name] if value == 'null' else ['put', 'global', name, value]
                root_command(['/system/bin/settings', *args])
            self.state.pop('windowBackup')
            self.state['grid'] = None
            self.save()
            return 'Đã khôi phục cờ cửa sổ và tắt tự xếp lưới.'
        if action == 'login_all':
            from noka_login import login_account
            messages = []
            failed = False
            for profile in sorted(self.state['profiles'].values(), key=lambda p: p['package']):
                account = self.accounts.get(profile['package'])
                if not account or not profile['enabled']:
                    continue
                try:
                    message = login_account(account, root_command)
                    profile['cookieStatus'] = 'valid'
                    profile['sqliteStatus'] = 'written'
                    profile['appStatus'] = 'running'
                    profile['loginStatus'] = 'unverified_ingame'
                except Exception as error:
                    message = str(error)
                    if 'Roblox' in message or 'cookie' in message.lower():
                        profile['cookieStatus'] = 'invalid'
                    elif 'kho cookie' in message or 'ghi cookie' in message or 'SQLite' in message:
                        profile['sqliteStatus'] = 'failed'
                    failed = True
                profile['loginMessage'] = message
                messages.append(profile['label']+': '+message)
            self.save()
            if not messages:
                raise AgentError('Chưa có tài khoản trên các Noka đang bật.')
            if self.state.get('grid'):
                self.layout(self.state['grid'], launch=False)
            try:
                from noka_login import tap_login_buttons
                time.sleep(2.5)
                tap_login_buttons(root_command)
                time.sleep(1)
                tap_login_buttons(root_command)
            except Exception:
                pass
            if failed:
                raise AgentError('; '.join(messages))
            return '; '.join(messages)
        if action == 'status':
            return 'Đã cập nhật trạng thái.'
        raise AgentError('Lệnh không được hỗ trợ.')

    def watchdog(self, now=None):
        if not self.state['watch']:
            return
        now = time.monotonic() if now is None else now
        for package, profile in self.state['profiles'].items():
            if not profile.get('enabled', True) or not profile.get('autoReopen', True):
                continue
            health = self.health.setdefault(package, {'missing': 0, 'next': 0, 'attempts': deque(), 'healthy': None})
            if self.android.running(package):
                health['missing'] = 0
                health['healthy'] = health['healthy'] or now
                if now-health['healthy'] >= 60:
                    health['attempts'].clear()
                    profile.pop('error', None)
                continue
            health['healthy'] = None
            health['missing'] += 1
            if health['missing'] < 2 or now < health['next']:
                continue
            while health['attempts'] and now-health['attempts'][0] > 600:
                health['attempts'].popleft()
            if len(health['attempts']) >= 5:
                profile['error'] = 'Ứng dụng đóng liên tục; tạm nghỉ để tránh lặp mở vô hạn.'
                health['next'] = health['attempts'][0]+600
                continue
            health['attempts'].append(now)
            health['next'] = now+min(300, 30*2**(len(health['attempts'])-1))
            try:
                # Do not reset health here: repeated crashes must keep the retry budget.
                if self.state.get('grid'):
                    cells = grid_bounds(*self.android.size(), **self.state['grid'])
                    self.android.resize(package, cells[profile['slot']-1])
                else:
                    self.android.open(package)
            except (AgentError, IndexError) as error:
                profile['error'] = str(error)

    def telemetry(self):
        profiles = {}
        try:
            tasks = parse_tasks(self.android.tasks())
        except Exception:
            tasks = {}
        for package, profile in self.state['profiles'].items():
            is_running = self.android.running(package)
            item = dict(profile, running=is_running, hasAccount=package in self.accounts)
            item['appStatus'] = 'running' if is_running else 'stopped'
            if package in tasks:
                item['taskId'] = tasks[package]
            # RTDB keys cannot contain periods; the package stays as a string field.
            profiles[hashlib.sha256(package.encode()).hexdigest()[:16]] = item
        _, model = root_command(['/system/bin/getprop', 'ro.product.model'], check=False)
        _, android = root_command(['/system/bin/getprop', 'ro.build.version.release'], check=False)
        return {'lastSeen': int(time.time()*1000), 'model': model.strip() or 'Redfinger Device',
                'android': android.strip() or 'Android',
                'watch': self.state['watch'], 'profiles': profiles, 'grid': self.state.get('grid'),
                'freeform': bool(self.android.freeform()), 'publicKey': self.public_key}

def process_command(firebase, manager, command, journal):
    identifier = command.get('id', '')
    if not re.fullmatch(r'[a-f0-9-]{36}', identifier):
        raise AgentError('Mã lệnh không hợp lệ.')
    if identifier in journal:
        result = journal[identifier]
        if result['status'] == 'running':
            result = dict(result, status='failed', message='Agent đã dừng giữa lệnh; kiểm tra máy trước khi gửi lại.', finishedAt=int(time.time()*1000))
    else:
        result = {'id': identifier, 'action': command.get('action', ''), 'createdAt': command['createdAt'], 'status': 'running'}
        journal[identifier] = result
        atomic_json(manager.private/'journal.json', journal)
        if command.get('expiresAt', 0) < time.time()*1000:
            result = dict(result, status='failed', message='Lệnh đã hết hạn trước khi máy nhận.', finishedAt=int(time.time()*1000))
        else:
            firebase.device('results/'+identifier, 'PUT', result)
            try:
                message = manager.execute(command)
                result = dict(result, status='succeeded', message=message[:3000], finishedAt=int(time.time()*1000))
            except Exception as error:
                import traceback
                LOG.error('Command %s error: %s\n%s', identifier, error, traceback.format_exc())
                message = str(error) or type(error).__name__
                result = dict(result, status='failed', message=message[:3000], finishedAt=int(time.time()*1000))
    journal[identifier] = result
    # Bound the local replay journal. Server command TTL still blocks older commands.
    if len(journal) > 1000:
        for old in sorted(journal, key=lambda k: journal[k]['createdAt'])[:-800]:
            journal.pop(old)
    atomic_json(manager.private/'journal.json', journal)
    firebase.device('results/'+identifier, 'PUT', result)
    firebase.device('inbox/'+identifier, 'DELETE')

def setup(api_key=None, database=None):
    PRIVATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(PRIVATE, 0o700)
    config = read_json(PRIVATE/'config.json', {})
    if not api_key:
        api_key = input('Firebase Web API key: ').strip()
    if not database:
        database = input('Realtime Database URL: ').strip().rstrip('/')
    else:
        database = database.strip().rstrip('/')
    parsed = urllib.parse.urlsplit(database)
    if parsed.scheme != 'https' or not parsed.hostname or not parsed.hostname.endswith(('.firebaseio.com', '.firebasedatabase.app')) or parsed.path or parsed.query or parsed.fragment:
        raise AgentError('URL database không hợp lệ.')
    if config and config.get('databaseURL') != database:
        raise AgentError('Máy đã cấu hình database khác. Dùng thư mục cài riêng để tránh nhầm danh tính.')
    atomic_json(PRIVATE/'config.json', {'apiKey': api_key, 'databaseURL': database})
    manager = Manager()
    manager.key()
    firebase = Firebase()
    firebase.authenticate()
    if firebase.device('meta'):
        print('\n[i] Máy đã ghép trước đó trên Dashboard. UID:', firebase.uid)
        return
    code = secrets.token_hex(16)
    firebase.request('pairingCodes/'+code, 'PUT', {'deviceUid': firebase.uid,
                                                'expiresAt': int(time.time()*1000)+600_000})
    print('\n==========================================')
    print(' [✓] KHỞI TẠO ĐỊNH DANH MÁY THÀNH CÔNG!')
    print('==========================================')
    print('UID máy           :', firebase.uid)
    print('Mã ghép (10 phút) :', code)
    print('==========================================')
    print('👉 Mở Dashboard: https://roblox-cloud-controller.vercel.app')
    print('👉 Bấm "Ghép máy mới" và dán UID + Mã ghép ở trên.')
    print('==========================================\n')

def run():
    import fcntl
    PRIVATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(PRIVATE, 0o700)
    lock = (PRIVATE/'agent.lock').open('a+')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        lock.seek(0)
        lock.truncate()
        lock.write(str(os.getpid()) + '\n')
        lock.flush()
    except BlockingIOError:
        lock.seek(0)
        existing_pid = lock.read().strip()
        msg = f'Agent đã chạy trên máy này (PID {existing_pid}).' if existing_pid else 'Agent đã chạy trên máy này.'
        raise AgentError(msg + ' Dùng "killall -9 python" để dừng trước khi chạy lại.') from None
    handler = RotatingFileHandler(PRIVATE/'agent.log', maxBytes=300000, backupCount=2, encoding='utf-8')
    LOG.addHandler(handler)
    LOG.setLevel(logging.INFO)
    code, root_id = root_command(['/system/bin/id', '-u'], check=False)
    if code != 0 or root_id.strip() != '0':
        print('[!] LỖI: Termux chưa được cấp quyền root (SuperUser)!', file=sys.stderr)
        print('👉 Hãy gõ "su" trong Termux và chọn "Cho phép" (Grant / Always allow) trên màn hình Redfinger.', file=sys.stderr)
        raise AgentError('Cần cấp root cho Termux.')
    firebase = Firebase()
    if not firebase.config:
        raise AgentError('Chạy setup trước.')
    manager = Manager()
    manager.key()
    manager.scan()
    journal = read_json(PRIVATE/'journal.json', {})
    active = True
    def stop(_signal, _frame):
        nonlocal active
        active = False
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    last_report, last_reflow, retry = 0, 0, 0
    print('Agent đang chạy. Ctrl+C để dừng.')
    while active:
        try:
            if not firebase.device('meta'):
                time.sleep(3)
                continue
            inbox = firebase.device('inbox') or {}
            for command in sorted(inbox.values(), key=lambda c: c.get('createdAt', 0)):
                if not active:
                    break
                process_command(firebase, manager, command, journal)
                last_report = 0
            manager.watchdog()
            now = time.monotonic()
            if manager.state.get('grid') and now-last_reflow > 30:
                try:
                    # Activities may create a new task after login. Reapply the saved grid.
                    manager.layout(manager.state['grid'], launch=False)
                except AgentError as error:
                    LOG.info('Grid: %s', str(error))
                last_reflow = now
            if now-last_report > 15:
                firebase.device('telemetry', 'PUT', manager.telemetry())
                last_report = now
            retry = 0
            time.sleep(3)
        except (AgentError, subprocess.TimeoutExpired, OSError) as error:
            # Watchdog still operates locally when Firebase is temporarily offline.
            LOG.warning('Connection/device error: %s: %s', type(error).__name__, str(error))
            print(f'[!] Cảnh báo vòng lặp agent: {type(error).__name__}: {error}', file=sys.stderr)
            try:
                manager.watchdog()
            except Exception:
                pass
            retry = min(retry+1, 5)
            time.sleep(min(30, 2**retry))
    manager.save()

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('setup', 'run'))
    parser.add_argument('--api-key', dest='api_key', default=None)
    parser.add_argument('--database', dest='database', default=None)
    args = parser.parse_args()
    try:
        if args.action == 'setup':
            setup(args.api_key, args.database)
        else:
            run()
    except (AgentError, ImportError, KeyboardInterrupt) as error:
        print(str(error) if not isinstance(error, ImportError) else 'Cài gói: pkg install python-cryptography', file=sys.stderr)
        sys.exit(1)
