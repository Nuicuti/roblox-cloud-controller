#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Agent chạy trên Android (Termux Root)
- Lưu trữ toàn bộ dữ liệu tại: /storage/emulated/0/
- Hỗ trợ Nạp Cookie (SQLite Injection) theo định dạng tk:mk:cookie
- Tự động kiểm tra xác minh 2 lần (Xác minh Roblox API + Kiểm tra SQLite readback)
- Báo cáo tiến độ thời gian thực (Realtime Progress) lên Firebase
"""

import os
import sys
import time
import json
import base64
import sqlite3
import subprocess
import threading
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
    print(f"[{now}] {msg}")

def load_config():
    if not os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(DEFAULT_CONFIG, f, indent=4)
        except Exception:
            pass
        return DEFAULT_CONFIG
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return DEFAULT_CONFIG

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

# ==================== KIỂM TRA & XÁC MINH COOKIE ====================

def verify_cookie_with_roblox(cookie):
    """Kiểm tra lần 1: Xác minh cookie có sống và lấy info từ Roblox API"""
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
    """Nạp trực tiếp .ROBLOSECURITY vào Chromium SQLite Cookies của app (CỰC NHANH)"""
    log(f"Đang nạp Cookie vào {pkg}...")
    run_cmd(f"am force-stop {pkg}")

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

        cur.execute("SELECT rowid FROM cookies WHERE host_key = '.roblox.com' AND name = '.ROBLOSECURITY'")
        row = cur.fetchone()
        now_utc = int(time.time() * 1000000) + 11644473600000000

        if row:
            cur.execute("UPDATE cookies SET value = ?, last_access_utc = ? WHERE rowid = ?", (cookie_val, now_utc, row[0]))
        else:
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

        # Kiểm tra lần 2: Đọc lại SQLite
        cur.execute("SELECT value FROM cookies WHERE host_key = '.roblox.com' AND name = '.ROBLOSECURITY'")
        readback_row = cur.fetchone()
        sqlite_verified = bool(readback_row and readback_row[0] == cookie_val)
        conn.close()

        # Cấp lại quyền cho app đọc file SQLite
        for ext in ['', '-journal', '-wal', '-shm']:
            f = cookie_path + ext
            if os.path.exists(f):
                os.chown(f, uid, gid)
                os.chmod(f, 0o660)

        log(f"[✓] Đã nạp Cookie vào {pkg}! (SQLite Readback: {'KHỚP 100%' if sqlite_verified else 'Lỗi'})")
        return True, sqlite_verified
    except Exception as e:
        log(f"[X] Lỗi khi inject cookie vào {pkg}: {e}")
        return False, False

def parse_accounts(text_or_file=""):
    """Phân tích danh sách tài khoản theo định dạng tk:mk:cookie"""
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
        cookie = ":".join(parts[2:]).strip() if len(parts) >= 3 else parts[-1].strip()
        if "_|WARNING" in cookie:
            cookie = cookie[cookie.index("_|WARNING"):]
        cookie = cookie.strip('\"\'')
        accounts.append({"user": username, "cookie": cookie, "raw": line})
    return accounts

# ==================== TÁC VỤ ROBLOX ====================

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
    run_cmd(f"am start -a android.intent.action.VIEW -d \"{uri}\" -p {pkg}")

def arrange_2x2(tabs):
    """Căn 4 tab thành lưới 2x2"""
    log("Thực hiện căn 4 tab lưới 2x2...")
    out = run_cmd("wm size")
    import re
    match = re.findall(r"size:\s*(\d+)x(\d+)", out)
    w, h = map(int, match[-1]) if match else (1080, 2400)
    hw, hh = w // 2, h // 2
    quads = [(0, 0, hw, hh), (hw, 0, w, hh), (0, hh, hw, h), (hw, hh, w, h)]

    for i, tab in enumerate(tabs[:4]):
        pkg = tab.get("package")
        run_cmd(f"am start --windowingMode 5 -p {pkg}")
        time.sleep(0.5)
        tasks = run_cmd("dumpsys activity tasks")
        task_id = None
        for line in tasks.splitlines():
            if pkg in line and "#" in line:
                m = re.search(r"#(\d+)", line)
                if m: task_id = m.group(1); break
        if task_id:
            l, t, r, b = quads[i]
            run_cmd(f"am task resize {task_id} {l} {t} {r} {b}")
            run_cmd(f"am task resize {task_id} {l},{t},{r},{b}")
    log("Đã căn xong 2x2!")

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

# ==================== QUY TRÌNH NẠP & BÁO CÁO TIẾN ĐỘ ====================

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

    for i in range(total):
        acc = acc_list[i]
        tab_name = tabs[i].get("name")
        pkg = tabs[i].get("package")
        cookie = acc.get("cookie")
        user = acc.get("user")

        report_progress(fb_url, i + 1, total, f"Đang nạp {tab_name} ({user})...")
        log(f"\n[+] Xử lý {tab_name} ({pkg}) cho user: {user}...")

        # 1. Xác thực cookie qua Roblox API
        roblox_info = verify_cookie_with_roblox(cookie)
        inject_ok = False
        sqlite_verified = False

        if roblox_info["valid"]:
            # 2. Nạp cookie vào SQLite
            inject_ok, sqlite_verified = inject_cookie_to_pkg(pkg, cookie)
            if inject_ok:
                launch_game(pkg, place_id, job_id)
                time.sleep(1)
        else:
            log(f"[X] Cookie của {user} không hợp lệ!")

        status = "Thành công" if (roblox_info["valid"] and inject_ok and sqlite_verified) else "Thất bại"
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

    # Căn lại 2x2 tự động sau khi nạp xong cả 4 tab
    report_progress(fb_url, total, total, "Đang căn lại cửa sổ 2x2...")
    time.sleep(1)
    arrange_2x2(tabs)

    # Đẩy báo cáo tổng kết
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
        cookie = acc.get("cookie")
        report_progress(fb_url, 1, 1, f"Đang nạp riêng {tab_name} ({user})...")

        roblox_info = verify_cookie_with_roblox(cookie)
        inject_ok, sqlite_verified = False, False
        if roblox_info["valid"]:
            inject_ok, sqlite_verified = inject_cookie_to_pkg(target_pkg, cookie)
            if inject_ok:
                launch_game(target_pkg, place_id, job_id)

        status = "Thành công" if (roblox_info["valid"] and inject_ok and sqlite_verified) else "Thất bại"
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

                    elif action == "tile":
                        arrange_2x2(tabs)

                    elif action == "screenshot":
                        threading.Thread(target=take_and_upload_screenshot, args=(fb_url,)).start()

                    elif action == "stop_all":
                        for t in tabs:
                            kill_app(t.get("package"))

            time.sleep(1.5)

        except KeyboardInterrupt:
            log("Dừng Agent.")
            break
        except Exception as e:
            time.sleep(2)

if __name__ == "__main__":
    main()
