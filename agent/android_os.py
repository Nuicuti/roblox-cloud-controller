"""Android OS process, windowing, SurfaceFlinger, UI tree, and socket inspection."""
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE_PATTERN = r'(?:[A-Za-z][A-Za-z0-9_]*\.)*[A-Za-z0-9_]*(?:noka|Noka|NOKA|roblox|Roblox|ROBLOX|arceus|Arceus|ARCEUS)[A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)*'
PACKAGE = re.compile(PACKAGE_PATTERN + r'\Z')
SU_BIN = next((p for p in ('/system/xbin/su', '/system/bin/su', '/sbin/su', 'su') if os.path.exists(p)), 'su')


class AgentError(Exception):
    pass


def root_command(args, *, stdin=None, check=True, timeout=15):
    cmd = [SU_BIN, '-c', shlex.join([str(a) for a in args])]
    result = subprocess.run(cmd, input=stdin, capture_output=True, text=True, timeout=timeout)
    output = (result.stdout + '\n' + result.stderr).strip()
    failed = result.returncode != 0 or bool(re.search(r'(?m)^(Error:|Exception |java\.|SecurityException)', output))
    if failed and check:
        raise AgentError(output[:500] or 'Lệnh Android không thành công.')
    return result.returncode, output


def harden_runtime(pid=None):
    """Best-effort root hardening so Android lmkd/Doze/PhantomKiller does not kill the agent."""
    target_pid = pid or os.getpid()
    commands = (
        ['/system/bin/sh', '-c', f'echo -1000 > /proc/{int(target_pid)}/oom_score_adj'],
        ['/system/bin/dumpsys', 'deviceidle', 'whitelist', '+com.termux'],
        ['/system/bin/settings', 'put', 'global', 'settings_enable_monitor_phantom_procs', 'false'],
    )
    for args in commands:
        try:
            root_command(args, check=False, timeout=5)
        except Exception:
            pass


def build_roblox_uri(place_id='', job_id=''):
    place_id = (place_id or '').strip()
    job_id = (job_id or '').strip()
    if not place_id or not re.fullmatch(r'\d{1,20}', place_id):
        return ''
    if job_id and re.fullmatch(r'[A-Za-z0-9-]{1,64}', job_id):
        return f'roblox://placeId={place_id}&gameInstanceId={job_id}'
    return f'roblox://experiences/start?placeId={place_id}'


def parse_tasks(text):
    """Extract active Task IDs from live ActivityRecord Hist entries only."""
    result = {}
    for line in (text or '').splitlines():
        if not re.search(r'\bHist\s+#\d+:\s+ActivityRecord\{', line):
            continue
        match = re.search(rf'\bu0\s+({PACKAGE_PATTERN})/[^\s}}]+\s+t(\d+)\b', line)
        if match:
            result.setdefault(match[1], int(match[2]))
    return result


def task_bounds(text, task):
    chunks = re.split(r'(?m)(?=^[ \t]*\*[ \t]*(?:Task|TaskRecord)\{)', text or '')
    for chunk in chunks:
        first = chunk.splitlines()[0] if chunk.splitlines() else ''
        if not re.search(r'#' + str(task) + r'\b', first):
            continue
        header = chunk.split('Hist #', 1)[0]
        bounds = re.search(r'(?:mBounds|bounds)=Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)', header)
        if not bounds:
            bounds = re.search(r'(?:mBounds|bounds)=\[(\d+),\s*(\d+)\]\[(\d+),\s*(\d+)\]', header)
        mode = 'freeform' in header or bool(re.search(r'(?:windowingMode|mode)=5\b', header))
        return (tuple(map(int, bounds.groups())) if bounds else None), mode
    return None, False


def bounds_match(actual, target, tolerance=6):
    if actual is None or target is None or len(actual) != 4 or len(target) != 4:
        return False
    return all(abs(a - b) <= tolerance for a, b in zip(actual, target))


def grid_bounds(width, height, rows, cols, gap, top, bottom):
    if not (1 <= rows <= 6 and 1 <= cols <= 6 and 0 <= gap <= 40 and 0 <= top <= 250 and 0 <= bottom <= 250):
        raise AgentError('Thông số lưới không hợp lệ.')
    available_w = width - gap * (cols + 1)
    available_h = height - top - bottom - gap * (rows + 1)
    if available_w // cols < 160 or available_h // rows < 120:
        raise AgentError('Ô lưới quá nhỏ. Giảm hàng/cột hoặc khoảng cách.')
    cells = []
    for row in range(rows):
        for col in range(cols):
            left = gap + col * gap + available_w * col // cols
            right = gap + col * gap + available_w * (col + 1) // cols
            upper = top + gap + row * gap + available_h * row // rows
            lower = top + gap + row * gap + available_h * (row + 1) // rows
            cells.append((left, upper, right, lower))
    return cells


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
    """Require all 4 independent checks to confirm closed; unreadable dumps never trigger crash."""
    if any(item is True for item in checks):
        return False, True
    if any(item is None for item in checks):
        return False, False
    return True, True


def inspect_activity_auth_or_anr(tasks_text, windows_text, package):
    """Fast 30ms check using ActivityRecord and WindowManager before needing uiautomator."""
    if windows_text:
        anr_re = re.compile(rf'Application Not Responding:\s*{re.escape(package)}\b', re.IGNORECASE)
        if anr_re.search(windows_text):
            return 'disconnected'
    if tasks_text:
        rec_re = re.compile(rf'\bHist\s+#0:\s+ActivityRecord\{{[^\n]*\bu0\s+{re.escape(package)}/([^\s}}]+)')
        match = rec_re.search(tasks_text)
        if match:
            activity = match.group(1).lower()
            if any(tok in activity for tok in ('arkose', 'funcaptcha', 'captcha', 'twostep', 'challenge')):
                return 'auth_required'
    return 'unknown'


def _parse_bounds(text):
    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', (text or '').strip())
    return tuple(map(int, match.groups())) if match else None


def _bounds_overlap(inner, outer):
    if not inner or not outer:
        return False
    cx, cy = (inner[0] + inner[2]) // 2, (inner[1] + inner[3]) // 2
    return outer[0] <= cx <= outer[2] and outer[1] <= cy <= outer[3]


def inspect_ui_state(ui_xml, package, bounds=None):
    """Classify visible UI state for one package; never attempts CAPTCHA bypass."""
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

    sys_joined = ' | '.join(system_texts)
    if any(tok in sys_joined for tok in ("isn't responding", 'không phản hồi')) and any(tok in sys_joined for tok in ('close app', 'đóng ứng dụng')):
        return 'disconnected'

    if not pkg_texts:
        return 'unknown'
    joined = ' | '.join(pkg_texts)

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
    if ('reconnect' in joined and 'leave' in joined) or ('kết nối lại' in joined and 'rời' in joined):
        return 'disconnected'

    teleport_markers = (
        'teleporting', 'joining server', 'waiting for an available server',
        'đang dịch chuyển', 'đang tham gia',
    )
    if any(marker in joined for marker in teleport_markers):
        return 'teleporting'

    return 'active'


def parse_proc_net_udp(text, uid=None):
    """Extract non-loopback remote UDP peers filtered by Linux UID (decimal or 8-hex)."""
    if not text:
        return None
    lines = text.splitlines()
    if len(lines) < 2:
        return set()
    uid_tokens = None
    if uid is not None:
        uid_int = int(uid)
        uid_tokens = {str(uid_int), f'{uid_int:08x}', f'{uid_int:x}'}
    peers = set()
    for line in lines[1:]:
        cols = line.split()
        if len(cols) < 8:
            continue
        rem = cols[2]
        sock_uid = cols[7].lower()
        if uid_tokens is not None and sock_uid not in uid_tokens:
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
        peers.add((addr_hex.upper(), port))
    return peers


class Android:
    def __init__(self):
        self._uid_cache = {}

    def packages(self):
        code, out = root_command(['/system/bin/pm', 'list', 'packages', '--user', '0'], check=False, timeout=10)
        if code != 0 or not out:
            _, out = root_command(['/system/bin/pm', 'list', 'packages'], check=False, timeout=10)
        return sorted({line[8:] for line in out.splitlines() if line.startswith('package:') and PACKAGE.fullmatch(line[8:])})

    def running(self, package):
        code, output = root_command(['/system/bin/pidof', package], check=False, timeout=5)
        if code not in (0, 1):
            raise AgentError('Không kiểm tra được tiến trình Android.')
        return code == 0 and bool(re.fullmatch(r'[\d\s]+', output))

    def tasks(self):
        return root_command(['/system/bin/dumpsys', 'activity', 'activities'], check=False, timeout=10)[1]

    def windows(self):
        return root_command(['/system/bin/dumpsys', 'window', 'windows'], check=False, timeout=10)[1]

    def surfaces(self):
        code, out = root_command(['/system/bin/dumpsys', 'SurfaceFlinger', '--list'], check=False, timeout=6)
        if code == 0 and len([ln for ln in out.splitlines() if ln.strip()]) >= 2:
            return out
        return root_command(['/system/bin/dumpsys', 'SurfaceFlinger'], check=False, timeout=8)[1]

    def ui_tree(self):
        dump_path = '/data/local/tmp/noka_ui_dump.xml'
        try:
            code, out = root_command(['/system/bin/uiautomator', 'dump', '--compressed', dump_path], check=False, timeout=4)
        except subprocess.TimeoutExpired:
            return ''
        if code != 0 or 'ERROR:' in out:
            return ''
        _, xml_content = root_command(['/system/bin/cat', dump_path], check=False, timeout=4)
        return xml_content if '<hierarchy' in xml_content else ''

    def package_uid(self, package):
        if package in self._uid_cache:
            return self._uid_cache[package]
        data_dir = f'/data/data/{package}'
        if os.path.exists(data_dir):
            uid = os.stat(data_dir).st_uid
            self._uid_cache[package] = uid
            return uid
        _, out = root_command(['/system/bin/stat', '-c', '%u', data_dir], check=False, timeout=5)
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
                _, content = root_command(['/system/bin/cat', proc_file], check=False, timeout=5)
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
        _, display = root_command(['/system/bin/dumpsys', 'display'])
        match = re.search(r'mDisplayInfo=DisplayInfo\{[^\n]*?\breal\s+(\d+)\s*x\s*(\d+)', display)
        return tuple(map(int, match.groups())) if match else (width, height)

    def resolve(self, package):
        _, output = root_command([
            '/system/bin/cmd', 'package', 'resolve-activity', '--brief', '--user', '0',
            '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-p', package,
        ])
        for line in reversed(output.splitlines()):
            if re.fullmatch(re.escape(package) + r'/[A-Za-z0-9_.$]+', line.strip()):
                return line.strip()
        raise AgentError('Không tìm thấy màn hình mở ứng dụng: ' + package)

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
        root_command(args, timeout=20)

    def close(self, package):
        root_command(['/system/bin/am', 'force-stop', '--user', '0', package])

    def freeform(self):
        try:
            _, features = root_command(['/system/bin/pm', 'list', 'features'], check=False, timeout=8)
            _, setting = root_command(['/system/bin/settings', 'get', 'global', 'enable_freeform_support'], check=False, timeout=5)
            _, help_text = root_command(['/system/bin/am', 'help'], check=False, timeout=5)
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
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            tasks_text = self.tasks()
            task = parse_tasks(tasks_text).get(package)
            if task is not None:
                break
            time.sleep(0.5)
        else:
            raise AgentError('Chưa tìm thấy cửa sổ đang hoạt động của ' + package)

        actual, is_freeform = task_bounds(tasks_text, task)
        if not launch and is_freeform and bounds_match(actual, bounds):
            return task

        root_command(['/system/bin/am', 'task', 'resize', str(task), *map(str, bounds)], check=False)
        time.sleep(0.35)
        actual, is_freeform = task_bounds(self.tasks(), task)
        if not is_freeform or actual is None:
            comma_bounds = ','.join(map(str, bounds))
            root_command(['/system/bin/am', 'task', 'resize', str(task), comma_bounds], check=False)
        return task
