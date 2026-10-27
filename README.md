# Lab8 Mail Real — Client ↔ Server + Google SMTP + MySQL

Ứng dụng JavaFX gửi **mail thật** qua Google SMTP (Gmail hoặc Google Workspace như `@vku.udn.vn`) và/hoặc **mail LAN** qua UDP Client ↔ Server.
Mỗi thư gửi đi được lưu **1 bản ghi** trong MySQL (`lab8_mail.emails`) và hiển thị trên Server Dashboard.

## Hai trường hợp sử dụng (đúng yêu cầu lab)

| Trường hợp | Khi nào | Cách gửi |
|---|---|---|
| **A. Gửi thư thật** | Gửi cho bất kỳ ai | Dùng **chính email thật của tài khoản đang đăng nhập** + **App Password riêng của email đó** → người nhận qua Google SMTP. |
| **B. Hai máy cùng LAN** | Bạn và bạn cùng WiFi | Mỗi máy/cửa sổ chạy Client với tài khoản server riêng, thấy thư của nhau trong **Hộp thư** và **Danh bạ**. |

Client tự tìm server qua localhost hoặc broadcast LAN (UDP discovery 20230).

> **Đăng nhập email nào thì gửi/nhận bằng chính email đó.**
> Mỗi tài khoản Gmail/Google Workspace cần **App Password riêng** của chính nó.
> App Password của `chamm4930@gmail.com` **không dùng được** để gửi thay cho email khác.
> Nếu tài khoản thứ hai chưa có App Password, hãy dùng chế độ **LAN** hoặc tạo App Password cho chính email đó.


## Vì sao lần đầu bắt nhập "App Password"?

Google **không cho** ứng dụng Java đăng nhập bằng mật khẩu thường. Google yêu cầu một
**Google App Password** 16 ký tự. Đây là lớp bảo mật của Google, không phải lỗi của chương trình.
VKU dùng Google Workspace nên tài khoản `@vku.udn.vn` cũng tạo App Password và gửi qua `smtp.gmail.com`.

- **Mỗi email có App Password riêng.** Không dùng chung App Password giữa các tài khoản Google.
- Nhập App Password khi đang đăng nhập đúng tài khoản đó, tại **Cấu hình → Google SMTP / IMAP**.
- App Password được lưu theo từng email trong `client-mail.properties` **trên máy client**, không gửi lên server.
- Các lần gửi sau: chỉ cần **Đến / Tiêu đề / Nội dung** rồi bấm Gửi.

### Tạo Google App Password

1. Đăng nhập đúng tài khoản email sẽ dùng để gửi (ví dụ `ten@gmail.com`) và bật **xác minh 2 bước**.
2. Mở https://myaccount.google.com/apppasswords
3. Tạo App Password, nhận 16 ký tự (có thể có dấu cách).
4. Mở Client → đăng nhập tài khoản server có email đó → **Cấu hình Google** → dán App Password → **Kiểm tra Google** → **Lưu cấu hình**.

## Yêu cầu

- JDK 21 (đường dẫn nằm trong `run-*.bat`)
- JavaFX + Ikonli + MySQL Connector (đã có trong `lib/`)
- MySQL/MariaDB localhost (XAMPP). Máy này dùng **cổng 3307**, XAMPP thường là **3306**.
- Internet để gửi Google (chỉ trường hợp A)

## Cấu hình database

1. Bật MySQL trong XAMPP.
2. Sửa `db.properties` cho đúng cổng.
3. Tạo schema một lần:

```cmd
C:\xampp_new\mysql\bin\mysql.exe -u root -h 127.0.0.1 -P 3307 < sql\schema.sql
```

## Cấu hình client

File `client-mail.properties` (tự tạo khi chạy client) lưu **App Password theo từng email**:

```properties
active.email=ban@gmail.com
appPassword.ban@gmail.com=xxxx xxxx xxxx xxxx
appPassword.ten-khac@vku.udn.vn=yyyy yyyy yyyy yyyy
send.mode=GMAIL
default.to=nguoi-nhan@example.com
```

- Mỗi lần đăng nhập, client tự chọn đúng dòng `appPassword.<email>` của tài khoản đó.
- Email đăng ký tài khoản **không bắt buộc** `@gmail.com` — có thể là `@vku.udn.vn` (Google Workspace), `@congty.vn`, v.v.
- Với Gmail/Google Workspace, header `From` dùng **chính email của tài khoản đang đăng nhập** và **tên hiển thị** trong Hồ sơ.
- Với tên miền không phải Google, `smtp.gmail.com` không gửi thay được; hãy dùng **chế độ LAN** hoặc bổ sung SMTP của nhà cung cấp đó.
- File cấu hình cũ có `gmail.address` + `gmail.appPassword` sẽ được tự động chuyển sang `appPassword.<email>` khi khởi động.

### Tên hiển thị — thứ điện thoại người nhận nhìn thấy

Mở **Cấu hình & Hồ sơ** (bấm vào tên tài khoản ở góc phải) → sửa **Tên hiển thị** và **Email thật** → **LƯU HỒ SƠ**.

- Tên này được gửi lên server (`PROFILE`) và lưu ở `accounts.display_name`.
- Khi gửi Gmail, client dùng đúng tên này trong header `From: "Tên hiển thị" <email>`.
- Vì vậy trên điện thoại/Gmail của người nhận sẽ thấy **tên thật**, không phải username.
- Mỗi thư còn lưu thêm cột `emails.from_name` để đối chiếu trong MySQL.


## Chạy

Mở hai cửa sổ Command Prompt trong thư mục project:

```cmd
run-server.bat
```

```cmd
run-client.bat
```

```cmd
run-client-2.bat
```

`run-client-2.bat` mở **CLIENT B** với file cấu hình riêng `client-mail-b.properties`
và tài khoản server gợi ý `Triiiii` / `123456` (email `tritv.24it@vku.udn.vn`).
Hai cửa sổ có badge **CLIENT A** / **CLIENT B** ở góc trên để không nhầm khi demo.

### Demo 2 client song song

1. Chạy `run-server.bat`.
2. Chạy `run-client.bat` → đăng nhập `chamm4930` (email `chamm4930@gmail.com` + App Password của chính email này).
3. Chạy `run-client-2.bat` → đăng nhập `Triiiii` (mật khẩu `123456`, email `tritv.24it@vku.udn.vn`).
4. CLIENT B đã có App Password riêng cho `tritv.24it@vku.udn.vn`. Nếu đổi sang tài khoản Google khác,
   tạo **App Password riêng** cho đúng email đó rồi nhập trong **Cấu hình** của CLIENT B.
   Không dùng App Password của `chamm4930`. Nếu chưa có, chọn **LAN** để demo gửi/nhận nội bộ.
5. Mở tab **Danh bạ** ở cả hai cửa sổ để thấy nhau đang online; mỗi lần gửi đều có dòng mới trong MySQL.

### Demo A — Gửi mail thật, lưu MySQL

1. Server hiện badge `● DB: MySQL` (xanh).
2. Client đăng nhập tài khoản server (ví dụ `chamm4930` / `123456`).
3. Mở **Cấu hình & Hồ sơ** → nhập **Tên hiển thị** thật → **LƯU HỒ SƠ**.
   Mở phần **Google SMTP / IMAP** → kiểm tra đúng email tài khoản + **App Password riêng của email đó** → **Kiểm tra Google**.
4. **Soạn thư** → chọn **Gửi thật qua Gmail** → nhập email người nhận → Gửi.
5. Hộp thoại kết quả hiện `lab8_mail.emails #<id>` — đây là bằng chứng thư đã lưu DB.
6. Mở hộp thư người nhận (điện thoại/Gmail) để thấy thư thật và **đúng tên hiển thị**.

### Demo B — Hai máy cùng WiFi (LAN)

1. Máy A chạy Server (cho phép UDP 2023 và 20230 qua Windows Firewall).
2. Máy A và máy B mỗi máy chạy Client; mỗi người đăng ký tài khoản với email thật của mình
   (bất kỳ tên miền nào). Mở tab **Danh bạ** để thấy nhau đang online.
3. Nhấp vào người bạn trong **Danh bạ** → soạn thư → chọn **Gửi thật qua Google** (nếu email đó là
   Gmail/Google Workspace và đã có App Password riêng) hoặc **Gửi LAN nội bộ** (không cần mạng ngoài).
4. Cả hai client đều thấy thư trong Hộp thư/Thư đã gửi; phần chi tiết hiển thị **IP máy gửi**.
5. Mỗi lần gửi đều có một bản ghi trong MySQL.

### Demo C — 2 máy: gửi từ client → điện thoại trả lời → quay về client

Đây là kịch bản chính của tab **Test 2 máy** trong client:

1. **Máy A** cấu hình Google + App Password, đặt **Tên hiển thị** trong Hồ sơ, rồi gửi thư thật
   tới địa chỉ email của người cầm điện thoại.
2. **Điện thoại / máy B** mở Gmail: thấy đúng tên người gửi → bấm **Trả lời (Reply)** → gửi.
   Không cần cài client trên điện thoại.
3. **Client A** tự đọc Gmail bằng **IMAP mỗi ~7 giây**; hoặc bấm **ĐỒNG BỘ GMAIL NGAY**.
   Sau lần đầu, client chỉ đọc **thư mới theo UID** nên thư trả lời từ điện thoại hiện nhanh,
   không phải quét lại toàn bộ hộp thư.
4. Thư trả lời **hoặc thư mới hoàn toàn** (không có `In-Reply-To`) đều được ghi thêm 1 dòng vào
   `emails` (`folder = INBOX`, `from_name` của người gửi) và xuất hiện ngay trong **Hộp thư**.
5. Nếu điện thoại dùng **cùng tài khoản Gmail** với client, thư đã gửi nằm trong Gmail **Sent Mail**
   chứ không nằm trong INBOX. Client đọc thêm Sent Mail và đưa thư đó vào mục **Thư đã gửi**
   (lệnh `SEND_SYNC`), không tạo bản ghi trùng với hộp thư người nhận.
6. Tab **Test 2 máy** hiển thị 4 bước đã hoàn thành, số liệu MySQL tăng lên, và **protocol log**
   `SMTP OK → IMAP → RECEIVE OK → emails.id=…` để trình bày với giảng viên.

Cơ chế chống trùng: client lưu `Message-ID` của thư mình vừa gửi; khi IMAP đọc lại chính thư đó
thì bỏ qua. Server cũng chống trùng theo `emails.message_id`.

- Client dùng **IMAP**, không cần bật POP3 trong Gmail.
- Điện thoại gửi thư mới hay bấm Trả lời đều được nhận, miễn là thư nằm trong hộp thư Gmail
  của tài khoản đang đăng nhập.
- Khi mở một thư, client tải lại **HTML, ảnh và tệp đính kèm** theo `Message-ID`; nội dung này
  không lưu vào MySQL nên mở lại luôn có bản đầy đủ từ Gmail.

## Ảnh đại diện tài khoản (ảnh Gmail thật, tự đồng bộ)

Ảnh đại diện hiển thị ở **góc trên bên phải** và **thẻ tài khoản ở sidebar**.
Khi tài khoản Google đã kết nối, app dùng **đúng ảnh profile hiện tại của Gmail** —
bạn đổi ảnh trên điện thoại thì app tự cập nhật, **không cần upload ảnh thủ công**.

- **Kết nối một lần:** bấm vào **ảnh đại diện** (hoặc **Cấu hình → Hồ sơ người gửi →
  Đồng bộ ảnh Gmail thật**) → dán **OAuth Client ID + Client Secret** → **ĐĂNG NHẬP GOOGLE** →
  chọn đúng tài khoản → **Allow**.
- App dùng **OAuth 2.0 Desktop (loopback + PKCE)** với quyền `openid email profile`, lấy trường
  `picture` từ Google. Ảnh được **tải về `avatars/`** để hiện ngay, kể cả khi mất mạng.
- **Tự đồng bộ:** khi mở app, khi quay lại cửa sổ app, và **mỗi 15 phút**. Nút **Đồng bộ lại**
  trong Hồ sơ để cập nhật ngay. Refresh token lưu riêng theo từng email.
- **Ảnh Google luôn được ưu tiên** khi đã kết nối, nên ảnh trong app luôn khớp với Gmail.
- **Tạo OAuth Client ID một lần (nếu chưa có):**
  1. Vào `console.cloud.google.com/apis/credentials`.
  2. Tạo project → **OAuth consent screen** → `External` → thêm email tài khoản vào **Test users**.
  3. **Create credentials → OAuth client ID → Desktop app**.
  4. Dán **Client ID** + **Client Secret** vào hộp thoại ảnh Google → **ĐĂNG NHẬP GOOGLE**.
  5. Nếu muốn refresh token không hết hạn sau 7 ngày, **Publish app** trên OAuth consent screen.
- OAuth chỉ đọc tên/ảnh đại diện; **không đọc nội dung mail, không thay App Password**.
  Refresh token và ảnh Google chỉ lưu trên máy client, không gửi lên server.
- Chỉ khi **không muốn dùng OAuth** mới cần **chọn ảnh từ máy** hoặc dán **URL ảnh** thủ công.
- Nếu chưa có ảnh Google, client thử **Gravatar** công khai; không có thì dùng chữ cái đầu trên nền gradient.
- **Lỗi thường gặp:** `client_secret is missing` → chưa dán Client Secret (Desktop app luôn có secret);
  `redirect_uri_mismatch` → Client ID tạo sai loại, phải là **Desktop app**;
  `access_denied` → email chưa có trong **Test users** hoặc bạn đã bấm Cancel.
- Lý do cần OAuth: Google **không trả ảnh đại diện qua IMAP/App Password**; endpoint công khai
  `google.com/s2/photos/profile/<email>` trả `404`. OAuth là cách chính thức duy nhất để lấy đúng ảnh.

### Kiểm tra dữ liệu MySQL

```cmd
C:\xampp_new\mysql\bin\mysql.exe -u root -h 127.0.0.1 -P 3307 --default-character-set=utf8mb4 ^
  -e "SELECT id,folder,sender_user,from_name,from_email,to_email,subject,sent_at FROM lab8_mail.emails ORDER BY id DESC LIMIT 10;"
```

Hoặc mở phpMyAdmin → database `lab8_mail` → bảng `emails`.

- Mỗi lần bấm Gửi = **1 dòng mới** (`folder = SENT`).
- Mỗi thư trả lời đồng bộ từ điện thoại = **1 dòng mới** (`folder = INBOX`).
- `from_name` là tên hiển thị người gửi; `message_id` dùng để chống trùng.
- `sent_at` là **DATETIME giờ địa phương** của máy chạy MySQL. JDBC dùng
  `connectionTimeZone=LOCAL&preserveInstants=false` để giữ nguyên giá trị này, nhờ đó cột
  **Sent at** trên giao diện server khớp đúng giờ gửi (không bị lệch +7 giờ).
- Nếu DB offline, server vẫn ghi file backup trong `server_mail/<user>/` và client báo màu vàng.

### Giao thức UDP mới

| Lệnh | Ý nghĩa |
|---|---|
| `PROFILE\|user\|pass\|name\|email` | Cập nhật tên hiển thị + email thật vào MySQL |
| `SUMMARY\|user` | Trả số liệu hộp thư/MySQL cho tab Test 2 máy |
| `SEND\|…\|fromName` | Lưu thư gửi kèm tên hiển thị (`from_name`) |
| `RECEIVE\|…\|fromName` | Lưu thư trả lời từ Gmail/điện thoại kèm tên người trả lời |
| `DBINFO` | Số email, số tài khoản, bản ghi mới nhất |

`MAIL|...` và `RECEIVE` có thêm trường `fromName` ở cuối để client hiển thị đúng tên.
Mỗi dòng `MAIL|...` còn có `messageId` để client mở lại đúng thư trên Gmail.


## Hiển thị HTML, ảnh và tệp đính kèm

- Thư được đọc bằng **IMAP** và hiển thị trong `WebView`: giữ định dạng HTML, tự tải ảnh remote,
  thay ảnh nhúng `cid:` bằng dữ liệu ảnh thật.
- Nút **Đính kèm file** và **Chèn ảnh** nằm ngay trong cửa sổ soạn thư.
  Tổng dung lượng tệp tối đa **20 MB**.
- Ảnh được gửi dạng **inline** (`multipart/related` + `Content-ID`) nên hiện trực tiếp trong nội dung;
  file khác gửi dạng tệp đính kèm thường (`multipart/mixed`).
- Thanh **TỆP ĐÍNH KÈM** dưới nội dung thư cho phép lưu từng tệp về máy.
- Gửi tệp/ảnh hiện hỗ trợ ở chế độ **Gmail**. Chế độ **LAN** chỉ gửi nội dung văn bản.


## Xử lý lỗi thường gặp

| Lỗi | Cách xử lý |
|---|---|
| `535-5.7.8 Username and Password not accepted` | App Password sai/hết hiệu lực, **hoặc đang dùng App Password của tài khoản khác**. Tạo App Password mới cho đúng email đang đăng nhập và nhập lại. |
| `Chưa có Google App Password` | Mở **Cấu hình**, nhập App Password riêng cho email của tài khoản đang đăng nhập. |
| Gửi Gmail báo lỗi dù đã có App Password | App Password thuộc tài khoản Google khác. Mỗi Gmail/Google Workspace cần App Password riêng; email domain khác dùng LAN hoặc SMTP riêng. |
| `Không kết nối được server` | Chạy `run-server.bat`; kiểm tra cùng WiFi; mở firewall UDP 2023/20230. |
| `DB: OFFLINE` | Bật MySQL, kiểm tra `db.properties` (3306 hoặc 3307). |
| Google không nhận | Kiểm tra thư trong Spam; xác nhận đã bật 2-Step Verification cho đúng tài khoản gửi. |
| Ảnh đại diện vẫn là chữ cái | Google không cho lấy ảnh Gmail qua App Password. Bấm vào **ảnh đại diện** → **Đồng bộ ảnh Gmail thật** → đăng nhập Google một lần. Sau đó app tự đồng bộ ảnh thật, không cần upload. |
| Ảnh Google không tự cập nhật | Kiểm tra **Hồ sơ** báo *Ảnh Google: đang bật*. Nếu refresh token hết hạn (app OAuth còn ở Testing), bấm ảnh đại diện để đăng nhập lại Google. |

## Cấu trúc

| Thành phần | Vai trò |
|---|---|
| `src/client` | JavaFX client, UDP, Google SMTP, IMAP đồng bộ thư/trả lời, đọc HTML/ảnh/tệp đính kèm, `MailConfig` |
| `src/server` | JavaFX server, UDP, MySQL (`MailDatabase`, `from_name`) |
| `client-mail.properties` | App Password **theo từng email** + chế độ gửi (chỉ trên máy client) |
| `db.properties` | Kết nối JDBC localhost |
| `sql/schema.sql` | DB `accounts`, `emails` |
| `avatars/` | Ảnh Gmail thật tải về máy (tự tạo, đã ignore git) |
| `server_mail/` | Backup file (song song với DB) |
