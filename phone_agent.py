#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Agent chạy trên Android (Termux Root) - Kết nối đồng bộ với Web Controller trên Vercel
"""

import os
import sys
import time
import json
import base64
import subprocess
import threading
from datetime import datetime
import urllib.request
import urllib.error

CONFIG_PATH = "/sdcard/Download/agent_config.json"
SCREEN_RAW = "/sdcard/Download/screen_temp.png"

DEFAULT_CONFIG = {
    # Dán URL Firebase Realtime Database của bạn vào đây
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
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(DEFAULT_CONFIG, f, indent=4)
        log(f"Đã tạo file cấu hình mẫu tại: {CONFIG_PATH}")
        return DEFAULT_CONFIG
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        log(f"Lỗi đọc file cấu hình: {e}")
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

# ==================== TÁC VỤ ROBLOX ====================

def is_running(pkg):
    pid = run_cmd(f"pidof {pkg}")
    return bool(pid)

def kill_app(pkg):
    log(f"Dừng ứng dụng: {pkg}")
    run_cmd(f"am force-stop {pkg}")
    time.sleep(1)

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
        time.sleep(1)
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
    """Chụp màn hình máy ảo và upload lên Firebase dưới dạng Base64"""
    log("Đang chụp ảnh màn hình...")
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
        urllib.request.urlopen(req, timeout=15)
        log("Đã tải ảnh chụp màn hình lên Firebase!")
    except Exception as e:
        log(f"Lỗi upload ảnh chụp màn hình: {e}")

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

def main():
    print("=" * 55)
    print("      ROBLOX AGENT - VERCEL CLOUD CONNECTOR        ")
    print("=" * 55)

    config = load_config()
    fb_url = config.get("firebase_url", "").rstrip("/")
    if not fb_url or "your-project" in fb_url:
        log("[!] Chưa điền Firebase URL vào agent_config.json!")
        return

    place_id = config.get("place_id", "1730877806")
    job_id = config.get("job_id", "")
    tabs = config.get("tabs", [])

    log(f"Kết nối Firebase: {fb_url}")
    last_command_time = int(time.time() * 1000)

    last_status_update = 0

    while True:
        try:
            now = time.time()

            # 1. Cập nhật Status lên Firebase mỗi 6 giây
            if now - last_status_update >= 6:
                tabs_status = {}
                for tab in tabs:
                    pkg = tab.get("package")
                    tabs_status[pkg] = {
                        "name": tab.get("name"),
                        "is_running": is_running(pkg),
                        "place_id": place_id
                    }
                
                status_payload = {
                    "last_seen": datetime.now().strftime("%H:%M:%S"),
                    "last_seen_epoch": int(now),
                    "tabs": tabs_status
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
                    log(f"-> Nhận lệnh từ Web: [{action}] {target_pkg or ''}")

                    if action == "rejoin":
                        kill_app(target_pkg)
                        launch_game(target_pkg, place_id, job_id)
                    elif action == "rejoin_all":
                        for t in tabs:
                            p = t.get("package")
                            kill_app(p)
                            launch_game(p, place_id, job_id)
                            time.sleep(2)
                    elif action == "tile":
                        arrange_2x2(tabs)
                    elif action == "screenshot":
                        threading.Thread(target=take_and_upload_screenshot, args=(fb_url,)).start()
                    elif action == "stop_all":
                        for t in tabs:
                            kill_app(t.get("package"))

            time.sleep(2)

        except KeyboardInterrupt:
            log("Dừng Agent.")
            break
        except Exception as e:
            time.sleep(3)

if __name__ == "__main__":
    main()
