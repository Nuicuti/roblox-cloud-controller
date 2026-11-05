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
    tapped = False
    try:
        root_command(['/system/bin/uiautomator', 'dump', '--compressed', '/data/local/tmp/ui_dump.xml'], check=False)
        _, xml_content = root_command(['/system/bin/cat', '/data/local/tmp/ui_dump.xml'], check=False)
        if xml_content and 'hierarchy' in xml_content:
            for pattern in ('Đăng nhập', 'Log In', 'Log in', 'LOG IN', 'Login', 'ĐĂNG NHẬP'):
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
    except Exception:
        pass

    # Nếu uiautomator không tìm thấy (do dump chậm hoặc OpenGL canvas),
    # fallback chạm vào tọa độ ước lượng của nút Đăng nhập trên 4 góc màn hình
    if not tapped:
        try:
            _, output = root_command(['/system/bin/wm', 'size'], check=False)
            sizes = re.findall(r'(?:Physical|Override) size:\s*(\d+)x(\d+)', output)
            if sizes:
                w, h = map(int, sizes[-1])
                quadrant_login_points = [
                    (int(w * 0.25), int(h * 0.42)),
                    (int(w * 0.75), int(h * 0.42)),
                    (int(w * 0.25), int(h * 0.92)),
                    (int(w * 0.75), int(h * 0.92)),
                ]
                for x, y in quadrant_login_points:
                    root_command(['/system/bin/input', 'tap', str(x), str(y)], check=False)
                    time.sleep(0.2)
                tapped = True
        except Exception:
            pass

    return tapped

def login_account(account, root_command):
    _main = sys.modules.get('__main__')
    AgentErrorClass = getattr(_main, 'AgentError', LoginError)

    cookie = account['cookie'].strip()
    if cookie.startswith('.ROBLOSECURITY='):
        cookie = cookie[len('.ROBLOSECURITY='):]
    if '\r' in cookie or '\n' in cookie:
        raise AgentErrorClass('Cookie chứa ký tự xuống dòng không hợp lệ.')

    # 1. Thử xác thực với Roblox API (nếu mạng hoặc Cloudflare chặn 403 trên IP Redfinger, không chặn nạp SQLite)
    cookie_status = 'unverified'
    actual_user = account.get('username', '')
    try:
        url = 'https://users.roblox.com/v1/users/authenticated'
        headers = {
            'Cookie': '.ROBLOSECURITY=' + cookie,
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'application/json, text/plain, */*',
            'Accept-Language': 'vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7',
            'Origin': 'https://www.roblox.com',
            'Referer': 'https://www.roblox.com/'
        }
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=8) as response:
            identity = json.load(response)
            if identity.get('name'):
                actual_user = identity['name']
                account['username'] = actual_user
                cookie_status = 'valid'
    except urllib.error.HTTPError as http_err:
        if http_err.code == 401:
            cookie_status = 'invalid'
            raise AgentErrorClass('Roblox xác nhận cookie đã hết hạn hoặc không hợp lệ (HTTP 401).') from None
        # HTTP 403 do Cloudflare WAF chặn IP Redfinger -> cho phép nạp trực tiếp vào SQLite app
        cookie_status = 'unverified'
    except Exception:
        cookie_status = 'unverified'

    # 2. Tắt app trước khi ghi SQLite
    package = account['package']
    root_command(['/system/bin/am', 'force-stop', '--user', '0', package])

    # 3. Ghi cookie an toàn qua file tạm nằm trong thư mục riêng của Termux (tránh lỗi Permission denied ở /data/local/tmp)
    private_dir = Path('/data/data/com.termux/files/home/.noka-controller')
    private_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    payload_file = private_dir / f'noka_ck_{package}.json'
    try:
        payload_file.write_text(json.dumps({'package': package, 'cookie': cookie}), encoding='utf-8')
        os.chmod(payload_file, 0o644)
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
        lines = [line.strip() for line in output.splitlines() if line.strip()]
        result = json.loads(lines[-1]) if lines else {}
    except (ValueError, IndexError):
        raise AgentErrorClass(f'Lỗi nạp cookie vào SQLite ({output[:200] or "Không có kết quả"})') from None

    if not result.get('ok'):
        raise AgentErrorClass(result.get('message', 'Không ghi được cookie vào SQLite.'))

    # 4. Mở Noka
    _, output = root_command(['/system/bin/cmd', 'package', 'resolve-activity', '--brief', '--user', '0',
        '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-p', package])
    component = next((line.strip() for line in reversed(output.splitlines()) if line.strip().startswith(package+'/')), None)
    if not component:
        raise AgentErrorClass('Đã nạp cookie nhưng chưa tìm thấy màn hình khởi động của ' + package)
    root_command(['/system/bin/am', 'start', '-W', '--user', '0', '-n', component])

    return {
        'status': 'ok',
        'cookieStatus': cookie_status,
        'message': f'Đã nạp cookie vào SQLite ({actual_user or "Roblox"}) và mở ứng dụng.'
    }
