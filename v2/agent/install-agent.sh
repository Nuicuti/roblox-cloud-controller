#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
[[ "$(id -u)" != 0 ]] || { printf '%s\n' 'Chạy bộ cài bằng người dùng Termux thông thường.'; exit 1; }
[[ -x /data/data/com.termux/files/usr/bin/pkg ]] || { printf '%s\n' 'Cần Termux com.termux.'; exit 1; }
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
target_dir=/data/data/com.termux/files/home/noka-controller
pkg update -y
pkg install -y python python-cryptography
mkdir -p "$target_dir" /data/data/com.termux/files/home/.noka-controller
chmod 700 "$target_dir" /data/data/com.termux/files/home/.noka-controller
for file in noka_agent.py noka_login.py cookie_helper.py start-agent.sh; do
    install -m 700 "$source_dir/$file" "$target_dir/$file"
done
printf '%s\n' 'Đã cài agent. Tiếp theo chạy:' 'python ~/noka-controller/noka_agent.py setup'
