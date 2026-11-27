# Cloud Rejoin Control — Redfinger · Termux · Vercel · Firebase

Hệ thống điều khiển từ xa cho 4–6+ clone trên Android root (Redfinger Cloud Phone):

- **Quản lý nhiều máy & Ghép thiết bị an toàn** qua mã ghép 10 phút và Firebase Realtime Database Rules.
- **Tài khoản & Cookie E2EE**: Mã hóa trong trình duyệt (AES-256-GCM + RSA-OAEP), chỉ máy Android đích giải mã và nạp vào WebView SQLite (`agent/cookie_store.py`).
- **Chia lưới Freeform (2×2, 2×3, 3×2…)** với kiểm tra đối chiếu tọa độ thực tế (`bounds_match`) tránh nháy khung hình khi reflow định kỳ.
- **Auto-Rejoin Loop đa lớp (không có auto-solve CAPTCHA)**:
  - Hàng đợi khởi chạy tuần tự không nghẽn vòng lặp (`delayBetweenClonesSec`, `delayAfterLaunchSec`).
  - Kiểm tra crash cửa sổ 4 lớp (`Task list`, `Window manager`, `SurfaceFlinger`, `Screen tree`) — dump lỗi / timeout OpenGL (`uiautomator`) tuyệt đối không bị đánh nhầm thành đóng cửa sổ.
  - Phát hiện màn hình Disconnect / ANR (`disconnectConfirmSec`), chuyển server qua UDP socket theo UID (`teleportDetection` / `teleportGraceSec`), deep link vào thẳng `Place ID` / `Job ID`, và tự tạm dừng khi gặp màn hình đăng nhập / CAPTCHA (`auth_required`) để người dùng thao tác thủ công.
  - Chống Android kill tiến trình agent (`oom_score_adj = -1000`, `deviceidle whitelist`, `settings_enable_monitor_phantom_procs false`, `termux-wake-lock`).

## Cài đặt trên Redfinger (Termux)

```bash
curl -sL "https://raw.githubusercontent.com/Nuicuti/roblox-cloud-controller/main/agent/install.sh?ts=$(date +%s)" | bash
```

Chạy ngầm sau khi ghép máy trên dashboard:

```bash
nohup bash ~/noka-controller/start.sh > ~/.noka-controller/console.log 2>&1 &
```

Cập nhật nhanh khi có bản mới:

```bash
bash ~/noka-controller/update.sh
```

## Triển khai Web & Firebase

- **Firebase Rules:** `npx -y firebase-tools@latest deploy --only database --project roblox-cloud-farm`
- **Vercel:** Đặt **Root Directory** trống (thư mục gốc của repo) với các biến môi trường `NEXT_PUBLIC_FIREBASE_*` và `FIREBASE_DATABASE_URL`.

## Kiểm thử local

```powershell
npm run check
npm test
npm run test:crypto
python -m unittest discover -s tests -v
npm run build
```
