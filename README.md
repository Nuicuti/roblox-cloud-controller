# Cloud Rejoin Control — Redfinger · Android Root APK · Vercel · Firebase

Hệ thống điều khiển từ xa cho 4–6+ clone trên Android root (Redfinger Cloud Phone):

- **Native Android Root APK (`android/`) — Khuyên dùng thay Termux:**
  - Chạy ngầm bền bỉ qua `RejoinForegroundService` + `PARTIAL_WAKE_LOCK` + `BOOT_COMPLETED` + tự nâng ưu tiên `oom_score_adj = -1000`.
  - Hỗ trợ `RejoinAccessibilityService` (tùy chọn) đọc trực tiếp màn hình Disconnect / Đăng nhập / CAPTCHA ~5ms mà không bị lỗi treo `waitForIdle` của `uiautomator` khi 4–6 game OpenGL đang chạy.
  - Tự động nạp `.ROBLOSECURITY` vào WebView SQLite bằng `android.database.sqlite.SQLiteDatabase` (không cần Termux, không cần binary `sqlite3` hay Python).
  - Giao diện app ngay trên Redfinger hiển thị trạng thái Root, nút bật dịch vụ ngầm, UID và nút tạo mã ghép 32 hex.
- **Tự động build `.apk` qua GitHub Actions:** Workflow [build-apk.yml](.github/workflows/build-apk.yml) chạy unit test và xuất tệp `rejoin-control-apk` (`app-debug.apk`) tại tab **Actions** trên GitHub.
- **Tùy chọn Termux (`agent/`):** Vẫn được giữ làm phương án dự phòng nếu muốn chạy qua CLI.

## Cài đặt nhanh bằng APK (thay thế Termux)

1. Tải trực tiếp file cài đặt trên Redfinger:
   `https://github.com/Nuicuti/roblox-cloud-controller/releases/latest/download/rejoin-control.apk`
2. Cài `rejoin-control.apk` lên máy Redfinger, mở app **Rejoin Control**:
   - Bấm **Tạo mã ghép máy** và cấp quyền **Root (SuperUser)** khi được hỏi.
   - Bấm **Bật dịch vụ chạy ngầm** (nên bấm thêm **Mở cài đặt Accessibility** và **Tắt tối ưu pin** để đọc màn hình nhanh và không bị tắt ngầm).
3. Ghép UID + mã 32 hex vào Web Dashboard, sau đó cấu hình tab **Auto-Rejoin Loop** và lưới cửa sổ.

## Triển khai Web & Firebase

- **Firebase Rules:** `npx -y firebase-tools@latest deploy --only database --project roblox-cloud-farm`
- **Vercel:** Đặt **Root Directory** trống (thư mục gốc repo) với biến môi trường `NEXT_PUBLIC_FIREBASE_*` và `FIREBASE_DATABASE_URL`.

## Kiểm thử local

```powershell
npm run check
npm test
npm run test:crypto
python -m unittest discover -s tests -v
npm run build
```
