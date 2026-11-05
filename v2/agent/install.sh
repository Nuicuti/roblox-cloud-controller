#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

echo "=========================================="
echo " ĐANG CÀI ĐẶT NOKA CONTROLLER AGENT"
echo "=========================================="

if [ "$(id -u)" = "0" ]; then
    echo "[!] Chú ý: Hãy chạy bộ cài bằng user Termux thông thường (không gõ su trước)."
    exit 1
fi

PREFIX="/data/data/com.termux/files/usr"

# 1. Tự động khắc phục lỗi Mirror 429 Too Many Requests (chuyển sang mirror chính thức ổn định)
echo "[1/5] Khắc phục và tối ưu Mirror Termux (tránh lỗi 429 Too Many Requests)..."
if [ -d "$PREFIX/etc/apt" ]; then
    find "$PREFIX/etc/apt" -type f \( -name "*.list" -o -name "sources.list" \) -exec sed -i 's|mirror.textcord.xyz|grimler.se|g' {} + 2>/dev/null || true
    find "$PREFIX/etc/apt" -type f \( -name "*.list" -o -name "sources.list" \) -exec sed -i 's|https://grimler.se/termux/termux-main|https://packages.termux.dev/apt/termux-main|g' {} + 2>/dev/null || true
fi
mkdir -p "$PREFIX/etc/apt"
echo "deb https://packages.termux.dev/apt/termux-main stable main" > "$PREFIX/etc/apt/sources.list"

# 2. Cấp quyền storage nếu cần
if [ ! -d "/storage/emulated/0/Download" ]; then
    termux-setup-storage || true
fi

# 3. Cập nhật gói và cài đặt các phụ thuộc
echo "[2/5] Cập nhật danh sách gói và cài đặt Python, Cryptography..."
apt update -y || {
    echo "[!] Mirror dev bận, chuyển sang mirror dự phòng grimler.se..."
    echo "deb https://grimler.se/termux/termux-main stable main" > "$PREFIX/etc/apt/sources.list"
    apt update -y
}
pkg install -y python python-cryptography curl git

# 4. Tạo thư mục làm việc
echo "[3/5] Tạo thư mục làm việc..."
TARGET_DIR="$HOME/noka-controller"
DATA_DIR="$HOME/.noka-controller"
mkdir -p "$TARGET_DIR" "$DATA_DIR"
chmod 700 "$TARGET_DIR" "$DATA_DIR"

# 5. Tải mã nguồn agent mới nhất từ GitHub
echo "[4/5] Tải mã nguồn agent mới nhất..."
REPO_RAW="https://raw.githubusercontent.com/Nuicuti/roblox-cloud-controller/main/v2/agent"
curl -sL "$REPO_RAW/noka_agent.py" -o "$TARGET_DIR/noka_agent.py"
curl -sL "$REPO_RAW/noka_login.py" -o "$TARGET_DIR/noka_login.py"
curl -sL "$REPO_RAW/cookie_helper.py" -o "$TARGET_DIR/cookie_helper.py"
curl -sL "$REPO_RAW/start-agent.sh" -o "$TARGET_DIR/start-agent.sh"
curl -sL "$REPO_RAW/update.sh" -o "$TARGET_DIR/update.sh"
chmod +x "$TARGET_DIR"/*.py "$TARGET_DIR"/*.sh

# 6. Chạy setup tự động với thông tin project
echo "[5/5] Khởi tạo định danh thiết bị..."
python "$TARGET_DIR/noka_agent.py" setup \
    --api-key "AIzaSyB1wXy5NNVPI6MT-Zp9fMTwchZdNFYOkic" \
    --database "https://roblox-cloud-farm-default-rtdb.firebaseio.com"

echo "=========================================="
echo " CÁCH CHẠY AGENT CHẠY NGẦM:"
echo " nohup bash ~/noka-controller/start-agent.sh > ~/.noka-controller/console.log 2>&1 &"
echo "=========================================="
