"""Use account cookies supplied by the operator; keep secrets on this device."""
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.request

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
    from noka_agent import AgentError, PREFIX
    cookie = account['cookie'].strip()
    if cookie.startswith('.ROBLOSECURITY='):
        cookie = cookie[len('.ROBLOSECURITY='):]
    if '\r' in cookie or '\n' in cookie:
        raise AgentError('Cookie chứa ký tự xuống dòng không hợp lệ.')
    request = urllib.request.Request('https://users.roblox.com/v1/users/authenticated',
        headers={'Cookie': '.ROBLOSECURITY='+cookie, 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            identity = json.load(response)
    except urllib.error.HTTPError:
        raise AgentError('Roblox không chấp nhận cookie; cần kiểm tra lại phiên đăng nhập.') from None
    except (urllib.error.URLError, TimeoutError):
        raise AgentError('Chưa xác minh được cookie do lỗi kết nối Roblox.') from None

    actual_user = identity.get('name', '')
    if actual_user:
        # Đồng bộ chính xác tên người dùng thực tế từ Roblox
        account['username'] = actual_user

    package = account['package']
    root_command(['/system/bin/am', 'force-stop', '--user', '0', package])
    # Credentials travel over stdin, never in the shell command/process arguments.
    helper = str(Path(__file__).with_name('cookie_helper.py').resolve())
    _, output = root_command(['/system/bin/env', f'LD_LIBRARY_PATH={PREFIX}/lib',
        f'TMPDIR={PREFIX}/tmp', f'PREFIX={PREFIX}', PREFIX+'/bin/python', '-I', helper],
        stdin=json.dumps({'package': package, 'cookie': cookie}))
    try:
        result = json.loads(output)
    except ValueError:
        raise AgentError('Chưa xác minh được việc ghi cookie trên máy.') from None
    if not result.get('ok'):
        raise AgentError(result.get('message', 'Không ghi được cookie.'))
    _, output = root_command(['/system/bin/cmd', 'package', 'resolve-activity', '--brief', '--user', '0',
        '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-p', package])
    component = next((line.strip() for line in reversed(output.splitlines()) if line.strip().startswith(package+'/')), None)
    if not component:
        raise AgentError('Đã lưu cookie nhưng chưa tìm thấy màn hình mở Noka.')
    root_command(['/system/bin/am', 'start', '-W', '--user', '0', '-n', component])
    return f'Cookie hợp lệ ({actual_user or "Roblox"}), đã ghi vào SQLite và mở Noka.'
