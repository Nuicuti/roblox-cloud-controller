#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

echo "=========================================="
echo " ĐANG CÀI ĐẶT NOKA CONTROLLER AGENT"
echo "=========================================="

if [ "$(id -u)" = "0" ]; then
    echo "[!] Chú ý: Hãy chạy bộ cài bằng user Termux thông thường (không gõ su trước)."
    exit 1
fi

# 1. Cấp quyền storage nếu cần
if [ ! -d "/storage/emulated/0/Download" ]; then
    termux-setup-storage || true
fi

# 2. Cài đặt các gói phụ thuộc
echo "[1/4] Cập nhật gói và cài đặt Python, Cryptography..."
pkg update -y || true
pkg install -y python python-cryptography curl git

# 3. Tạo thư mục làm việc
echo "[2/4] Tạo thư mục làm việc..."
TARGET_DIR="$HOME/noka-controller"
DATA_DIR="$HOME/.noka-controller"
mkdir -p "$TARGET_DIR" "$DATA_DIR"
chmod 700 "$TARGET_DIR" "$DATA_DIR"

# 4. Tải mã nguồn agent mới nhất từ GitHub
echo "[3/4] Tải mã nguồn agent mới nhất..."
REPO_RAW="https://raw.githubusercontent.com/Nuicuti/roblox-cloud-controller/main/v2/agent"
curl -sL "$REPO_RAW/noka_agent.py" -o "$TARGET_DIR/noka_agent.py"
curl -sL "$REPO_RAW/noka_login.py" -o "$TARGET_DIR/noka_login.py"
curl -sL "$REPO_RAW/cookie_helper.py" -o "$TARGET_DIR/cookie_helper.py"
curl -sL "$REPO_RAW/start-agent.sh" -o "$TARGET_DIR/start-agent.sh"
chmod +x "$TARGET_DIR"/*.py "$TARGET_DIR"/*.sh

# 5. Chạy setup tự động với thông tin project
echo "[4/4] Khởi tạo định danh thiết bị..."
python "$TARGET_DIR/noka_agent.py" setup \
    --api-key "AIzaSyB1wXy5NNVPI6MT-Zp9fMTwchZdNFYOkic" \
    --database "https://roblox-cloud-farm-default-rtdb.firebaseio.com"

echo "=========================================="
echo " CÁCH CHẠY AGENT CHẠY NGẦM:"
echo " nohup bash ~/noka-controller/start-agent.sh > ~/.noka-controller/console.log 2>&1 &"
echo "=========================================="
