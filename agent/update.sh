#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

echo "=========================================="
echo " CẬP NHẬT AGENT LÊN BẢN MỚI NHẤT"
echo "=========================================="

TARGET_DIR="$HOME/noka-controller"
DATA_DIR="$HOME/.noka-controller"
mkdir -p "$TARGET_DIR" "$DATA_DIR"
chmod 700 "$TARGET_DIR" "$DATA_DIR"
cd "$TARGET_DIR"

pkill -9 -f 'noka_agent.py|agent.py' 2>/dev/null || true
rm -f "$DATA_DIR/agent.lock" "$DATA_DIR"/*.tmp* "$DATA_DIR"/noka_ck_*.json /data/local/tmp/ui_dump.xml /data/local/tmp/noka_ui_dump.xml 2>/dev/null || true

TS=$(date +%s)
BASE="https://raw.githubusercontent.com/Nuicuti/roblox-cloud-controller/main/agent"
for f in agent.py android_os.py cookie_store.py login_flow.py start.sh update.sh; do
    printf "  - Đang tải %s... " "$f"
    curl -sL "$BASE/$f?ts=$TS" -o "$TARGET_DIR/$f"
    echo "OK"
done
chmod 700 "$TARGET_DIR"/*.py "$TARGET_DIR"/*.sh

nohup bash "$TARGET_DIR/start.sh" > "$DATA_DIR/console.log" 2>&1 &
sleep 2

if pgrep -f 'agent.py' >/dev/null 2>&1; then
    echo "CẬP NHẬT THÀNH CÔNG (PID: $(pgrep -f 'agent.py' | head -n 1))."
else
    echo "[!] Có lỗi khi khởi động. Xem log: cat ~/.noka-controller/console.log"
fi
