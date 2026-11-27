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
import xml.etree.ElementTree as ET

PREFIX = '/data/data/com.termux/files/usr'
ROOT = 'nokaController/v1'
PRIVATE = Path('/data/data/com.termux/files/home/.noka-controller')
PACKAGE_PATTERN = r'(?:free\.noka[A-Za-z0-9_.]*|com\.roblox\.client(?:\.[A-Za-z0-9_]+)*)'
PACKAGE = re.compile(PACKAGE_PATTERN + r'\Z')
LOG = logging.getLogger('noka')

DEFAULT_REJOIN_CONFIG = {
    'enabled': False,
    'placeId': '',
    'jobId': '',
    'checkIntervalSec': 3,
    'delayAfterLaunchSec': 10,
    'delayBetweenClonesSec': 0,
    'bootGraceSec': 150,
    'windowGraceSec': 90,
    'disconnectConfirmSec': 10,
    'networkTimeoutSec': 0,
    'maxRejoinIntervalSec': 300,
    'retryAttempts': 0,
    'backoffSec': 30,
    'teleportDetection': True,
    'teleportGraceSec': 120,
    'rejoinOnCrash': True,
    'detectDisconnectUi': True,
}

REJOIN_CONFIG_BOUNDS = {
    'checkIntervalSec': (3, 60),
    'delayAfterLaunchSec': (0, 120),
    'delayBetweenClonesSec': (0, 60),
    'bootGraceSec': (10, 300),
    'windowGraceSec': (10, 300),
    'disconnectConfirmSec': (2, 60),
    'networkTimeoutSec': (0, 300),
    'maxRejoinIntervalSec': (30, 900),
    'retryAttempts': (0, 50),
    'backoffSec': (5, 120),
    'teleportGraceSec': (10, 300),
}

class AgentError(Exception):
    pass

def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_name(f'{path.name}.tmp.{os.getpid()}.{time.time_ns()}')
    try:
        with temporary.open('w', encoding='utf-8') as stream:
            os.chmod(temporary, 0o600)
            json.dump(value, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            try:
                temporary.unlink()
            except Exception:
                pass

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

def normalize_rejoin_config(raw=None, watch=False):
    cfg = dict(DEFAULT_REJOIN_CONFIG)
    if isinstance(raw, dict):
        for key in ('enabled', 'teleportDetection', 'rejoinOnCrash', 'detectDisconnectUi'):
            if isinstance(raw.get(key), bool):
                cfg[key] = raw[key]
        if isinstance(raw.get('placeId'), str) and re.fullmatch(r'\d{0,20}', raw['placeId'].strip()):
            cfg['placeId'] = raw['placeId'].strip()
        if isinstance(raw.get('jobId'), str) and re.fullmatch(r'[A-Za-z0-9-]{0,64}', raw['jobId'].strip()):
            cfg['jobId'] = raw['jobId'].strip()
        for key, (low, high) in REJOIN_CONFIG_BOUNDS.items():
            val = raw.get(key)
            if type(val) is int and low <= val <= high:
                cfg[key] = val
    if not isinstance(raw, dict) or 'enabled' not in raw:
        cfg['enabled'] = bool(watch)
    return cfg

def build_roblox_uri(place_id='', job_id=''):
    place_id = (place_id or '').strip()
    job_id = (job_id or '').strip()
    if not place_id or not re.fullmatch(r'\d{1,20}', place_id):
        return ''
    if job_id and re.fullmatch(r'[A-Za-z0-9-]{1,64}', job_id):
        return f'roblox://placeId={place_id}&gameInstanceId={job_id}'
    return f'roblox://experiences/start?placeId={place_id}'

def parse_tasks(text):
    """Use live Hist records, not stale recents or substring package matches."""
    result = {}
    for line in text.splitlines():
        if not re.search(r'\bHist\s+#\d+:\s+ActivityRecord\{', line):
            continue
        match = re.search(rf'\bu0\s+({PACKAGE_PATTERN})/[^\s}}]+\s+t(\d+)\b', line)
        if match:
            result.setdefault(match[1], int(match[2]))
    return result

def check_task_list(tasks_text, package):
    if not tasks_text or 'ACTIVITY MANAGER' not in tasks_text:
        return None
    return package in parse_tasks(tasks_text)

def check_window_manager(windows_text, package):
    if not windows_text or ('WINDOW MANAGER' not in windows_text and 'Window{' not in windows_text):
        return None
    blocks = re.split(r'(?m)(?=^[ \t]*Window\s+#\d+\s+Window\{)', windows_text)
    pkg_re = re.compile(rf'\bu0\s+{re.escape(package)}(?:/|\}}|\s)')
    for block in blocks:
        if not pkg_re.search(block):
            continue
        if 'mHasSurface=false' in block and 'mViewVisibility=0x8' in block:
            continue
        return True
    return False

def check_surfaces(surface_text, package):
    lines = [line.strip() for line in (surface_text or '').splitlines() if line.strip()]
    if len(lines) < 2:
        return None
    pkg_re = re.compile(rf'(?:^|[\s\[/]){re.escape(package)}(?:[/\]#:\s]|$)')
    return any(pkg_re.search(line) for line in lines)

def check_screen_tree(ui_xml, package):
    if not ui_xml or '<hierarchy' not in ui_xml:
        return None
    return f'package="{package}"' in ui_xml

def evaluate_window_closed(checks):
    """Four independent checks must agree; any unreadable check prevents false crash."""
    if any(item is True for item in checks):
        return False, True
    if any(item is None for item in checks):
        return False, False
    return True, True

def _parse_bounds(text):
    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', (text or '').strip())
    return tuple(map(int, match.groups())) if match else None

def _bounds_overlap(inner, outer):
    if not inner or not outer:
        return False
    cx, cy = (inner[0] + inner[2]) // 2, (inner[1] + inner[3]) // 2
    return outer[0] <= cx <= outer[2] and outer[1] <= cy <= outer[3]

def inspect_ui_state(ui_xml, package, bounds=None):
    """Classify visible UI state for one package without ever attempting CAPTCHA bypass."""
    if not ui_xml or '<hierarchy' not in ui_xml:
        return 'unknown'
    try:
        root = ET.fromstring(ui_xml)
    except ET.ParseError:
        return 'unknown'

    pkg_texts = []
    system_texts = []
    for node in root.iter('node'):
        node_pkg = node.attrib.get('package', '')
        text_val = (node.attrib.get('text') or '').strip()
        desc_val = (node.attrib.get('content-desc') or '').strip()
        combined = ' '.join(part for part in (text_val, desc_val) if part)
        if not combined:
            continue
        if node_pkg == package:
            pkg_texts.append(combined.lower())
        elif node_pkg == 'android' and bounds and _bounds_overlap(_parse_bounds(node.attrib.get('bounds')), bounds):
            system_texts.append(combined.lower())

    joined = ' | '.join(pkg_texts)
    sys_joined = ' | '.join(system_texts)

    if any(tok in sys_joined for tok in ("isn't responding", 'không phản hồi')) and any(tok in sys_joined for tok in ('close app', 'đóng ứng dụng')):
        return 'disconnected'

    if not pkg_texts:
        return 'unknown'

    auth_markers = (
        'arkose', 'captcha', 'verify you are human', 'two-step verification',
        '2-step verification', 'security notification', 'xác minh 2 bước',
        'xác minh bạn là con người',
    )
    if any(marker in joined for marker in auth_markers):
        return 'auth_required'
    has_login = any(tok in joined for tok in ('log in', 'đăng nhập'))
    has_login_form = any(tok in joined for tok in ('sign up', 'đăng ký', 'forgot password', 'quên mật khẩu', 'username/email/phone', 'mật khẩu'))
    if has_login and has_login_form:
        return 'auth_required'

    disconnect_markers = (
        'disconnected', 'lost connection', 'connection failed',
        'same account launched', 'you were kicked', 'error code:', 'error code ',
        'đã mất kết nối', 'mất kết nối', 'mã lỗi:', 'mã lỗi ',
    )
    if any(marker in joined for marker in disconnect_markers):
        return 'disconnected'
    if (('reconnect' in joined and 'leave' in joined) or ('kết nối lại' in joined and 'rời' in joined)):
        return 'disconnected'

    teleport_markers = (
        'teleporting', 'joining server', 'waiting for an available server',
        'đang dịch chuyển', 'đang tham gia',
    )
    if any(marker in joined for marker in teleport_markers):
        return 'teleporting'

    return 'active'

def parse_proc_net_udp(text, uid=None):
    """Extract established/active non-loopback remote UDP peers optionally filtered by Linux UID."""
    if not text:
        return None
    lines = text.splitlines()
    if len(lines) < 2:
        return set()
    peers = set()
    for line in lines[1:]:
        cols = line.split()
        if len(cols) < 8:
            continue
        rem = cols[2]
        sock_uid = cols[7]
        if uid is not None and sock_uid != str(uid):
            continue
        if ':' not in rem:
            continue
        addr_hex, port_hex = rem.split(':', 1)
        try:
            port = int(port_hex, 16)
        except ValueError:
            continue
        if port in (0, 53):
            continue
        if len(addr_hex) == 8 and addr_hex in ('00000000', '0100007F'):
            continue
        if len(addr_hex) == 32 and (addr_hex == '0' * 32 or addr_hex == '0' * 30 + '0100' or addr_hex.endswith('0100007F')):
            continue
        peers.add((addr_hex, port))
    return peers

def task_bounds(text, task):
    chunks = re.split(r'(?m)(?=^[ \t]*\*[ \t]*(?:Task|TaskRecord)\{)', text)
    for chunk in chunks:
        first = chunk.splitlines()[0] if chunk.splitlines() else ''
        if not re.search(r'#'+str(task)+r'\b', first):
            continue
        # Task configuration precedes its activity history. Avoid using child activity bounds.
        header = chunk.split('Hist #', 1)[0]
        bounds = re.search(r'(?:mBounds|bounds)=Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)', header)
        if not bounds:
            bounds = re.search(r'(?:mBounds|bounds)=\[(\d+),\s*(\d+)\]\[(\d+),\s*(\d+)\]', header)
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
    def __init__(self):
        self._uid_cache = {}

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

    def windows(self):
        return root_command(['/system/bin/dumpsys', 'window', 'windows'], check=False)[1]

    def surfaces(self):
        return root_command(['/system/bin/dumpsys', 'SurfaceFlinger', '--list'], check=False)[1]

    def ui_tree(self):
        dump_path = '/data/local/tmp/noka_ui_dump.xml'
        code, _ = root_command(['/system/bin/uiautomator', 'dump', '--compressed', dump_path], check=False)
        if code != 0:
            return ''
        _, xml_content = root_command(['/system/bin/cat', dump_path], check=False)
        return xml_content if '<hierarchy' in xml_content else ''

    def package_uid(self, package):
        if package in self._uid_cache:
            return self._uid_cache[package]
        data_dir = f'/data/data/{package}'
        if os.path.exists(data_dir):
            uid = os.stat(data_dir).st_uid
            self._uid_cache[package] = uid
            return uid
        _, out = root_command(['/system/bin/stat', '-c', '%u', data_dir], check=False)
        if out.strip().isdigit():
            uid = int(out.strip())
            self._uid_cache[package] = uid
            return uid
        return None

    def udp_peers(self, package):
        uid = self.package_uid(package)
        if uid is None:
            return None
        peers = set()
        readable = False
        for proc_file in ('/proc/net/udp', '/proc/net/udp6'):
            try:
                content = Path(proc_file).read_text(encoding='utf-8', errors='ignore')
            except OSError:
                _, content = root_command(['/system/bin/cat', proc_file], check=False)
            parsed = parse_proc_net_udp(content, uid=uid)
            if parsed is not None:
                readable = True
                peers.update(parsed)
        return peers if readable else None

    def window_checks(self, package, ui_xml=None, tasks_text=None, windows_text=None, surface_text=None):
        t_text = self.tasks() if tasks_text is None else tasks_text
        w_text = self.windows() if windows_text is None else windows_text
        s_text = self.surfaces() if surface_text is None else surface_text
        u_xml = self.ui_tree() if ui_xml is None else ui_xml
        return (
            check_task_list(t_text, package),
            check_window_manager(w_text, package),
            check_surfaces(s_text, package),
            check_screen_tree(u_xml, package),
        )

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

    def open(self, package, freeform=False, place_id='', job_id=''):
        args = ['/system/bin/am', 'start', '-W', '--user', '0']
        if freeform:
            args += ['--windowingMode', '5']
        uri = build_roblox_uri(place_id, job_id)
        if uri:
            args += ['-a', 'android.intent.action.VIEW', '-d', uri, '-p', package]
        else:
            component = self.resolve(package)
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

    def resize(self, package, bounds, launch=True, place_id='', job_id=''):
        if launch:
            if place_id:
                self.open(package, freeform=True, place_id=place_id, job_id=job_id)
            else:
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
        time.sleep(.4)
        actual, freeform = task_bounds(self.tasks(), task)
        if not freeform or actual is None:
            comma_bounds = ','.join(map(str, bounds))
            root_command(['/system/bin/am', 'task', 'resize', str(task), comma_bounds], check=False)
        return task

class Manager:
    def __init__(self, private=PRIVATE, android=None):
        self.private = Path(private)
        self.android = android or Android()
        self.state = read_json(self.private/'state.json', {'profiles': {}, 'watch': False, 'grid': None})
        self.state['rejoinConfig'] = normalize_rejoin_config(self.state.get('rejoinConfig'), watch=self.state.get('watch', False))
        self.accounts = read_json(self.private/'accounts.json', {})
        self.health = {}
        self.last_any_launch = None
        self.last_any_launch_pkg = None
        self.public_key = None
        self._sync_usernames()

    def _rejoin_config(self):
        cfg = normalize_rejoin_config(self.state.get('rejoinConfig'), watch=self.state.get('watch', False))
        cfg['enabled'] = bool(self.state.get('watch', cfg['enabled']))
        return cfg

    def _effective_target(self, profile, cfg):
        place_id = (profile.get('placeId') or cfg.get('placeId') or '').strip()
        job_id = (profile.get('jobId') if profile.get('placeId') else cfg.get('jobId')) or ''
        return place_id, job_id.strip()

    def _launch_package(self, package, place_id='', job_id='', force_stop=False):
        if force_stop:
            self.android.close(package)
        if self.state.get('grid'):
            settings = self.state['grid']
            if not self.android.freeform():
                raise AgentError('Không khôi phục được freeform. Tắt cấu hình lưới hoặc kiểm tra máy.')
            cells = grid_bounds(*self.android.size(), **settings)
            profile = self.profile(package)
            if profile['slot'] > len(cells):
                raise AgentError('Vị trí Noka nằm ngoài lưới.')
            if place_id:
                self.android.resize(package, cells[profile['slot']-1], place_id=place_id, job_id=job_id)
            else:
                self.android.resize(package, cells[profile['slot']-1])
        else:
            if place_id:
                self.android.open(package, place_id=place_id, job_id=job_id)
            else:
                self.android.open(package)

    def _sync_usernames(self):
        changed = False
        for package, account in self.accounts.items():
            username = account.get('username')
            if username and package in self.state.get('profiles', {}):
                prof = self.state['profiles'][package]
                if not prof.get('username'):
                    prof['username'] = username
                    changed = True
        if changed:
            self.save()

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
            if package.startswith('free.noka'):
                suffix = package.removeprefix('free.noka')
                label = 'Noka '+str(ord(suffix)-64) if len(suffix) == 1 and 'A' <= suffix <= 'Z' else package
            elif package == 'com.roblox.client':
                label = 'Roblox Quốc tế'
            else:
                label = 'Roblox '+package.removeprefix('com.roblox.client.')
            username = self.accounts.get(package, {}).get('username', '')
            profiles[package] = {'package': package, 'label': label, 'username': username, 'slot': slot, 'enabled': True, 'autoReopen': True}
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

    def open_profile(self, package, force_stop=False):
        profile = self.profile(package)
        profile['enabled'] = True
        profile.pop('error', None)
        profile['healthState'] = 'booting'
        profile['healthMessage'] = 'Đang khởi chạy vào game.'
        self.health.pop(package, None)
        cfg = self._rejoin_config()
        place_id, job_id = self._effective_target(profile, cfg)
        self.save()
        self._launch_package(package, place_id=place_id, job_id=job_id, force_stop=force_stop)
        now = time.monotonic()
        self.health[package] = {
            'missing': 0, 'next': 0, 'attempts': deque(), 'healthy': None,
            'last_check': now, 'last_launch': now, 'window_closed_since': None,
            'disconnect_since': None, 'teleport_at': None, 'last_peers': set(),
            'socket_lost_since': None, 'auth_paused': False,
        }
        self.last_any_launch = now
        self.last_any_launch_pkg = package
        profile['lastRejoinAt'] = int(time.time() * 1000)
        self.save()

    def execute(self, command):
        action = command['action']
        if action == 'scan':
            return self.scan()
        if action == 'set_accounts':
            return self.import_accounts(command['sealed'])
        if action == 'profile':
            item = command['profile']
            self.profile(item['package'])
            valid_keys = {'package', 'label', 'username', 'slot', 'enabled', 'autoReopen', 'placeId', 'jobId'}
            if not set(item).issubset(valid_keys) or not {'package', 'label', 'username', 'slot', 'enabled'}.issubset(item) or type(item['slot']) is not int or not 1 <= item['slot'] <= 36 or type(item['enabled']) is not bool:
                raise AgentError('Thông tin tài khoản không hợp lệ.')
            if 'autoReopen' in item and type(item['autoReopen']) is not bool:
                raise AgentError('Cài đặt tự mở lại không hợp lệ.')
            if 'placeId' in item and (not isinstance(item['placeId'], str) or not re.fullmatch(r'\d{0,20}', item['placeId'].strip())):
                raise AgentError('Place ID không hợp lệ.')
            if 'jobId' in item and (not isinstance(item['jobId'], str) or not re.fullmatch(r'[A-Za-z0-9-]{0,64}', item['jobId'].strip())):
                raise AgentError('Job ID không hợp lệ.')
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
            self.state['rejoinConfig'] = normalize_rejoin_config(self.state.get('rejoinConfig'), watch=command['enabled'])
            self.state['rejoinConfig']['enabled'] = command['enabled']
            self.health.clear()
            self.save()
            return 'Đã bật tự mở lại.' if command['enabled'] else 'Đã tắt tự mở lại.'
        if action == 'set_rejoin_config':
            raw = command.get('config')
            expected_keys = set(DEFAULT_REJOIN_CONFIG)
            if not isinstance(raw, dict) or set(raw) != expected_keys:
                raise AgentError('Cấu hình Auto-rejoin thiếu hoặc dư trường.')
            for key in ('enabled', 'teleportDetection', 'rejoinOnCrash', 'detectDisconnectUi'):
                if type(raw.get(key)) is not bool:
                    raise AgentError('Cấu hình Auto-rejoin không hợp lệ.')
            if not isinstance(raw.get('placeId'), str) or not re.fullmatch(r'\d{0,20}', raw['placeId'].strip()):
                raise AgentError('Place ID phải là chữ số.')
            if not isinstance(raw.get('jobId'), str) or not re.fullmatch(r'[A-Za-z0-9-]{0,64}', raw['jobId'].strip()):
                raise AgentError('Job ID không hợp lệ.')
            for key, (low, high) in REJOIN_CONFIG_BOUNDS.items():
                val = raw.get(key)
                if type(val) is not int or not (low <= val <= high):
                    raise AgentError(f'Tham số {key} nằm ngoài giới hạn ({low}–{high}).')
            cfg = normalize_rejoin_config(raw)
            self.state['rejoinConfig'] = cfg
            self.state['watch'] = cfg['enabled']
            for health in self.health.values():
                health['last_check'] = None
                health['window_closed_since'] = None
                health['disconnect_since'] = None
                health['socket_lost_since'] = None
            self.save()
            target = f"Place {cfg['placeId']}" if cfg['placeId'] else 'mặc định'
            return f"Đã lưu cấu hình Auto-rejoin ({'Bật' if cfg['enabled'] else 'Tắt'}, {target})."
        if action == 'close':
            profile = self.profile(command['package'])
            profile['enabled'] = False
            profile['autoReopen'] = False
            profile['healthState'] = 'stopped'
            profile['healthMessage'] = 'Đã đóng thủ công từ dashboard.'
            self.save()
            self.android.close(profile['package'])
            return 'Đã đóng Noka và tạm dừng tự mở lại cho bản này.'
        if action == 'open':
            profile = self.profile(command['package'])
            profile['enabled'] = True
            profile['autoReopen'] = True
            self.open_profile(command['package'])
            return 'Đã mở Noka.'
        if action == 'rejoin':
            profile = self.profile(command['package'])
            profile['enabled'] = True
            profile['autoReopen'] = True
            self.open_profile(command['package'], force_stop=True)
            return f"Đã rejoin {profile['label']}."
        if action == 'open_all':
            cfg = self._rejoin_config()
            delay = cfg['delayBetweenClonesSec']
            selected = sorted((p for p in self.state['profiles'].values() if p['enabled']), key=lambda p: p['slot'])
            for idx, profile in enumerate(selected):
                if idx > 0 and delay > 0:
                    time.sleep(delay)
                self.open_profile(profile['package'])
            try:
                from noka_login import tap_login_buttons
                time.sleep(2)
                tap_login_buttons(root_command)
            except Exception:
                pass
            return 'Đã mở các bản Noka đang bật quản lý.'
        if action == 'rejoin_all':
            cfg = self._rejoin_config()
            delay = cfg['delayBetweenClonesSec']
            selected = sorted((p for p in self.state['profiles'].values() if p['enabled']), key=lambda p: p['slot'])
            if not selected:
                raise AgentError('Chưa có bản nào đang bật quản lý.')
            for idx, profile in enumerate(selected):
                if idx > 0 and delay > 0:
                    time.sleep(delay)
                self.open_profile(profile['package'], force_stop=True)
            return f'Đã rejoin lần lượt {len(selected)} bản.'
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
                    res = login_account(account, root_command)
                    if isinstance(res, dict):
                        profile['cookieStatus'] = res.get('cookieStatus', 'unverified')
                        msg = res.get('message', 'Đã nạp cookie vào SQLite.')
                    else:
                        profile['cookieStatus'] = 'valid'
                        msg = str(res)
                    profile['sqliteStatus'] = 'written'
                    profile['appStatus'] = 'running'
                    profile['loginStatus'] = 'unverified_ingame'
                    profile['loginMessage'] = msg
                    profile.pop('error', None)
                    messages.append(profile['label']+': '+msg)
                except Exception as error:
                    message = str(error)
                    if '401' in message:
                        profile['cookieStatus'] = 'invalid'
                    elif 'kho cookie' in message or 'ghi cookie' in message or 'SQLite' in message:
                        profile['sqliteStatus'] = 'failed'
                    failed = True
                    profile['loginMessage'] = message
                    profile['error'] = message
                    messages.append(profile['label']+': '+message)
            self.save()
            if not messages:
                raise AgentError('Chưa có tài khoản trên các Noka đang bật.')
            if self.state.get('grid'):
                try:
                    self.layout(self.state['grid'], launch=False)
                except Exception:
                    pass
            try:
                from noka_login import tap_login_buttons
                time.sleep(3)
                tap_login_buttons(root_command)
                time.sleep(2)
                tap_login_buttons(root_command)
            except Exception:
                pass
            if failed:
                raise AgentError('; '.join(messages))
            return '; '.join(messages)
        if action == 'status':
            return 'Đã cập nhật trạng thái.'
        raise AgentError('Lệnh không được hỗ trợ.')

    def _new_health(self):
        return {
            'missing': 0,
            'next': 0,
            'attempts': deque(),
            'healthy': None,
            'last_check': None,
            'last_launch': None,
            'window_closed_since': None,
            'disconnect_since': None,
            'teleport_at': None,
            'last_peers': set(),
            'socket_lost_since': None,
            'auth_paused': False,
        }

    def _can_launch(self, package, health, now, cfg):
        if now < health['next']:
            return False
        if health['last_launch'] is not None and now - health['last_launch'] < cfg['delayAfterLaunchSec']:
            return False
        last_pkg = getattr(self, 'last_any_launch_pkg', None)
        if cfg['delayBetweenClonesSec'] > 0 and self.last_any_launch is not None and last_pkg != package:
            if now - self.last_any_launch < cfg['delayBetweenClonesSec']:
                return False
        return True

    def _recover_package(self, package, profile, health, now, cfg, reason, force_stop=True):
        if not self._can_launch(package, health, now, cfg):
            return False
        window_sec = max(600, cfg['maxRejoinIntervalSec'] * 2)
        while health['attempts'] and now - health['attempts'][0] > window_sec:
            health['attempts'].popleft()
        limit = cfg['retryAttempts']
        if limit > 0 and len(health['attempts']) >= limit:
            profile['healthState'] = 'paused_retries'
            profile['healthMessage'] = f'Đã đạt giới hạn {limit} lần thử lại; tạm nghỉ để tránh lặp mở vô hạn.'
            profile['error'] = profile['healthMessage']
            health['next'] = health['attempts'][0] + max(600, cfg['maxRejoinIntervalSec'])
            return False

        health['healthy'] = None
        health['attempts'].append(now)
        step = min(len(health['attempts']) - 1, 10)
        wait_sec = min(cfg['maxRejoinIntervalSec'], cfg['backoffSec'] * (2 ** step))
        health['next'] = now + max(wait_sec, cfg['delayAfterLaunchSec'])
        health['window_closed_since'] = None
        health['disconnect_since'] = None
        health['socket_lost_since'] = None
        health['missing'] = 0
        place_id, job_id = self._effective_target(profile, cfg)
        try:
            self._launch_package(package, place_id=place_id, job_id=job_id, force_stop=force_stop)
            health['last_launch'] = now
            self.last_any_launch = now
            self.last_any_launch_pkg = package
            profile['retryCount'] = len(health['attempts'])
            profile['lastRejoinAt'] = int(time.time() * 1000)
            profile['healthState'] = 'booting'
            profile['healthMessage'] = reason
            profile.pop('error', None)
            return True
        except (AgentError, IndexError) as error:
            profile['error'] = str(error)
            profile['healthMessage'] = str(error)
            return False

    def _snapshot_cycle(self):
        snapshot = {'tasks': None, 'windows': None, 'surfaces': None, 'ui_xml': None}
        if hasattr(self.android, 'tasks') and hasattr(self.android, 'window_checks'):
            try:
                snapshot['tasks'] = self.android.tasks()
            except Exception:
                snapshot['tasks'] = ''
        if hasattr(self.android, 'windows'):
            try:
                snapshot['windows'] = self.android.windows()
            except Exception:
                snapshot['windows'] = ''
        if hasattr(self.android, 'surfaces'):
            try:
                snapshot['surfaces'] = self.android.surfaces()
            except Exception:
                snapshot['surfaces'] = ''
        if hasattr(self.android, 'ui_tree'):
            try:
                snapshot['ui_xml'] = self.android.ui_tree()
            except Exception:
                snapshot['ui_xml'] = ''
        return snapshot

    def _check_running_health(self, package, profile, health, now, cfg, snapshot):
        in_boot_grace = health['last_launch'] is not None and now - health['last_launch'] < cfg['bootGraceSec']
        ui_xml = snapshot.get('ui_xml')

        bounds = None
        if snapshot.get('tasks'):
            tid = parse_tasks(snapshot['tasks']).get(package)
            if tid is not None:
                bounds, _ = task_bounds(snapshot['tasks'], tid)

        ui_state = inspect_ui_state(ui_xml, package, bounds=bounds) if ui_xml else 'unknown'

        if ui_state == 'auth_required':
            health['auth_paused'] = True
            health['healthy'] = None
            profile['loginStatus'] = 'manual_verification_required'
            profile['healthState'] = 'auth_required'
            profile['healthMessage'] = 'Phát hiện màn hình đăng nhập hoặc CAPTCHA — tạm dừng tự rejoin để bạn xác minh thủ công.'
            return

        if in_boot_grace:
            health['window_closed_since'] = None
            health['disconnect_since'] = None
            profile['healthState'] = 'booting'
            profile['healthMessage'] = 'Đang trong thời gian chờ khởi động game (Boot grace).'
            return

        if ui_state == 'teleporting' and cfg['teleportDetection']:
            health['teleport_at'] = now
            health['disconnect_since'] = None
            profile['healthState'] = 'teleporting'
            profile['healthMessage'] = 'Phát hiện đang dịch chuyển server (Teleport grace).'

        peers = None
        if hasattr(self.android, 'udp_peers') and (cfg['teleportDetection'] or cfg['networkTimeoutSec'] > 0):
            try:
                peers = self.android.udp_peers(package)
            except Exception:
                peers = None

        if peers is not None:
            if peers and health['last_peers'] and peers != health['last_peers'] and cfg['teleportDetection']:
                health['teleport_at'] = now
                health['disconnect_since'] = None
                profile['healthState'] = 'teleporting'
                profile['healthMessage'] = 'Phát hiện chuyển kết nối server UDP (Teleport grace).'
            if peers:
                health['last_peers'] = set(peers)
                health['socket_lost_since'] = None
            elif cfg['networkTimeoutSec'] > 0 and health['last_peers']:
                in_tp_grace = cfg['teleportDetection'] and health['teleport_at'] is not None and now - health['teleport_at'] < cfg['teleportGraceSec']
                if not in_tp_grace:
                    if health['socket_lost_since'] is None:
                        health['socket_lost_since'] = now
                    elif now - health['socket_lost_since'] >= cfg['networkTimeoutSec']:
                        profile['healthState'] = 'disconnected'
                        self._recover_package(package, profile, health, now, cfg, 'Mất kết nối mạng UDP quá thời gian chờ; đang rejoin.', force_stop=True)
                        return

        if cfg['detectDisconnectUi'] and ui_state == 'disconnected':
            in_tp_grace = cfg['teleportDetection'] and health['teleport_at'] is not None and now - health['teleport_at'] < cfg['teleportGraceSec']
            if in_tp_grace:
                health['disconnect_since'] = None
                profile['healthState'] = 'teleporting'
                profile['healthMessage'] = 'Bỏ qua ngắt kết nối ngắn trong thời gian Teleport grace.'
            else:
                if health['disconnect_since'] is None:
                    health['disconnect_since'] = now
                profile['healthState'] = 'disconnected'
                profile['healthMessage'] = 'Phát hiện màn hình mất kết nối; đang chờ xác nhận.'
                if now - health['disconnect_since'] >= cfg['disconnectConfirmSec']:
                    self._recover_package(package, profile, health, now, cfg, 'Xác nhận mất kết nối; đang force-stop và rejoin.', force_stop=True)
                    return
        else:
            health['disconnect_since'] = None

        if cfg['rejoinOnCrash'] and hasattr(self.android, 'window_checks'):
            checks = self.android.window_checks(
                package,
                ui_xml=ui_xml,
                tasks_text=snapshot.get('tasks'),
                windows_text=snapshot.get('windows'),
                surface_text=snapshot.get('surfaces'),
            )
            closed, readable = evaluate_window_closed(checks)
            if not readable:
                # Never treat an unreadable window dump as a closed window.
                health['window_closed_since'] = None
            elif closed:
                if health['window_closed_since'] is None:
                    health['window_closed_since'] = now
                profile['healthState'] = 'window_closed'
                profile['healthMessage'] = 'Cửa sổ game chưa hiển thị (4 lớp xác nhận).'
                if now - health['window_closed_since'] >= cfg['windowGraceSec']:
                    live_min = bool(peers) and cfg['networkTimeoutSec'] > 0
                    reason = 'Cửa sổ bị thu nhỏ với kết nối sống; đang mở lại.' if live_min else 'Cửa sổ game bị đóng quá Window grace; đang rejoin.'
                    self._recover_package(package, profile, health, now, cfg, reason, force_stop=not live_min)
                    return
            else:
                health['window_closed_since'] = None

        health['healthy'] = health['healthy'] or now
        if profile.get('healthState') not in ('teleporting', 'disconnected', 'window_closed'):
            profile['healthState'] = 'running'
            profile['healthMessage'] = 'Đang hoạt động bình thường.'
        if now - health['healthy'] >= 60:
            health['attempts'].clear()
            profile['retryCount'] = 0
            profile.pop('error', None)

    def watchdog(self, now=None):
        cfg = self._rejoin_config()
        if not self.state.get('watch') or not cfg['enabled']:
            return
        now = time.monotonic() if now is None else now
        snapshot = None
        for package, profile in sorted(self.state['profiles'].items(), key=lambda item: item[1].get('slot', 99)):
            if not profile.get('enabled', True) or not profile.get('autoReopen', True):
                continue
            health = self.health.setdefault(package, self._new_health())
            if health.get('auth_paused'):
                profile['healthState'] = 'auth_required'
                continue
            if health['last_check'] is not None and now - health['last_check'] < cfg['checkIntervalSec']:
                continue
            health['last_check'] = now

            if self.android.running(package):
                health['missing'] = 0
                if snapshot is None:
                    snapshot = self._snapshot_cycle()
                self._check_running_health(package, profile, health, now, cfg, snapshot)
                continue

            health['healthy'] = None
            health['window_closed_since'] = None
            health['disconnect_since'] = None
            health['socket_lost_since'] = None
            health['missing'] += 1
            if profile.get('healthState') != 'paused_retries' or now >= health['next']:
                profile['healthState'] = 'crashed' if health['missing'] >= 2 else 'stopped'
            if not cfg['rejoinOnCrash'] or health['missing'] < 2:
                continue
            self._recover_package(package, profile, health, now, cfg, 'Tiến trình bị đóng; đang tự mở lại.', force_stop=False)

    def telemetry(self):
        profiles = {}
        try:
            tasks = parse_tasks(self.android.tasks())
        except Exception:
            tasks = {}
        for package, profile in self.state['profiles'].items():
            account = self.accounts.get(package)
            if account and account.get('username') and not profile.get('username'):
                profile['username'] = account['username']
            is_running = self.android.running(package)
            item = dict(profile, running=is_running, hasAccount=package in self.accounts)
            if account and account.get('username') and not item.get('username'):
                item['username'] = account['username']
            item['appStatus'] = 'running' if is_running else 'stopped'
            if 'healthState' not in item:
                item['healthState'] = 'running' if is_running else 'stopped'
            if package in tasks:
                item['taskId'] = tasks[package]
            # RTDB keys cannot contain periods; the package stays as a string field.
            profiles[hashlib.sha256(package.encode()).hexdigest()[:16]] = item
        _, model = root_command(['/system/bin/getprop', 'ro.product.model'], check=False)
        _, android = root_command(['/system/bin/getprop', 'ro.build.version.release'], check=False)
        return {'lastSeen': int(time.time()*1000), 'model': model.strip() or 'Redfinger Device',
                'android': android.strip() or 'Android',
                'watch': self.state['watch'], 'rejoinConfig': self._rejoin_config(),
                'profiles': profiles, 'grid': self.state.get('grid'),
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
