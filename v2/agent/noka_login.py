"""Use account cookies supplied by the operator; keep secrets on this device."""
import json
from pathlib import Path
import urllib.error
import urllib.request

def login_account(account, root_command):
    from noka_agent import AgentError, PREFIX
    cookie = account['cookie'].strip()
    if cookie.startswith('.ROBLOSECURITY='):
        cookie = cookie[len('.ROBLOSECURITY='):]
    if '\r' in cookie or '\n' in cookie:
        raise AgentError('Cookie chứa ký tự xuống dòng không hợp lệ.')
    request = urllib.request.Request('https://users.roblox.com/v1/users/authenticated',
        headers={'Cookie': '.ROBLOSECURITY='+cookie, 'User-Agent': 'NokaController/1.0'})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            identity = json.load(response)
    except urllib.error.HTTPError:
        raise AgentError('Roblox không chấp nhận cookie; cần kiểm tra lại phiên đăng nhập.') from None
    except (urllib.error.URLError, TimeoutError):
        raise AgentError('Chưa xác minh được cookie do lỗi kết nối Roblox.') from None
    if identity.get('name', '').casefold() != account['username'].casefold():
        raise AgentError('Cookie thuộc tài khoản khác tên đã nhập; dừng để tránh gán nhầm.')
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
    return 'Cookie hợp lệ, đã ghi và mở Noka. Trạng thái đăng nhập trong ứng dụng chưa được xác minh.'
