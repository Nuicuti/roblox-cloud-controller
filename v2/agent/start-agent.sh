#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
export PATH=/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin
export TMPDIR=/data/data/com.termux/files/usr/tmp
export HOME=/data/data/com.termux/files/home
cd "$HOME/noka-controller"
if command -v termux-wake-lock >/dev/null 2>&1; then
    termux-wake-lock || true
    trap 'termux-wake-unlock >/dev/null 2>&1 || true' EXIT
fi
python noka_agent.py run
