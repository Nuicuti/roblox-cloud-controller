#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

echo "=========================================="
echo " CÀI ĐẶT ROBLOX CLOUD REJOIN AGENT"
echo "=========================================="

if [ "$(id -u)" = "0" ]; then
    echo "[!] Chạy bằng user Termux thông thường (không gõ su trước)."
    exit 1
fi

PREFIX="/data/data/com.termux/files/usr"
if [ -d "$PREFIX/etc/apt" ]; then
    echo "deb https://packages.termux.dev/apt/termux-main stable main" > "$PREFIX/etc/apt/sources.list"
fi

if [ ! -d "/storage/emulated/0/Download" ]; then
    termux-setup-storage || true
fi

apt update -y || {
    echo "deb https://grimler.se/termux/termux-main stable main" > "$PREFIX/etc/apt/sources.list"
    apt update -y
}
pkg install -y python python-cryptography curl

TARGET_DIR="$HOME/noka-controller"
DATA_DIR="$HOME/.noka-controller"
mkdir -p "$TARGET_DIR" "$DATA_DIR"
chmod 700 "$TARGET_DIR" "$DATA_DIR"

pkill -9 -f 'noka_agent.py|agent.py' 2>/dev/null || true
rm -f "$DATA_DIR/agent.lock" "$DATA_DIR"/*.tmp* "$DATA_DIR"/noka_ck_*.json 2>/dev/null || true

REPO_RAW="https://raw.githubusercontent.com/Nuicuti/roblox-cloud-controller/main/agent"
TS=$(date +%s)
for f in agent.py android_os.py cookie_store.py login_flow.py start.sh update.sh; do
    curl -sL "$REPO_RAW/$f?ts=$TS" -o "$TARGET_DIR/$f"
done
chmod 700 "$TARGET_DIR"/*.py "$TARGET_DIR"/*.sh

python "$TARGET_DIR/agent.py" setup \
    --api-key "AIzaSyB1wXy5NNVPI6MT-Zp9fMTwchZdNFYOkic" \
    --database "https://roblox-cloud-farm-default-rtdb.firebaseio.com"

echo "=========================================="
echo " CHẠY NGẦM:"
echo " nohup bash ~/noka-controller/start.sh > ~/.noka-controller/console.log 2>&1 &"
echo "=========================================="
