"""Use account cookies supplied by the operator; keep secrets on this device."""
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request

class LoginError(Exception):
    pass

def tap_login_buttons(root_command):
    """Tự động tìm và bấm tất cả các nút 'Đăng nhập' / 'Log In' của Noka trên màn hình."""
    try:
        root_command(['/system/bin/uiautomator', 'dump', '/data/local/tmp/ui_dump.xml'], check=False)
        _, xml_content = root_command(['/system/bin/cat', '/data/local/tmp/ui_dump.xml'], check=False)
        if not xml_content:
            return False
        tapped = False
        for pattern in ('Đăng nhập', 'Log In', 'Log in'):
            regex = rf'text="([^"]*{pattern}[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
            matches = re.findall(regex, xml_content, re.IGNORECASE)
            if not matches:
                regex_desc = rf'content-desc="([^"]*{pattern}[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
                matches = re.findall(regex_desc, xml_content, re.IGNORECASE)
            for m in matches:
                x1, y1, x2, y2 = int(m[1]), int(m[2]), int(m[3]), int(m[4])
                cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
                root_command(['/system/bin/input', 'tap', str(cx), str(cy)], check=False)
                tapped = True
        return tapped
    except Exception:
        return False

def login_account(account, root_command):
    _main = sys.modules.get('__main__')
    AgentErrorClass = getattr(_main, 'AgentError', LoginError)

    cookie = account['cookie'].strip()
    if cookie.startswith('.ROBLOSECURITY='):
        cookie = cookie[len('.ROBLOSECURITY='):]
    if '\r' in cookie or '\n' in cookie:
        raise AgentErrorClass('Cookie chứa ký tự xuống dòng không hợp lệ.')
    request = urllib.request.Request('https://users.roblox.com/v1/users/authenticated',
        headers={'Cookie': '.ROBLOSECURITY='+cookie, 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            identity = json.load(response)
    except urllib.error.HTTPError as http_err:
        raise AgentErrorClass(f'Roblox từ chối cookie (HTTP {http_err.code}); cookie có thể đã hết hạn hoặc không hợp lệ.') from None
    except (urllib.error.URLError, TimeoutError) as net_err:
        raise AgentErrorClass(f'Lỗi kết nối kiểm tra cookie với Roblox: {net_err}') from None

    actual_user = identity.get('name', '')
    if actual_user:
        account['username'] = actual_user

    package = account['package']
    root_command(['/system/bin/am', 'force-stop', '--user', '0', package])

    # Ghi cookie an toàn qua file tạm /data/local/tmp (hoàn toàn tránh được lỗi nuốt stdin của su)
    payload_file = Path('/data/local/tmp') / f'noka_ck_{package}.json'
    try:
        payload_file.write_text(json.dumps({'package': package, 'cookie': cookie}), encoding='utf-8')
        os.chmod(payload_file, 0o600)
        helper = str(Path(__file__).with_name('cookie_helper.py').resolve())
        PREFIX = '/data/data/com.termux/files/usr'
        code, output = root_command(['/system/bin/env', f'LD_LIBRARY_PATH={PREFIX}/lib',
            f'TMPDIR={PREFIX}/tmp', f'PREFIX={PREFIX}', PREFIX+'/bin/python', '-I', helper, str(payload_file)],
            check=False)
    finally:
        if payload_file.exists():
            try:
                payload_file.unlink()
            except Exception:
                pass

    try:
        # Tìm dòng JSON cuối cùng trong output
        lines = [line.strip() for line in output.splitlines() if line.strip()]
        result = json.loads(lines[-1]) if lines else {}
    except (ValueError, IndexError):
        raise AgentErrorClass(f'Lỗi nạp cookie vào SQLite ({output[:200] or "Không có kết quả"})') from None

    if not result.get('ok'):
        raise AgentErrorClass(result.get('message', 'Không ghi được cookie vào SQLite.'))

    _, output = root_command(['/system/bin/cmd', 'package', 'resolve-activity', '--brief', '--user', '0',
        '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-p', package])
    component = next((line.strip() for line in reversed(output.splitlines()) if line.strip().startswith(package+'/')), None)
    if not component:
        raise AgentErrorClass('Đã nạp cookie nhưng chưa tìm thấy màn hình khởi động của ' + package)
    root_command(['/system/bin/am', 'start', '-W', '--user', '0', '-n', component])
    return f'Nạp thành công ({actual_user or "Roblox"})'
