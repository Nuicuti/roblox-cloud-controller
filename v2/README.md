# Noka Control — Redfinger, Vercel và Firebase

Bản điều khiển từ xa cho các Roblox clone Noka. Dashboard triển khai trên Vercel; Firebase Realtime Database giữ trạng thái, hàng đợi lệnh và bản tài khoản đã mã hóa. Agent chạy trong Termux trên từng máy Redfinger đã root.

## Các project đã xác định

- Vercel: `nuicuti1/roblox-cloud-controller`.
- Vercel project ID: `prj_x3HTZWoPu3CUgaaMqySfZm3gyANN`.
- Firebase project: `roblox-cloud-farm`.
- Database đã xác minh tồn tại: `https://roblox-cloud-farm-default-rtdb.firebaseio.com`.
- Kho mã hiện tại: `https://github.com/Nuicuti/roblox-cloud-controller`.
- Bản cũ được kiểm tra tại commit `ffb7be430bc191d4ad548a1bd159d23605292034`.

Đây là bản mới được chuẩn bị riêng để đối chiếu trước khi thay bản đang chạy. Bản cũ và dữ liệu hiện có chưa được tự động xóa.

## Chức năng

- Quản lý nhiều máy, mỗi máy có UID và mã ghép riêng.
- Quét các ứng dụng có mã gói `free.noka...`; giữ tên tài khoản và vị trí đã cấu hình khi quét lại.
- Nhập `tên:mật khẩu:cookie` từ dashboard. Mật khẩu có thể để trống: `tên::cookie`.
- Mã hóa tài khoản bằng AES-256-GCM; khóa AES được bọc bằng RSA-OAEP/SHA-256 của máy đích. Vercel/Firebase giữ bản mã; khóa riêng chỉ ở máy đích.
- Nạp cookie theo yêu cầu, kiểm tra tên tài khoản với Roblox trước khi ghi, sao lưu SQLite và đọc lại sau khi ghi. Không xóa cookie của miền khác hoặc xóa WAL thủ công.
- Mở/đóng từng Noka; mở các Noka đang được quản lý.
- Tự mở lại khi tiến trình Noka đã đóng qua hai lần kiểm tra liên tiếp, với thời gian nghỉ tăng dần và giới hạn thử lại.
- Chia lưới hàng/cột và kiểm tra vị trí thực tế do Android báo lại. Mặc định giữ bố trí của repo cũ: Noka 3–4 hàng trên, Noka 1–2 hàng dưới.
- Đổi vị trí một Noka sang ô đang có Noka khác sẽ hoán đổi hai vị trí.
- Mỗi lệnh có ID, hạn bắt đầu 2 phút và kết quả riêng. Lệnh đã xử lý không tự chạy lại khi gửi kết quả gặp lỗi.

Dashboard không cung cấp ô thực thi shell/root tùy ý. Agent chỉ hỗ trợ các thao tác đã định nghĩa trong mã.

## APK đã kiểm tra

Tệp `Noka Arceus Lite [FREE] 1.apk`:

| Thuộc tính | Giá trị |
|---|---|
| Nhãn | `Noka FREE 1` |
| Package | `free.nokaA` |
| Version name/code | `2.744.1081` / `19426` |
| Android tối thiểu | API 26 |
| Native ABI | `arm64-v8a` |
| Launcher đang bật | `com.roblox.client.startup.LauncherAliasMain` |
| Cho phép thay đổi kích thước trong manifest | `true` |
| SHA-256 | `908b0991775d5a181ccf664ef2459e5cbde2050d836d50ed5462ce01b0f2e313` |

`free.nokaB/C/D` được thấy trong mã repo hiện tại. Chưa có APK 2–4 để đối chiếu manifest; agent sẽ quét gói thực tế được cài trên máy. Việc đọc manifest không phải kết luận kiểm toán toàn bộ APK.

## Kết nối Firebase

Chỉ dùng Realtime Database cho bản này, không cần tạo Firestore hoặc tải khóa riêng của service account.

1. Đăng nhập Firebase bằng tài khoản có quyền với project:

   ```bash
   npx -y firebase-tools@latest login
   npx -y firebase-tools@latest apps:list WEB --project roblox-cloud-farm
   ```

2. Nếu chưa có Web app, tạo một Web app trong project. Lấy cấu hình của Web app:

   ```bash
   npx -y firebase-tools@latest apps:sdkconfig WEB <APP_ID> --project roblox-cloud-farm
   ```

3. Project thật đã bật Google Sign-In, Email/Password và Anonymous. `firebase.json` giữ cấu hình cơ bản Email/Password và Anonymous; triển khai lại hai provider này khi cần:

   ```bash
   npx -y firebase-tools@latest deploy --only auth --project roblox-cloud-farm
   ```

4. Với project đã cấu hình trong phiên này, chọn **Tiếp tục với Google** trên dashboard và dùng đúng tài khoản Google bạn đã kết nối Firebase. Email chủ sở hữu được lưu riêng tại `nokaController/v1/ownerEmail`; rules yêu cầu email đã được Firebase xác minh. Không cần đặt mật khẩu dashboard mới.

5. Nếu muốn cấp thêm một tài khoản Email/Password, tự tạo mật khẩu trong Firebase Authentication, lấy UID và thêm giá trị boolean `true` tại:

   ```text
   nokaController/v1/operators/<UID_TAI_KHOAN_DASHBOARD>
   ```

   UID này là của người điều khiển, khác UID của máy Redfinger.

6. Rules mới đã được triển khai lên project thật trong phiên này sau khi vượt qua các bài kiểm tra emulator. Bản rules trước đó được giữ trong thư mục kiểm tra local; dữ liệu tài khoản cũ không bị xóa. Lệnh triển khai lại khi cần:

   ```bash
   npx -y firebase-tools@latest deploy --only database --project roblox-cloud-farm
   ```

   `firebase/database.rules.json` chặn đọc/ghi công khai ở gốc và phân quyền theo tài khoản/máy. **Agent/dashboard cũ dùng truy cập không đăng nhập đã bị chặn đồng bộ sau khi áp dụng rules.** Cần chuyển sang agent mới theo các bước dưới. Lệnh triển khai rules không xóa dữ liệu tại các nhánh cũ.

Kiểm tra đọc danh sách nhánh gốc của database hiện tại đã trả HTTP 200 khi không đăng nhập, có các nhánh `accounts`, `command`, `screenshot`, `status`. Tôi chỉ đọc tên nhánh, không đọc tài khoản/cookie. Vì mã cũ có chức năng root `exec_cmd`, cần chặn ghi lệnh công khai khi chuyển đổi; không giữ rules mở để làm bản mới hoạt động.

## Cấu hình Vercel

Trong project `nuicuti1/roblox-cloud-controller`, thêm các biến sau cho đúng môi trường triển khai:

| Biến | Giá trị |
|---|---|
| `NEXT_PUBLIC_FIREBASE_API_KEY` | `apiKey` từ cấu hình Firebase Web app |
| `NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN` | `authDomain` từ cùng cấu hình Web app |
| `NEXT_PUBLIC_FIREBASE_PROJECT_ID` | `roblox-cloud-farm` |
| `FIREBASE_DATABASE_URL` | `https://roblox-cloud-farm-default-rtdb.firebaseio.com` |

Không cần `FIREBASE_PRIVATE_KEY` hoặc mật khẩu Roblox trong biến môi trường. Firebase Web API key là cấu hình client; quyền đọc/ghi được kiểm tra bằng Firebase Authentication và database rules.

Vercel dùng preset Next.js. Khi bản này đặt trong thư mục `v2` của kho cũ, chọn Root Directory là `v2` cho deployment của bản mới. Nếu triển khai riêng thư mục này làm gốc, để Root Directory trống. Không áp dụng cấu hình static `vercel.json` của bản cũ cho app Next.js này.

Kiểm tra local:

```bash
npm ci
npm run check
npm test
npm run build
```

Sau khi thêm hoặc đổi biến `NEXT_PUBLIC_*`, cần build/deploy lại để trình duyệt nhận cấu hình mới. Bản xem mẫu trong dashboard ghi rõ là dữ liệu mẫu và vô hiệu hóa các lệnh điều khiển.

## Cài một lần trên mỗi máy Redfinger

Điều kiện: Termux `com.termux`, Android có `su`, cho phép Termux dùng root. Cài ứng dụng Noka và đăng nhập/khởi tạo ứng dụng theo nhu cầu.

Chép thư mục `agent` trong gói mã vào Download. Trong Termux thông thường, chạy:

```bash
termux-setup-storage
bash /storage/emulated/0/Download/agent/install-agent.sh
python ~/noka-controller/noka_agent.py setup
```

Nhập Firebase Web API key và database URL khi được hỏi. Agent dùng Anonymous Auth để tạo danh tính riêng, rồi in UID và mã ghép có hiệu lực 10 phút. Mở dashboard, đăng nhập, chọn **Ghép máy mới**, nhập UID/mã ghép và đặt tên máy.

Sau đó chạy agent:

```bash
bash ~/noka-controller/start-agent.sh
```

Agent tự yêu cầu root khi thực hiện thao tác Android. Không chạy toàn bộ Termux bằng root và không chạy `pkg` trong root shell.

Chạy nền sau khi đã thử thành công ở phía trước:

```bash
nohup bash ~/noka-controller/start-agent.sh > ~/.noka-controller/console.log 2>&1 &
```

Agent có khóa tiến trình để tránh hai bản cùng điều khiển một máy. Không chạy song song với agent cũ vì chúng có thể mở/đóng hoặc di chuyển cùng một Noka.

Thư mục `~/.noka-controller` chứa token đăng nhập máy, khóa giải mã, tài khoản và cấu hình riêng, được giới hạn quyền truy cập. Không chép thư mục này lên GitHub hoặc bộ nhớ chia sẻ. Cập nhật mã bằng bộ cài không xóa thư mục dữ liệu này.

## Dùng dashboard hằng ngày

1. Chọn máy đang kết nối, bấm **Quét Noka**.
2. Bấm **Nhập tài khoản**, nhập từng dòng theo thứ tự Noka mà hộp nhập hiển thị. Dùng username Roblox, không dùng display name.
3. Chờ lệnh **Gửi tài khoản** hoàn tất trong nhật ký rồi bấm **Nạp cookie và mở Noka** nếu cần đổi phiên.
4. Bấm **Sửa** để đổi nhãn, tên tài khoản, vị trí hoặc bật/tắt quản lý mỗi Noka.
5. Chọn số hàng/cột và **Áp dụng lưới**. Nếu Android chưa bật freeform, thử **Bật cửa sổ tự do** rồi áp dụng lại. Một số máy cần khởi động lại để cờ hệ thống có hiệu lực.
6. Bật **Tự mở lại** nếu muốn agent khôi phục Noka bị đóng. Bấm **Đóng** trên dashboard sẽ tắt theo dõi riêng bản đó, tránh mở lại trái ý bạn.

Lệnh “Gửi” mới chỉ là đã xếp vào hàng đợi; kết quả thực tế nằm trong **Nhật ký lệnh**. Nếu mất mạng, dashboard đánh dấu máy mất kết nối và không cho gửi lệnh mới. Watchdog đang chạy vẫn cố theo dõi tại máy, nhưng không thể hồi phục khi chính tiến trình agent hoặc máy cloud đã bị dừng.

## Giới hạn cần kiểm tra trên thiết bị thật

- Root không tự bảo đảm Android hỗ trợ nhiều cửa sổ tự do. Khai báo resizeable trong APK cũng chưa đủ. Agent kiểm tra hỗ trợ, mở cửa sổ freeform, thay đổi bounds rồi đối chiếu Android báo lại.
- Các biến thể Android có thể định dạng `dumpsys` khác. Nếu không nhận diện chắc chắn cửa sổ hoặc kích thước, agent báo lỗi thay vì nhận thành công.
- Tự mở lại dựa trên tiến trình bị đóng. Chưa phát hiện được game mất kết nối hoặc đứng hình khi tiến trình còn sống; chưa có cơ chế tự vào lại đúng server/game.
- Nạp cookie WebView không đồng nghĩa phiên native của mọi bản Roblox đã nhận cookie. Thông báo chỉ xác nhận kiểm tra cookie với Roblox, ghi/đọc SQLite và mở ứng dụng; không tự coi đó là đăng nhập game thành công.
- Mật khẩu trong định dạng nhập được giữ tương thích với bản cũ nhưng bản này dùng cookie để nạp phiên, không tự điền mật khẩu qua tọa độ màn hình.
- Nếu Noka chưa tạo SQLite Cookies, cần khởi tạo ứng dụng trước. Nếu schema Cookies thay đổi với trường bắt buộc mới chưa biết, bộ ghi dừng và giữ bản sao trên máy.
- Cần thao tác cài/ghép lần đầu trên từng Redfinger. Sau đó điều khiển qua web khi agent còn hoạt động. Tự chạy sau reboot cần thêm cơ chế phù hợp với bản Termux/Android, chưa được thiết lập tự động trong gói này.
- Mất khóa riêng của máy sẽ không giải mã được bản tài khoản đã mã hóa cho máy đó. Muốn chuyển tài khoản sang máy khác, nhập lại và mã hóa cho khóa máy mới.
- Bản mới chưa giữ chức năng root shell, chụp ảnh màn hình hoặc tự bấm qua màn hình chào từ repo cũ. Ba chức năng bạn yêu cầu được ưu tiên: quản lý tài khoản/cookie, tự mở lại và chia lưới.

## Kiểm tra đã thực hiện

- TypeScript: đạt.
- Build Next.js: đạt trước khi cấu hình Firebase thật; sẽ chạy lại sau thay đổi cấu hình.
- 7 kiểm tra hợp đồng API/chữ ký token: đạt, gồm từ chối shell tùy ý, gói Android ngoài phạm vi, tài khoản dạng rõ, quyền sở hữu sai, chữ ký giả, issuer/audience sai và token hết hạn.
- 11 kiểm tra agent: đạt sau cập nhật hoán đổi ô, gồm đối chiếu task, lưới không chồng, watchdog, replay và sao lưu SQLite. Kết quả cuối được ghi trong tài liệu bàn giao.
- 1 kiểm tra liên thông: mã hóa bằng đúng hàm WebCrypto của dashboard rồi giải bằng agent Python đạt.
- 9 kiểm tra quyền trên Firebase Realtime Database Emulator: đạt, gồm ngăn máy đọc máy khác, ngăn chiếm quyền máy, kiểm tra lưu vault/lệnh nguyên tử và phân biệt email chủ sở hữu đã/chưa xác minh.
- Các API local không có đăng nhập trả HTTP 401. Trang dashboard trả HTTP 200.
- Firebase Anonymous Auth thật đã được kiểm tra; token hợp lệ nhưng không có quyền operator bị API local trả HTTP 403. Đọc gốc database thật không đăng nhập trả HTTP 401 sau khi triển khai rules. Tài khoản anonymous do bài kiểm tra tạo đã được xóa sau kiểm tra.
- Chưa kiểm tra trực quan bằng trình duyệt trong phiên này do công cụ điều khiển trình duyệt dừng ở bước xác minh URL Windows.
- Chưa chạy agent trên Redfinger thật hoặc kiểm tra đăng nhập tài khoản Roblox thật.

Emulator dùng project demo `demo-noka-controller`; các bài kiểm tra không sửa database thật.

## Nguồn kỹ thuật

- [Firebase Auth REST](https://firebase.google.com/docs/reference/rest/auth).
- [Đối chiếu chữ ký và claims của Firebase ID token](https://firebase.google.com/docs/auth/admin/verify-id-tokens).
- [Xác thực Realtime Database REST](https://firebase.google.com/docs/database/rest/auth).
- [Đọc Realtime Database qua REST](https://firebase.google.com/docs/database/rest/retrieve-data).
- [Đa cửa sổ Android](https://source.android.com/docs/core/display/multi-window).
- [Mã ActivityManagerShellCommand Android 10](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/am/ActivityManagerShellCommand.java).

Mã AOSP đọc bốn số rời cho `am task resize TASK LEFT TOP RIGHT BOTTOM`; không gửi lại cú pháp dấu phẩy sau một lệnh đã thành công như bản cũ.
