"""Verify operator-supplied cookies and write them to isolated WebView SQLite."""
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request

from android_os import AgentError


def login_account(account, root_command):
    _main = sys.modules.get('__main__')
    error_cls = getattr(_main, 'AgentError', AgentError)

    cookie = account['cookie'].strip()
    if cookie.startswith('.ROBLOSECURITY='):
        cookie = cookie[len('.ROBLOSECURITY='):]
    if '\r' in cookie or '\n' in cookie:
        raise error_cls('Cookie chứa ký tự xuống dòng không hợp lệ.')

    cookie_status = 'unverified'
    actual_user = account.get('username', '')
    try:
        req = urllib.request.Request(
            'https://users.roblox.com/v1/users/authenticated',
            headers={
                'Cookie': '.ROBLOSECURITY=' + cookie,
                'User-Agent': 'Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36',
                'Accept': 'application/json, text/plain, */*',
                'Origin': 'https://www.roblox.com',
                'Referer': 'https://www.roblox.com/',
            },
        )
        with urllib.request.urlopen(req, timeout=8) as response:
            identity = json.load(response)
            if identity.get('name'):
                actual_user = identity['name']
                account['username'] = actual_user
                cookie_status = 'valid'
    except urllib.error.HTTPError as http_err:
        if http_err.code == 401:
            raise error_cls('Roblox xác nhận cookie đã hết hạn hoặc không hợp lệ (HTTP 401).') from None
        cookie_status = 'unverified'
    except Exception:
        cookie_status = 'unverified'

    package = account['package']
    root_command(['/system/bin/am', 'force-stop', '--user', '0', package])

    private_dir = Path('/data/data/com.termux/files/home/.noka-controller')
    private_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    payload_file = private_dir / f'noka_ck_{package}.json'
    try:
        payload_file.write_text(json.dumps({'package': package, 'cookie': cookie}), encoding='utf-8')
        os.chmod(payload_file, 0o600)
        helper = str(Path(__file__).with_name('cookie_store.py').resolve())
        prefix = '/data/data/com.termux/files/usr'
        _, output = root_command([
            '/system/bin/env', f'LD_LIBRARY_PATH={prefix}/lib',
            f'TMPDIR={prefix}/tmp', f'PREFIX={prefix}', f'{prefix}/bin/python', '-I', helper, str(payload_file),
        ], check=False, timeout=20)
    finally:
        if payload_file.exists():
            try:
                payload_file.unlink()
            except Exception:
                pass

    try:
        lines = [line.strip() for line in output.splitlines() if line.strip()]
        result = json.loads(lines[-1]) if lines else {}
    except (ValueError, IndexError):
        raise error_cls(f'Lỗi nạp cookie vào SQLite ({output[:200] or "Không có kết quả"})') from None

    if not result.get('ok'):
        raise error_cls(result.get('message', 'Không ghi được cookie vào SQLite.'))

    return {
        'status': 'ok',
        'cookieStatus': cookie_status,
        'message': f'Đã nạp cookie vào SQLite ({actual_user or "Roblox"}).',
    }
