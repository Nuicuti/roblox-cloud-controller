#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Agent chạy trên Android (Termux Root)
- Tự động quét và nhận diện các bản Clone Noka cài trên máy
- Nạp Cookie chuẩn SQLite (Checkpoint & dọn dẹp WAL để app nhận phiên 100%)
- Tự động vượt màn hình chào NOKA (bấm Đăng nhập / điền tk & mk) để vào thẳng game
- Căn 4 tab lưới 2x2 chuẩn theo yêu cầu:
    + Top-Left:     Noka 3 (free.nokaC)
    + Top-Right:    Noka 4 (free.nokaD)
    + Bottom-Left:  Noka 1 (free.nokaA)
    + Bottom-Right: Noka 2 (free.nokaB)
- Kết nối đồng bộ hai chiều với Web Controller trên Vercel qua Firebase
"""

import os
import sys
import time
import json
import base64
import sqlite3
import subprocess
import threading
import re
from datetime import datetime
import urllib.request
import urllib.error

# Toàn bộ file lưu trữ tại /storage/emulated/0/
BASE_DIR = "/storage/emulated/0"
CONFIG_PATH = f"{BASE_DIR}/agent_config.json"
ACCOUNTS_FILE = f"{BASE_DIR}/accounts.txt"
SCREEN_RAW = f"{BASE_DIR}/screen_temp.png"

DEFAULT_CONFIG = {
    "firebase_url": "https://roblox-cloud-farm-default-rtdb.firebaseio.com",
    "place_id": "1730877806",
    "job_id": "",
    "tabs": [
        {"name": "Noka 1", "package": "free.nokaA"},
        {"name": "Noka 2", "package": "free.nokaB"},
        {"name": "Noka 3", "package": "free.nokaC"},
        {"name": "Noka 4", "package": "free.nokaD"}
    ]
}

def log(msg):
    now = datetime.now().strftime("%H:%M:%S")
    entry = f"[{now}] {msg}"
    print(entry)
    sys.stdout.flush()

def run_cmd(cmd):
    """Chạy lệnh shell bằng quyền root"""
    try:
        res = subprocess.run(
            ["su", "-c", cmd],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=10
        )
        return res.stdout.strip()
    except Exception:
        return ""

def auto_detect_noka_packages():
    """Tự động dò tìm tất cả các package Noka / Roblox clone đang cài trên máy"""
    out = run_cmd("pm list packages | grep -iE 'noka|roblox'")
    pkgs = []
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("package:"):
            p = line.replace("package:", "").strip()
            if p and p not in pkgs:
                pkgs.append(p)
    return pkgs

def load_config():
    cfg = DEFAULT_CONFIG
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception:
            cfg = DEFAULT_CONFIG

    # Tự động đồng bộ với các package clone thực tế trên máy
    installed = auto_detect_noka_packages()
    if installed:
        log(f"[*] Tìm thấy các bản clone trên máy: {installed}")
        # Cập nhật danh sách tabs theo package thực tế
        new_tabs = []
        for i, p in enumerate(installed[:4]):
            new_tabs.append({"name": f"Noka {i+1}", "package": p})
        if new_tabs:
            cfg["tabs"] = new_tabs

    return cfg

# ==================== KIỂM TRA & XÁC MINH COOKIE ====================

def verify_cookie_with_roblox(cookie):
    """Xác minh cookie sống với Roblox API"""
    try:
        url = "https://users.roblox.com/v1/users/authenticated"
        cookie_header = f".ROBLOSECURITY={cookie}" if not cookie.startswith(".ROBLOSECURITY=") else cookie
        req = urllib.request.Request(url, headers={
            "Cookie": cookie_header,
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        })
        with urllib.request.urlopen(req, timeout=6) as r:
            if r.status == 200:
                data = json.loads(r.read().decode())
                log(f"[✓] Roblox xác thực: {data.get('name')} (Display: {data.get('displayName')}, ID: {data.get('id')})")
                return {
                    "valid": True,
                    "id": data.get("id"),
                    "name": data.get("name"),
                    "displayName": data.get("displayName")
                }
    except Exception as e:
        log(f"[!] Cookie không hợp lệ hoặc lỗi kết nối: {e}")
    return {"valid": False, "id": None, "name": None, "displayName": None}

def get_pkg_owner(pkg):
    data_dir = f"/data/data/{pkg}"
    if os.path.exists(data_dir):
        st = os.stat(data_dir)
        return st.st_uid, st.st_gid
    return 10000, 10000

def init_app_webview(pkg):
    cookie_path = f"/data/data/{pkg}/app_webview/Default/Cookies"
    if not os.path.exists(cookie_path):
        log(f"[*] Khởi tạo thư mục WebView cho {pkg}...")
        run_cmd(f"am start -n {pkg}/com.roblox.client.startup.LauncherAliasMain")
        time.sleep(3)
        run_cmd(f"am force-stop {pkg}")
        time.sleep(1)

def inject_cookie_to_pkg(pkg, cookie_val):
    """
    Nạp trực tiếp .ROBLOSECURITY vào Chromium SQLite Cookies
    Xử lý WAL Checkpoint & xóa -wal/-shm để app buộc phải nhận phiên đăng nhập mới
    """
    log(f"Đang nạp Cookie vào {pkg}...")
    # 1. Tắt hoàn toàn app trước khi sửa database
    run_cmd(f"am force-stop {pkg}")
    time.sleep(0.5)

    cookie_path = f"/data/data/{pkg}/app_webview/Default/Cookies"
    if not os.path.exists(cookie_path):
        cookie_path = f"/data/data/{pkg}/app_webview/Cookies"
        if not os.path.exists(cookie_path):
            init_app_webview(pkg)
            cookie_path = f"/data/data/{pkg}/app_webview/Default/Cookies"

    if not os.path.exists(cookie_path):
        log(f"[X] Không tìm thấy Cookies database của {pkg}!")
        return False, False

    try:
        uid, gid = get_pkg_owner(pkg)
        conn = sqlite3.connect(cookie_path)
        cur = conn.cursor()

        cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='cookies'")
        if not cur.fetchone():
            conn.close()
            return False, False

        # Xóa sạch cookie cũ của .roblox.com để tránh xung đột phiên cũ
        cur.execute("DELETE FROM cookies WHERE host_key LIKE '%.roblox.com%'")

        now_utc = int(time.time() * 1000000) + 11644473600000000

        cur.execute("PRAGMA table_info(cookies)")
        cols = [c[1] for c in cur.fetchall()]
        val_map = {
            'creation_utc': now_utc,
            'host_key': '.roblox.com',
            'top_frame_site_key': '',
            'name': '.ROBLOSECURITY',
            'value': cookie_val,
            'encrypted_value': b'',
            'path': '/',
            'expires_utc': now_utc + 31536000000000,
            'is_secure': 1,
            'is_httponly': 1,
            'last_access_utc': now_utc,
            'has_expires': 1,
            'is_persistent': 1,
            'priority': 1,
            'samesite': -1,
            'source_scheme': 2,
            'source_port': 443,
            'is_same_party': 0,
            'last_update_utc': now_utc
        }
        insert_cols = [c for c in cols if c in val_map]
        placeholders = ', '.join(['?'] * len(insert_cols))
        col_names = ', '.join(insert_cols)
        values = [val_map[c] for c in insert_cols]
        sql = f"INSERT INTO cookies ({col_names}) VALUES ({placeholders})"
        cur.execute(sql, values)

        conn.commit()

        # Checkpoint WAL sang file Cookies chính và chuyển sang DELETE mode
        try:
            cur.execute("PRAGMA wal_checkpoint(FULL);")
            cur.execute("PRAGMA journal_mode=DELETE;")
        except Exception:
            pass

        # Kiểm tra lần 2: Đọc lại SQLite xem đã có cookie chưa
        cur.execute("SELECT value FROM cookies WHERE host_key = '.roblox.com' AND name = '.ROBLOSECURITY'")
        readback_row = cur.fetchone()
        sqlite_verified = bool(readback_row and readback_row[0] == cookie_val)
        conn.close()

        # QUAN TRỌNG: Xóa sạch file WAL và SHM cũ để app không bị đọc dữ liệu đệm cũ
        for ext in ['-wal', '-shm', '-journal']:
            f = cookie_path + ext
            if os.path.exists(f):
                try: os.remove(f)
                except Exception: pass

        # Cấp quyền cho app đọc file SQLite
        if os.path.exists(cookie_path):
            os.chown(cookie_path, uid, gid)
            os.chmod(cookie_path, 0o660)

        # Xóa cache login cũ của Roblox để app làm mới session
        run_cmd(f"rm -rf /data/data/{pkg}/shared_prefs/RBX_LOGIN*")

        log(f"[✓] Đã nạp Cookie vào {pkg}! (Xác minh SQLite: {'KHỚP 100%' if sqlite_verified else 'Lỗi'})")
        return True, sqlite_verified
    except Exception as e:
        log(f"[X] Lỗi khi inject cookie vào {pkg}: {e}")
        return False, False

def parse_accounts(text_or_file=""):
    lines = []
    if text_or_file and ":" in text_or_file:
        lines = text_or_file.strip().splitlines()
    elif os.path.exists(ACCOUNTS_FILE):
        try:
            with open(ACCOUNTS_FILE, "r", encoding="utf-8", errors="ignore") as f:
                lines = [l.strip() for l in f if l.strip()]
        except Exception:
            pass

    accounts = []
    for line in lines:
        if not line or not ":" in line:
            continue
        parts = line.split(":")
        username = parts[0].strip() if len(parts) >= 1 else "Unknown"
        password = parts[1].strip() if len(parts) >= 2 else ""
        cookie = ":".join(parts[2:]).strip() if len(parts) >= 3 else parts[-1].strip()
        if "_|WARNING" in cookie:
            cookie = cookie[cookie.index("_|WARNING"):]
        cookie = cookie.strip('\"\'')
        accounts.append({
            "user": username,
            "password": password,
            "cookie": cookie,
            "raw": line
        })
    return accounts

# ==================== TÁC VỤ ROBLOX & TỰ ĐỘNG ĐĂNG NHẬP ====================

def is_running(pkg):
    pid = run_cmd(f"pidof {pkg}")
    return bool(pid)

def kill_app(pkg):
    run_cmd(f"am force-stop {pkg}")

def launch_game(pkg, place_id, job_id=""):
    log(f"Khởi chạy {pkg} vào Place {place_id}...")
    if job_id:
        uri = f"roblox://placeId={place_id}&gameInstanceId={job_id}"
    else:
        uri = f"roblox://experiences/start?placeId={place_id}"

    # Khởi chạy bằng intent VIEW deep link
    run_cmd(f"am start -a android.intent.action.VIEW -d \"{uri}\" -p {pkg}")

def find_and_tap_text(text_patterns):
    """Tìm toạ độ text/button trên màn hình qua uiautomator dump và tap vào giữa"""
    try:
        run_cmd("uiautomator dump /data/local/tmp/ui_dump.xml")
        xml_content = run_cmd("cat /data/local/tmp/ui_dump.xml")
        if not xml_content:
            return False
        for pattern in text_patterns:
            regex = rf'text="([^"]*{pattern}[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
            matches = re.findall(regex, xml_content, re.IGNORECASE)
            if not matches:
                regex_desc = rf'content-desc="([^"]*{pattern}[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
                matches = re.findall(regex_desc, xml_content, re.IGNORECASE)
            
            if matches:
                m = matches[0]
                x1, y1, x2, y2 = int(m[1]), int(m[2]), int(m[3]), int(m[4])
                cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
                log(f"[UI] Tìm thấy '{pattern}' tại ({cx}, {cy}). Đang tap...")
                run_cmd(f"input tap {cx} {cy}")
                return True
    except Exception as e:
        log(f"[UI] Lỗi parse XML uiautomator: {e}")
    return False

def fill_login_form(user, password):
    """Nếu hiện ô nhập tài khoản và mật khẩu, tự động gõ tk & mk và bấm Đăng nhập"""
    try:
        run_cmd("uiautomator dump /data/local/tmp/ui_dump.xml")
        xml = run_cmd("cat /data/local/tmp/ui_dump.xml")
        if not xml:
            return False
        
        edit_matches = re.findall(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if edit_matches:
            log(f"[UI] Phát hiện {len(edit_matches)} ô nhập liệu (Username/Password)...")
            # Ô 1: Username
            x1, y1, x2, y2 = map(int, edit_matches[0])
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
            run_cmd(f"input tap {cx} {cy}")
            time.sleep(0.4)
            # Xoá text cũ nếu có
            for _ in range(25): run_cmd("input keyevent 67")
            run_cmd(f"input text \"{user}\"")
            time.sleep(0.4)
            
            # Ô 2: Password
            if len(edit_matches) >= 2 and password:
                px1, py1, px2, py2 = map(int, edit_matches[1])
                pcx, pcy = (px1 + px2) // 2, (py1 + py2) // 2
                run_cmd(f"input tap {pcx} {pcy}")
                time.sleep(0.4)
                for _ in range(25): run_cmd("input keyevent 67")
                run_cmd(f"input text \"{password}\"")
                time.sleep(0.4)
            
            # Ẩn bàn phím ảo
            run_cmd("input keyevent 111")
            time.sleep(0.4)
            
            # Bấm nút Đăng nhập bên trong form
            find_and_tap_text(["Đăng nhập", "Log In"])
            log(f"[UI] Đã điền xong và bấm Đăng nhập cho {user}!")
            time.sleep(4)
            return True
    except Exception as e:
        log(f"[UI] Lỗi điền form: {e}")
    return False

def tap_login_button(pkg, slot_name, w=1280, h=720):
    """Bấm nút Đăng nhập của bản NOKA tương ứng"""
    # 1. Thử tự động nhận diện bằng UI Automator
    if find_and_tap_text(["Đăng nhập", "Log In"]):
        return True
    
    # 2. Toạ độ dự phòng đo trực tiếp từ layout màn hình
    coords = {
        "top_left": (305, 205),
        "top_right": (917, 205),
        "bottom_left": (326, 594),
        "bottom_right": (917, 601)
    }
    pt = coords.get(slot_name, (w // 2, int(h * 0.8)))
    log(f"[Fallback] Tap nút Đăng nhập tại {pt}...")
    run_cmd(f"input tap {pt[0]} {pt[1]}")
    return True

def arrange_2x2(tabs):
    """
    Căn 4 tab chuẩn theo ảnh người dùng yêu cầu:
    - Top-Left:     Noka 3 (free.nokaC) -> Slot 0
    - Top-Right:    Noka 4 (free.nokaD) -> Slot 1
    - Bottom-Left:  Noka 1 (free.nokaA) -> Slot 2
    - Bottom-Right: Noka 2 (free.nokaB) -> Slot 3
    """
    log("Thực hiện căn 4 tab lưới 2x2...")
    out = run_cmd("wm size")
    match = re.findall(r"size:\s*(\d+)x(\d+)", out)
    w, h = map(int, match[-1]) if match else (1280, 720)
    hw, hh = w // 2, h // 2

    # Toạ độ 4 góc màn hình
    quads = {
        "top_left": (0, 0, hw, hh),
        "top_right": (hw, 0, w, hh),
        "bottom_left": (0, hh, hw, h),
        "bottom_right": (hw, hh, w, h)
    }

    # Phân loại package vào đúng vị trí ảnh mẫu
    assigned = {}
    for i, tab in enumerate(tabs[:4]):
        pkg = tab.get("package", "")
        pkg_lower = pkg.lower()
        name_lower = tab.get("name", "").lower()
        if "nokac" in pkg_lower or "3" in name_lower:
            assigned["top_left"] = pkg
        elif "nokad" in pkg_lower or "4" in name_lower:
            assigned["top_right"] = pkg
        elif "nokaa" in pkg_lower or "1" in name_lower:
            assigned["bottom_left"] = pkg
        elif "nokab" in pkg_lower or "2" in name_lower:
            assigned["bottom_right"] = pkg

    # Fallback nếu tên không theo chuẩn
    positions = ["top_left", "top_right", "bottom_left", "bottom_right"]
    used_pkgs = set(assigned.values())
    for pos in positions:
        if pos not in assigned:
            for tab in tabs[:4]:
                p = tab.get("package")
                if p not in used_pkgs:
                    assigned[pos] = p
                    used_pkgs.add(p)
                    break

    # Thứ tự resize: Hàng trên trước, hàng dưới sau để hàng dưới đè lên hàng trên chuẩn như ảnh
    ordered_positions = ["top_left", "top_right", "bottom_left", "bottom_right"]
    for pos in ordered_positions:
        pkg = assigned.get(pos)
        if not pkg:
            continue
        l, t, r, b = quads[pos]
        run_cmd(f"am start --windowingMode 5 -p {pkg}")
        time.sleep(0.4)
        tasks = run_cmd("dumpsys activity tasks")
        task_id = None
        for line in tasks.splitlines():
            if pkg in line and "#" in line:
                m = re.search(r"#(\d+)", line)
                if m:
                    task_id = m.group(1)
                    break
        if task_id:
            run_cmd(f"am task resize {task_id} {l} {t} {r} {b}")
            run_cmd(f"am task resize {task_id} {l},{t},{r},{b}")
    log("Đã căn xong 2x2 chuẩn vị trí!")

def take_and_upload_screenshot(fb_url):
    run_cmd(f"screencap -p {SCREEN_RAW}")
    if not os.path.exists(SCREEN_RAW):
        return

    try:
        with open(SCREEN_RAW, "rb") as f:
            b64_data = base64.b64encode(f.read()).decode("utf-8")
        
        payload = {
            "image_base64": f"data:image/png;base64,{b64_data}",
            "updated_at": datetime.now().strftime("%H:%M:%S")
        }
        
        req = urllib.request.Request(
            f"{fb_url}/screenshot.json",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="PUT"
        )
        urllib.request.urlopen(req, timeout=10)
        log("Đã chụp và tải màn hình lên Firebase!")
    except Exception as e:
        log(f"Lỗi upload screenshot: {e}")

# ==================== ĐỒNG BỘ FIREBASE ====================

def http_get(url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.loads(r.read().decode())
    except Exception:
        return None

def http_put(url, data):
    try:
        req = urllib.request.Request(
            url,
            data=json.dumps(data).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="PUT"
        )
        with urllib.request.urlopen(req, timeout=5) as r:
            return True
    except Exception:
        return False

# ==================== QUY TRÌNH NẠP & TIẾN ĐỘ ====================

def report_progress(fb_url, step, total, msg):
    http_put(f"{fb_url}/login_progress.json", {
        "step": step,
        "total": total,
        "msg": msg,
        "timestamp": int(time.time() * 1000)
    })

def process_login_all(acc_list, tabs, place_id, job_id, fb_url):
    results = []
    total = min(len(acc_list), len(tabs))
    log(f"--- BẮT ĐẦU NẠP VÀ KIỂM TRA LẦN LƯỢT {total} ACC ---")

    # 1. Căn trước 2x2 để các cửa sổ vào đúng vị trí
    arrange_2x2(tabs)
    time.sleep(1)

    for i in range(total):
        acc = acc_list[i]
        tab = tabs[i]
        tab_name = tab.get("name")
        pkg = tab.get("package")
        cookie = acc.get("cookie")
        user = acc.get("user")
        password = acc.get("password")

        report_progress(fb_url, i + 1, total, f"Đang nạp {tab_name} ({user})...")
        log(f"\n[+] Xử lý {tab_name} ({pkg}) cho user: {user}...")

        roblox_info = verify_cookie_with_roblox(cookie)
        inject_ok = False
        sqlite_verified = False

        if roblox_info["valid"]:
            # Bước 1: Nạp cookie vào SQLite WebView
            inject_ok, sqlite_verified = inject_cookie_to_pkg(pkg, cookie)
            
            # Bước 2: Bật app
            log(f"[*] Mở {pkg}...")
            run_cmd(f"am start --windowingMode 5 -n {pkg}/com.roblox.client.startup.LauncherAliasMain")
            time.sleep(3)

            # Xác định slot vị trí của tab
            pkg_lower = pkg.lower()
            name_lower = tab_name.lower()
            slot = "bottom_left"
            if "nokac" in pkg_lower or "3" in name_lower: slot = "top_left"
            elif "nokad" in pkg_lower or "4" in name_lower: slot = "top_right"
            elif "nokab" in pkg_lower or "2" in name_lower: slot = "bottom_right"

            # Bước 3: Bấm nút Đăng nhập trên màn hình chào
            tap_login_button(pkg, slot)
            time.sleep(2)

            # Bước 4: Tự động điền tk/mk nếu hiện form
            fill_login_form(user, password)
            time.sleep(2)

            # Bước 5: Khởi chạy thẳng vào game Place 1730877806
            launch_game(pkg, place_id, job_id)
            time.sleep(1.5)
        else:
            log(f"[X] Cookie của {user} không hợp lệ!")

        status = "Thành công" if (roblox_info["valid"] and inject_ok) else "Thất bại"
        results.append({
            "tab": tab_name,
            "package": pkg,
            "input_user": user,
            "roblox_name": roblox_info.get("name") or user,
            "display_name": roblox_info.get("displayName") or "",
            "roblox_id": roblox_info.get("id"),
            "cookie_valid": roblox_info["valid"],
            "sqlite_ok": inject_ok,
            "sqlite_verified": sqlite_verified,
            "game_started": is_running(pkg),
            "status": status
        })

    # Căn lại 2x2 sau khi khởi chạy hoàn tất
    report_progress(fb_url, total, total, "Đang căn lại cửa sổ 2x2...")
    time.sleep(1)
    arrange_2x2(tabs)

    # Chụp ảnh màn hình mới nhất tải lên Firebase
    take_and_upload_screenshot(fb_url)

    report_payload = {
        "timestamp": int(time.time() * 1000),
        "updated_at": datetime.now().strftime("%H:%M:%S"),
        "success_count": sum(1 for r in results if r["status"] == "Thành công"),
        "total_count": len(results),
        "results": results
    }
    http_put(f"{fb_url}/login_result.json", report_payload)
    report_progress(fb_url, total, total, "Hoàn tất!")
    log(f"\n--- ĐÃ HOÀN TẤT: {report_payload['success_count']}/{report_payload['total_count']} THÀNH CÔNG ---")

def process_login_tab(target_pkg, tabs, accounts, place_id, job_id, fb_url):
    tab_idx = 0
    tab_name = target_pkg
    for i, t in enumerate(tabs):
        if t.get("package") == target_pkg:
            tab_idx = i
            tab_name = t.get("name")
            break

    if tab_idx < len(accounts):
        acc = accounts[tab_idx]
        user = acc.get("user")
        password = acc.get("password")
        cookie = acc.get("cookie")
        report_progress(fb_url, 1, 1, f"Đang nạp riêng {tab_name} ({user})...")

        roblox_info = verify_cookie_with_roblox(cookie)
        inject_ok, sqlite_verified = False, False
        if roblox_info["valid"]:
            inject_ok, sqlite_verified = inject_cookie_to_pkg(target_pkg, cookie)
            run_cmd(f"am start --windowingMode 5 -n {target_pkg}/com.roblox.client.startup.LauncherAliasMain")
            time.sleep(3)

            pkg_lower = target_pkg.lower()
            name_lower = tab_name.lower()
            slot = "bottom_left"
            if "nokac" in pkg_lower or "3" in name_lower: slot = "top_left"
            elif "nokad" in pkg_lower or "4" in name_lower: slot = "top_right"
            elif "nokab" in pkg_lower or "2" in name_lower: slot = "bottom_right"

            tap_login_button(target_pkg, slot)
            time.sleep(2)
            fill_login_form(user, password)
            time.sleep(2)
            launch_game(target_pkg, place_id, job_id)
            time.sleep(1)

        arrange_2x2(tabs)
        take_and_upload_screenshot(fb_url)

        status = "Thành công" if (roblox_info["valid"] and inject_ok) else "Thất bại"
        report_payload = {
            "timestamp": int(time.time() * 1000),
            "updated_at": datetime.now().strftime("%H:%M:%S"),
            "success_count": 1 if status == "Thành công" else 0,
            "total_count": 1,
            "results": [{
                "tab": tab_name,
                "package": target_pkg,
                "input_user": user,
                "roblox_name": roblox_info.get("name") or user,
                "display_name": roblox_info.get("displayName") or "",
                "roblox_id": roblox_info.get("id"),
                "cookie_valid": roblox_info["valid"],
                "sqlite_ok": inject_ok,
                "sqlite_verified": sqlite_verified,
                "game_started": is_running(target_pkg),
                "status": status
            }]
        }
        http_put(f"{fb_url}/login_result.json", report_payload)
        report_progress(fb_url, 1, 1, "Hoàn tất!")

def main():
    print("=" * 55)
    print("      ROBLOX AGENT - VERCEL CLOUD CONNECTOR        ")
    print(f"       (Storage: {BASE_DIR}/)                      ")
    print("=" * 55)

    config = load_config()
    fb_url = config.get("firebase_url", "").rstrip("/")
    place_id = config.get("place_id", "1730877806")
    job_id = config.get("job_id", "")
    tabs = config.get("tabs", [])

    log(f"Kết nối Firebase: {fb_url}")
    last_command_time = int(time.time() * 1000)
    last_status_update = 0

    while True:
        try:
            now = time.time()
            accounts = parse_accounts()

            # 1. Cập nhật Status dạng Array (Tránh lỗi 400 Bad Request của Firebase do có dấu chấm)
            if now - last_status_update >= 5:
                tabs_list = []
                for i, tab in enumerate(tabs):
                    pkg = tab.get("package")
                    acc_user = accounts[i].get("user") if i < len(accounts) else ""
                    tabs_list.append({
                        "package": pkg,
                        "name": tab.get("name"),
                        "is_running": is_running(pkg),
                        "place_id": place_id,
                        "account_user": acc_user
                    })
                
                status_payload = {
                    "last_seen": datetime.now().strftime("%H:%M:%S"),
                    "last_seen_epoch": int(now),
                    "tabs": tabs_list
                }
                http_put(f"{fb_url}/status.json", status_payload)
                last_status_update = now

            # 2. Lắng nghe lệnh từ Web Vercel
            cmd_data = http_get(f"{fb_url}/command.json")
            if cmd_data and isinstance(cmd_data, dict):
                cmd_time = cmd_data.get("timestamp", 0)
                if cmd_time > last_command_time:
                    last_command_time = cmd_time
                    action = cmd_data.get("action")
                    target_pkg = cmd_data.get("package")
                    extra_text = cmd_data.get("accounts_text", "")
                    log(f"-> Nhận lệnh từ Web: [{action}] {target_pkg or ''}")

                    if action == "sync_accounts" and extra_text:
                        try:
                            with open(ACCOUNTS_FILE, "w", encoding="utf-8") as f:
                                f.write(extra_text)
                            log(f"Đã lưu danh sách accounts vào {ACCOUNTS_FILE}")
                        except Exception as e:
                            log(f"Lỗi ghi {ACCOUNTS_FILE}: {e}")

                    elif action == "login_all":
                        acc_list = parse_accounts(extra_text)
                        if not acc_list:
                            acc_list = accounts
                        threading.Thread(target=process_login_all, args=(acc_list, tabs, place_id, job_id, fb_url)).start()

                    elif action == "login_tab" and target_pkg:
                        threading.Thread(target=process_login_tab, args=(target_pkg, tabs, accounts, place_id, job_id, fb_url)).start()

                    elif action == "rejoin":
                        kill_app(target_pkg)
                        time.sleep(1)
                        launch_game(target_pkg, place_id, job_id)

                    elif action == "rejoin_all":
                        for t in tabs:
                            p = t.get("package")
                            kill_app(p)
                            time.sleep(0.5)
                            launch_game(p, place_id, job_id)
                            time.sleep(1.5)
                        time.sleep(2)
                        arrange_2x2(tabs)

                    elif action == "tile":
                        arrange_2x2(tabs)

                    elif action == "screenshot":
                        threading.Thread(target=take_and_upload_screenshot, args=(fb_url,)).start()

                    elif action == "stop_all":
                        for t in tabs:
                            kill_app(t.get("package"))

                    elif action == "exec_cmd":
                        shell_cmd = cmd_data.get("cmd", "")
                        if shell_cmd:
                            out = run_cmd(shell_cmd)
                            http_put(f"{fb_url}/cmd_output.json", {
                                "cmd": shell_cmd,
                                "output": out,
                                "timestamp": int(time.time() * 1000)
                            })

            time.sleep(1.5)

        except KeyboardInterrupt:
            log("Dừng Agent.")
            break
        except Exception as e:
            time.sleep(2)

if __name__ == "__main__":
    main()
