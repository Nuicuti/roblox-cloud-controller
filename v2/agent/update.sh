#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

echo "=========================================="
echo " ĐANG CẬP NHẬT NOKA AGENT LÊN BẢN MỚI NHẤT"
echo "=========================================="

TARGET_DIR="$HOME/noka-controller"
DATA_DIR="$HOME/.noka-controller"
mkdir -p "$TARGET_DIR" "$DATA_DIR"
cd "$TARGET_DIR"

# 1. Dừng tiến trình agent cũ và xóa lock
echo "[1/4] Dừng agent cũ..."
pkill -9 -f noka_agent.py || true
rm -f "$DATA_DIR/agent.lock" "$DATA_DIR"/*.tmp* "$DATA_DIR"/noka_ck_*.json 2>/dev/null || true

# 2. Tải toàn bộ file mã nguồn mới nhất từ GitHub (kèm cache-busting timestamp)
echo "[2/4] Tải mã nguồn mới nhất..."
TS=$(date +%s)
BASE="https://raw.githubusercontent.com/Nuicuti/roblox-cloud-controller/main/v2/agent"
for f in noka_agent.py noka_login.py cookie_helper.py start-agent.sh; do
    printf "  - Đang tải %s... " "$f"
    curl -sL "$BASE/$f?ts=$TS" -o "$TARGET_DIR/$f"
    echo "OK"
done
chmod +x "$TARGET_DIR"/*.py "$TARGET_DIR"/*.sh

# 3. Dọn dẹp cache UI dump cũ
echo "[3/4] Dọn dẹp dữ liệu tạm..."
rm -f /data/local/tmp/ui_dump.xml /data/local/tmp/noka_ck_*.json 2>/dev/null || true

# 4. Khởi chạy agent mới ở chế độ nền
echo "[4/4] Khởi động agent..."
nohup bash "$TARGET_DIR/start-agent.sh" > "$DATA_DIR/console.log" 2>&1 &
sleep 2

if pgrep -f noka_agent.py >/dev/null 2>&1; then
    echo "=========================================="
    echo " CẬP NHẬT THÀNH CÔNG! AGENT ĐANG CHẠY (PID: $(pgrep -f noka_agent.py | head -n 1))"
    echo " Kiểm tra dashboard: Máy sẽ Online lại ngay lập tức."
    echo "=========================================="
else
    echo "[!] Có lỗi khi khởi động. Xem log bằng: cat ~/.noka-controller/console.log"
fi
