#!/data/data/com.termux/files/usr/bin/python
"""Android Cloud Rejoin Controller Agent. Fixed commands only; no remote shell."""
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
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from android_os import (
    AgentError,
    Android,
    PACKAGE,
    build_roblox_uri,
    check_screen_tree,
    check_surfaces,
    check_task_list,
    check_window_manager,
    evaluate_window_closed,
    grid_bounds,
    harden_runtime,
    inspect_activity_auth_or_anr,
    inspect_ui_state,
    parse_proc_net_udp,
    parse_tasks,
    root_command,
    task_bounds,
)

ROOT = 'nokaController/v1'
PRIVATE = Path('/data/data/com.termux/files/home/.noka-controller')
LOG = logging.getLogger('rejoin_agent')

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
        raise AgentError(f'Dịch vụ trả HTTP {error.code}; kiểm tra quyền và cấu hình.') from None
    except (urllib.error.URLError, TimeoutError):
        raise AgentError('Không kết nối được Firebase. Agent sẽ thử lại.') from None


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


class Firebase:
    def __init__(self, private=PRIVATE):
        self.private = Path(private)
        self.config = read_json(self.private / 'config.json', {})
        self.auth = read_json(self.private / 'auth.json', {})
        self.token = ''
        self.expiry = 0
        self.uid = self.auth.get('uid', '')

    def authenticate(self):
        if time.monotonic() < self.expiry:
            return
        api_key = self.config['apiKey']
        if self.auth.get('refreshToken'):
            data = http(
                'https://securetoken.googleapis.com/v1/token?key=' + urllib.parse.quote(api_key),
                'POST', {'grant_type': 'refresh_token', 'refresh_token': self.auth['refreshToken']}, form=True,
            )
            self.token, self.uid = data['id_token'], data['user_id']
            refresh, duration = data['refresh_token'], data['expires_in']
        else:
            data = http(
                'https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=' + urllib.parse.quote(api_key),
                'POST', {'returnSecureToken': True},
            )
            self.token, self.uid = data['idToken'], data['localId']
            refresh, duration = data['refreshToken'], data['expiresIn']
        self.auth = {'uid': self.uid, 'refreshToken': refresh}
        atomic_json(self.private / 'auth.json', self.auth)
        self.expiry = time.monotonic() + int(duration) - 120

    def request(self, path, method='GET', payload=None):
        self.authenticate()
        encoded_path = '/'.join(urllib.parse.quote(part, safe='') for part in (ROOT + '/' + path).split('/'))
        url = self.config['databaseURL'].rstrip('/') + '/' + encoded_path + '.json?' + urllib.parse.urlencode({'auth': self.token})
        return http(url, method, payload)

    def device(self, suffix, method='GET', payload=None):
        self.authenticate()
        return self.request(f'devices/{self.uid}/{suffix}', method, payload)


class Manager:
    def __init__(self, private=PRIVATE, android=None):
        self.private = Path(private)
        self.android = android or Android()
        self.state = read_json(self.private / 'state.json', {'profiles': {}, 'watch': False, 'grid': None})
        self.state['rejoinConfig'] = normalize_rejoin_config(self.state.get('rejoinConfig'), watch=self.state.get('watch', False))
        self.accounts = read_json(self.private / 'accounts.json', {})
        self.health = {}
        self.launch_queue = deque()
        self.last_any_launch = None
        self.last_any_launch_pkg = None
        self.last_ui_tree_at = None
        self.public_key = None
        self._sync_usernames()

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
        atomic_json(self.private / 'state.json', self.state)

    def _rejoin_config(self):
        cfg = normalize_rejoin_config(self.state.get('rejoinConfig'), watch=self.state.get('watch', False))
        cfg['enabled'] = bool(self.state.get('watch', cfg['enabled']))
        return cfg

    def _effective_target(self, profile, cfg):
        place_id = (profile.get('placeId') or cfg.get('placeId') or '').strip()
        job_id = (profile.get('jobId') if profile.get('placeId') else cfg.get('jobId')) or ''
        return place_id, job_id.strip()

    def key(self):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        path = self.private / 'device-key.pem'
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
            return base64.urlsafe_b64encode(number.to_bytes((number.bit_length() + 7) // 8, 'big')).rstrip(b'=').decode()
        self.public_key = {'kty': 'RSA', 'n': b64int(numbers.n), 'e': b64int(numbers.e),
                           'alg': 'RSA-OAEP-256', 'ext': True, 'key_ops': ['encrypt']}
        return key

    def scan(self):
        installed = self.android.packages()
        profiles = self.state['profiles']
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
                label = 'Noka ' + str(ord(suffix) - 64) if len(suffix) == 1 and 'A' <= suffix <= 'Z' else package
            elif package == 'com.roblox.client':
                label = 'Roblox Quốc tế'
            else:
                label = 'Roblox ' + package.removeprefix('com.roblox.client.')
            username = self.accounts.get(package, {}).get('username', '')
            profiles[package] = {'package': package, 'label': label, 'username': username, 'slot': slot, 'enabled': True, 'autoReopen': True}
        for p in profiles.values():
            p.setdefault('autoReopen', True)
        self.save()
        return f'Phát hiện {len(installed)} bản clone. Cấu hình cũ được giữ nguyên.'

    def profile(self, package):
        if not PACKAGE.fullmatch(package) or package not in self.state['profiles']:
            raise AgentError('Bản clone chưa được quét và thêm vào quản lý.')
        return self.state['profiles'][package]

    def layout(self, settings, launch=True):
        if not self.android.freeform():
            raise AgentError('Máy chưa hỗ trợ/bật freeform. Dùng Bật cửa sổ tự do, rồi kiểm tra lại.')
        width, height = self.android.size()
        cells = grid_bounds(width, height, **settings)
        selected = [p for p in self.state['profiles'].values() if p['enabled']]
        if not selected:
            raise AgentError('Chưa có bản nào bật quản lý.')
        if any(p['slot'] > len(cells) for p in selected):
            raise AgentError('Có bản nằm ngoài số ô lưới; chỉnh vị trí hoặc tăng hàng/cột.')
        failures = []
        for profile in sorted(selected, key=lambda p: p['slot']):
            if not launch and not self.android.running(profile['package']):
                continue
            try:
                self.android.resize(profile['package'], cells[profile['slot'] - 1], launch=launch)
                profile.pop('error', None)
            except AgentError as error:
                profile['error'] = str(error)
                failures.append(profile['label'] + ': ' + str(error))
        self.save()
        if failures:
            raise AgentError('; '.join(failures))

    def import_accounts(self, sealed):
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        try:
            key = self.key().decrypt(
                base64.b64decode(sealed['key'], validate=True),
                padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
            )
            raw = AESGCM(key).decrypt(
                base64.b64decode(sealed['iv'], validate=True),
                base64.b64decode(sealed['data'], validate=True),
                None,
            )
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
        atomic_json(self.private / 'accounts.json', updated)
        self.accounts = updated
        for record in records:
            self.state['profiles'][record['package']]['username'] = record['username']
        self.save()
        return f'Đã lưu {len(records)} tài khoản riêng trên máy.'

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
                raise AgentError('Vị trí bản nằm ngoài lưới.')
            if place_id:
                self.android.resize(package, cells[profile['slot'] - 1], place_id=place_id, job_id=job_id)
            else:
                self.android.resize(package, cells[profile['slot'] - 1])
        else:
            if place_id:
                self.android.open(package, place_id=place_id, job_id=job_id)
            else:
                self.android.open(package)

    def open_profile(self, package, force_stop=False, now=None):
        now = time.monotonic() if now is None else now
        profile = self.profile(package)
        profile['enabled'] = True
        profile.pop('error', None)
        profile['healthState'] = 'booting'
        profile['healthMessage'] = 'Đang khởi chạy vào game.'
        cfg = self._rejoin_config()
        place_id, job_id = self._effective_target(profile, cfg)
        self.save()
        self._launch_package(package, place_id=place_id, job_id=job_id, force_stop=force_stop)
        health = self._new_health()
        health['last_check'] = now
        health['last_launch'] = now
        self.health[package] = health
        self.last_any_launch = now
        self.last_any_launch_pkg = package
        profile['lastRejoinAt'] = int(time.time() * 1000)
        self.save()

    def enqueue_launch(self, package, force_stop=False):
        self.launch_queue = deque(item for item in self.launch_queue if item['package'] != package)
        self.launch_queue.append({'package': package, 'force_stop': force_stop})
        profile = self.profile(package)
        profile['healthState'] = 'booting'
        profile['healthMessage'] = 'Đang xếp hàng khởi chạy tuần tự.'

    def drain_launch_queue(self, now=None):
        """Launch at most one queued clone per cycle respecting delayBetweenClonesSec."""
        if not self.launch_queue:
            return False
        now = time.monotonic() if now is None else now
        cfg = self._rejoin_config()
        for _ in range(len(self.launch_queue)):
            item = self.launch_queue[0]
            package = item['package']
            health = self.health.setdefault(package, self._new_health())
            if self._can_launch(package, health, now, cfg):
                self.launch_queue.popleft()
                profile = self.profile(package)
                try:
                    self.open_profile(package, force_stop=item['force_stop'], now=now)
                except (AgentError, IndexError) as error:
                    profile['error'] = str(error)
                    profile['healthMessage'] = str(error)
                return True
            self.launch_queue.rotate(-1)
        return False

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
            return 'Đã lưu cấu hình bản clone.'
        if action == 'set_watch':
            if type(command['enabled']) is not bool:
                raise AgentError('Trạng thái tự mở lại không hợp lệ.')
            self.state['watch'] = command['enabled']
            self.state['rejoinConfig'] = normalize_rejoin_config(self.state.get('rejoinConfig'), watch=command['enabled'])
            self.state['rejoinConfig']['enabled'] = command['enabled']
            self.health.clear()
            self.launch_queue.clear()
            self.save()
            return 'Đã bật Auto-rejoin.' if command['enabled'] else 'Đã tắt Auto-rejoin.'
        if action == 'set_rejoin_config':
            raw = command.get('config')
            if not isinstance(raw, dict) or set(raw) != set(DEFAULT_REJOIN_CONFIG):
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
            self.launch_queue = deque(item for item in self.launch_queue if item['package'] != profile['package'])
            self.save()
            self.android.close(profile['package'])
            return 'Đã đóng và tạm dừng tự mở lại cho bản này.'
        if action == 'open':
            profile = self.profile(command['package'])
            profile['enabled'] = True
            profile['autoReopen'] = True
            self.open_profile(command['package'])
            return f"Đã mở {profile['label']}."
        if action == 'rejoin':
            profile = self.profile(command['package'])
            profile['enabled'] = True
            profile['autoReopen'] = True
            self.open_profile(command['package'], force_stop=True)
            return f"Đã rejoin {profile['label']}."
        if action in ('open_all', 'rejoin_all'):
            force_stop = action == 'rejoin_all'
            selected = sorted((p for p in self.state['profiles'].values() if p['enabled']), key=lambda p: p['slot'])
            if not selected:
                raise AgentError('Chưa có bản nào đang bật quản lý.')
            cfg = self._rejoin_config()
            if cfg['delayBetweenClonesSec'] > 0:
                for p in selected:
                    self.health.pop(p['package'], None)
                    self.enqueue_launch(p['package'], force_stop=force_stop)
                self.drain_launch_queue()
                verb = 'rejoin' if force_stop else 'mở'
                return f'Đã xếp hàng {verb} tuần tự {len(selected)} bản (giãn cách {cfg["delayBetweenClonesSec"]}s).'
            for p in selected:
                self.open_profile(p['package'], force_stop=force_stop)
            verb = 'rejoin' if force_stop else 'mở'
            return f'Đã {verb} {len(selected)} bản đang bật.'
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
            from login_flow import login_account
            messages = []
            failed = False
            for profile in sorted(self.state['profiles'].values(), key=lambda p: p['slot']):
                account = self.accounts.get(profile['package'])
                if not account or not profile['enabled']:
                    continue
                try:
                    res = login_account(account, root_command)
                    profile['cookieStatus'] = res.get('cookieStatus', 'unverified')
                    msg = res.get('message', 'Đã nạp cookie vào SQLite.')
                    profile['sqliteStatus'] = 'written'
                    profile['loginStatus'] = 'unverified_ingame'
                    profile['loginMessage'] = msg
                    profile.pop('error', None)
                    self.enqueue_launch(profile['package'], force_stop=False)
                    messages.append(profile['label'] + ': ' + msg)
                except Exception as error:
                    message = str(error)
                    if '401' in message:
                        profile['cookieStatus'] = 'invalid'
                    elif 'kho cookie' in message or 'ghi cookie' in message or 'SQLite' in message:
                        profile['sqliteStatus'] = 'failed'
                    failed = True
                    profile['loginMessage'] = message
                    profile['error'] = message
                    messages.append(profile['label'] + ': ' + message)
            self.save()
            if not messages:
                raise AgentError('Chưa có tài khoản trên các bản đang bật.')
            self.drain_launch_queue()
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
        if cfg['delayBetweenClonesSec'] > 0 and self.last_any_launch is not None and self.last_any_launch_pkg != package:
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

    def _snapshot_cycle(self, now, cfg):
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
        # Throttle uiautomator dump to avoid stalling the loop when OpenGL SurfaceView renders continuously.
        ui_cadence = max(cfg['checkIntervalSec'], 15) if self.last_ui_tree_at is not None else 0
        if hasattr(self.android, 'ui_tree') and (self.last_ui_tree_at is None or now - self.last_ui_tree_at >= ui_cadence or cfg['checkIntervalSec'] >= 5):
            try:
                snapshot['ui_xml'] = self.android.ui_tree()
            except Exception:
                snapshot['ui_xml'] = ''
            self.last_ui_tree_at = now
        return snapshot

    def _check_running_health(self, package, profile, health, now, cfg, snapshot):
        in_boot_grace = health['last_launch'] is not None and now - health['last_launch'] < cfg['bootGraceSec']
        tasks_text = snapshot.get('tasks')
        windows_text = snapshot.get('windows')
        ui_xml = snapshot.get('ui_xml')

        bounds = None
        if tasks_text:
            tid = parse_tasks(tasks_text).get(package)
            if tid is not None:
                bounds, _ = task_bounds(tasks_text, tid)

        fast_state = inspect_activity_auth_or_anr(tasks_text, windows_text, package)
        ui_state = inspect_ui_state(ui_xml, package, bounds=bounds) if ui_xml else 'unknown'
        if fast_state == 'auth_required' or ui_state == 'auth_required':
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

        effective_disconnect = fast_state == 'disconnected' or ui_state == 'disconnected'
        if cfg['detectDisconnectUi'] and effective_disconnect:
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
                tasks_text=tasks_text,
                windows_text=windows_text,
                surface_text=snapshot.get('surfaces'),
            )
            closed, readable = evaluate_window_closed(checks)
            if not readable:
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
        now = time.monotonic() if now is None else now
        self.drain_launch_queue(now)
        cfg = self._rejoin_config()
        if not self.state.get('watch') or not cfg['enabled']:
            return
        snapshot = None
        for package, profile in sorted(self.state['profiles'].items(), key=lambda item: item[1].get('slot', 99)):
            if not profile.get('enabled', True) or not profile.get('autoReopen', True):
                continue
            if any(q['package'] == package for q in self.launch_queue):
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
                    snapshot = self._snapshot_cycle(now, cfg)
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
            profiles[hashlib.sha256(package.encode()).hexdigest()[:16]] = item
        _, model = root_command(['/system/bin/getprop', 'ro.product.model'], check=False, timeout=5)
        _, android = root_command(['/system/bin/getprop', 'ro.build.version.release'], check=False, timeout=5)
        return {
            'lastSeen': int(time.time() * 1000),
            'model': model.strip() or 'Redfinger Device',
            'android': android.strip() or 'Android',
            'watch': self.state['watch'],
            'rejoinConfig': self._rejoin_config(),
            'profiles': profiles,
            'grid': self.state.get('grid'),
            'freeform': bool(self.android.freeform()),
            'publicKey': self.public_key,
        }


def process_command(firebase, manager, command, journal):
    identifier = command.get('id', '')
    if not re.fullmatch(r'[a-f0-9-]{36}', identifier):
        raise AgentError('Mã lệnh không hợp lệ.')
    if identifier in journal:
        result = journal[identifier]
        if result['status'] == 'running':
            result = dict(result, status='failed', message='Agent đã dừng giữa lệnh; kiểm tra máy trước khi gửi lại.', finishedAt=int(time.time() * 1000))
    else:
        result = {'id': identifier, 'action': command.get('action', ''), 'createdAt': command['createdAt'], 'status': 'running'}
        journal[identifier] = result
        atomic_json(manager.private / 'journal.json', journal)
        if command.get('expiresAt', 0) < time.time() * 1000:
            result = dict(result, status='failed', message='Lệnh đã hết hạn trước khi máy nhận.', finishedAt=int(time.time() * 1000))
        else:
            firebase.device('results/' + identifier, 'PUT', result)
            try:
                message = manager.execute(command)
                result = dict(result, status='succeeded', message=message[:3000], finishedAt=int(time.time() * 1000))
            except Exception as error:
                import traceback
                LOG.error('Command %s error: %s\n%s', identifier, error, traceback.format_exc())
                message = str(error) or type(error).__name__
                result = dict(result, status='failed', message=message[:3000], finishedAt=int(time.time() * 1000))
    journal[identifier] = result
    if len(journal) > 1000:
        for old in sorted(journal, key=lambda k: journal[k]['createdAt'])[:-800]:
            journal.pop(old)
    atomic_json(manager.private / 'journal.json', journal)
    firebase.device('results/' + identifier, 'PUT', result)
    firebase.device('inbox/' + identifier, 'DELETE')


def setup(api_key=None, database=None):
    PRIVATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(PRIVATE, 0o700)
    config = read_json(PRIVATE / 'config.json', {})
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
    atomic_json(PRIVATE / 'config.json', {'apiKey': api_key, 'databaseURL': database})
    manager = Manager()
    manager.key()
    firebase = Firebase()
    firebase.authenticate()
    if firebase.device('meta'):
        print('\n[i] Máy đã ghép trước đó trên Dashboard. UID:', firebase.uid)
        return
    code = secrets.token_hex(16)
    firebase.request('pairingCodes/' + code, 'PUT', {'deviceUid': firebase.uid, 'expiresAt': int(time.time() * 1000) + 600_000})
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
    lock = (PRIVATE / 'agent.lock').open('a+')
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

    handler = RotatingFileHandler(PRIVATE / 'agent.log', maxBytes=300000, backupCount=2, encoding='utf-8')
    LOG.addHandler(handler)
    LOG.setLevel(logging.INFO)
    code, root_id = root_command(['/system/bin/id', '-u'], check=False, timeout=5)
    if code != 0 or root_id.strip() != '0':
        print('[!] LỖI: Termux chưa được cấp quyền root (SuperUser)!', file=sys.stderr)
        print('👉 Hãy gõ "su" trong Termux và chọn "Cho phép" (Grant / Always allow).', file=sys.stderr)
        raise AgentError('Cần cấp root cho Termux.')

    harden_runtime(os.getpid())
    firebase = Firebase()
    if not firebase.config:
        raise AgentError('Chạy setup trước.')
    manager = Manager()
    manager.key()
    manager.scan()
    journal = read_json(PRIVATE / 'journal.json', {})
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
            if manager.state.get('grid') and now - last_reflow > 30:
                try:
                    manager.layout(manager.state['grid'], launch=False)
                except AgentError as error:
                    LOG.info('Grid: %s', str(error))
                last_reflow = now
            if now - last_report > 12:
                firebase.device('telemetry', 'PUT', manager.telemetry())
                last_report = now
            retry = 0
            time.sleep(1.5)
        except (AgentError, subprocess.TimeoutExpired, OSError) as error:
            LOG.warning('Loop warning: %s: %s', type(error).__name__, str(error))
            print(f'[!] Cảnh báo vòng lặp agent: {type(error).__name__}: {error}', file=sys.stderr)
            try:
                manager.watchdog()
            except Exception:
                pass
            retry = min(retry + 1, 5)
            time.sleep(min(30, 2 ** retry))
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
