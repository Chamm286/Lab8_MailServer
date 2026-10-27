# 📬 MailFlow — Lab 8 Mail Server

Ứng dụng **Client ↔ Server** viết bằng **JavaFX**, gửi **mail thật** qua Google SMTP, đồng bộ thư bằng **IMAP**, lưu lịch sử vào **MySQL** và có **Dashboard server** để trình diễn.

> **Mô hình lab:** Máy 1 chạy **Mail Server** · Máy 2 chạy **Mail Client** · Kết nối qua LAN bằng UDP, không cần cấu hình IP thủ công.

---

## 🚀 Điểm nổi bật

| Nhóm | Tính năng |
|---|---|
| **Gửi mail** | Gửi thật qua Gmail / Google Workspace SMTP, nhiều người nhận, Cc, Bcc, tệp đính kèm, ảnh inline |
| **Nhận mail** | Đọc Gmail bằng IMAP, tự đồng bộ thư trả lời từ điện thoại, hiển thị HTML + ảnh + tệp đính kèm |
| **Giao diện** | Inbox hiện đại, avatar thật theo email, trạng thái chưa đọc, tìm kiếm, hiệu ứng mượt, toast + modal thành công |
| **Danh bạ** | Tải từ database server, chọn nhiều liên hệ bằng `Ctrl`, gửi một lần cho nhiều email |
| **Server** | Dashboard theo dõi kết nối, số thư, MySQL, log giao thức, biểu đồ request |
| **Dữ liệu** | Mỗi thư = 1 bản ghi `lab8_mail.emails` + 1 file backup trong `server_mail/` |

---

## 🧭 Mục lục

1. [Kiến trúc hệ thống](#1-kiến-trúc-hệ-thống)
2. [Yêu cầu môi trường](#2-yêu-cầu-môi-trường)
3. [Cài đặt lần đầu](#3-cài-đặt-lần-đầu)
4. [Chạy trên 1 máy](#4-chạy-trên-1-máy)
5. [Kết nối 2 máy qua LAN](#5-kết-nối-2-máy-qua-lan)
6. [Cấu hình Google SMTP](#6-cấu-hình-google-smtp)
7. [Ảnh đại diện Gmail thật (OAuth)](#7-ảnh-đại-diện-gmail-thật-oauth)
8. [Hướng dẫn sử dụng giao diện](#8-hướng-dẫn-sử-dụng-giao-diện)
9. [Kịch bản demo cho giảng viên](#9-kịch-bản-demo-cho-giảng-viên)
10. [Kiểm tra MySQL](#10-kiểm-tra-mysql)
11. [Xử lý lỗi thường gặp](#11-xử-lý-lỗi-thường-gặp)
12. [Cấu trúc project](#12-cấu-trúc-project)
13. [Giao thức UDP](#13-giao-thức-udp)

---

## 1. Kiến trúc hệ thống

```text
┌──────────────────────────┐          UDP 2023           ┌──────────────────────────┐
│        MÁY 1             │ ◄─────────────────────────► │        MÁY 2             │
│  Mail Server + Dashboard │                             │      MailFlow Client     │
│                          │      UDP 20230 (discover)   │                          │
│  MySQL: lab8_mail        │ ◄─────────────────────────► │  Gmail SMTP / IMAP       │
│  server_mail/ (backup)   │                             │  client-mail.properties  │
└──────────────────────────┘                             └──────────────────────────┘
            │                                                         │
            └────────────────── LAN / WiFi ──────────────────────────┘
```

- **Server** giữ tài khoản, hộp thư, lịch sử MySQL và phục vụ Dashboard.
- **Client** gửi/nhận mail thật, tự tìm server trong LAN bằng UDP broadcast.
- **Không cần nhập IP server** — client tự dò cổng `20230`, server trả về cổng dịch vụ `2023`.

---

## 2. Yêu cầu môi trường

| Thành phần | Phiên bản / Ghi chú |
|---|---|
| JDK | **21** (đường dẫn cấu hình trong `run-*.bat`) |
| JavaFX | 21 — có sẵn trong `lib/javafx-sdk-21.0.5` |
| Thư viện | Ikonli, MySQL Connector, Jakarta Mail, Angus Mail — có sẵn trong `lib/` |
| Database | MySQL / MariaDB (XAMPP), cổng **3306** hoặc **3307** |
| Mạng | Cùng WiFi/LAN nếu chạy 2 máy; Internet nếu gửi Gmail |
| Hệ điều hành | Windows 10/11 |

> Nếu JDK cài ở đường dẫn khác, mở `run-server.bat`, `run-client.bat`, `run-client-2.bat` và sửa biến `JDK_BIN`.

---

## 3. Cài đặt lần đầu

### 3.1. Clone project

```powershell
git clone https://github.com/Chamm286/Lab8_MailServer.git
cd Lab8_MailServer
```

### 3.2. Bật MySQL

Mở **XAMPP Control Panel** → **Start** MySQL.

### 3.3. Cấu hình kết nối

Mở `db.properties` và sửa đúng cổng MySQL:

```properties
jdbc.url=jdbc:mysql://127.0.0.1:3307/lab8_mail?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL&preserveInstants=false&characterEncoding=UTF-8
jdbc.user=root
jdbc.password=
jdbc.driver=com.mysql.cj.jdbc.Driver
```

> XAMPP mặc định dùng **3306**. Nếu MySQL của bạn ở 3307 thì giữ nguyên.

### 3.4. Tạo database

```powershell
# Sửa đường dẫn mysql.exe cho đúng máy bạn
C:\xampp\mysql\bin\mysql.exe -u root -h 127.0.0.1 -P 3307 < sql\schema.sql
```

Hoặc mở **phpMyAdmin** → tab **Import** → chọn `sql/schema.sql` → **Go**.

Sau khi import sẽ có:

- Database `lab8_mail`
- Bảng `accounts` — tài khoản đăng nhập client
- Bảng `emails` — lịch sử gửi/nhận

### 3.5. Kiểm tra nhanh

```powershell
.\run-server.bat
```

Dashboard server hiện **● DB: MySQL** màu xanh là đã kết nối thành công.

---

## 4. Chạy trên 1 máy

Mở **3 cửa sổ Command Prompt** tại thư mục project:

```powershell
# Cửa sổ 1 — Server
.\run-server.bat

# Cửa sổ 2 — Client A
.\run-client.bat

# Cửa sổ 3 — Client B (tuỳ chọn, để demo 2 người)
.\run-client-2.bat
```

| File chạy | Vai trò | Tài khoản gợi ý |
|---|---|---|
| `run-server.bat` | Server + Dashboard + MySQL | — |
| `run-client.bat` | **CLIENT A** | `chamm4930` |
| `run-client-2.bat` | **CLIENT B** | `Triiiii` / `123456` |

Hai client dùng file cấu hình riêng (`client-mail.properties` và `client-mail-b.properties`) nên không ghi đè App Password của nhau.

---

## 5. Kết nối 2 máy qua LAN

### 5.1. Chuẩn bị

- **Máy 1**: chạy **Mail Server** + MySQL.
- **Máy 2**: chạy **Mail Client**.
- Hai máy **cùng một WiFi / LAN**.
- Mỗi máy cài **JDK 21** và có thư mục project (copy nguyên thư mục, gồm `lib/`).

### 5.2. Lấy IP máy chủ

Trên **Máy 1**:

```powershell
ipconfig
```

Ghi lại địa chỉ IPv4, ví dụ: `192.168.1.15`.

### 5.3. Mở firewall trên Máy 1

Mở **PowerShell với quyền Administrator**:

```powershell
New-NetFirewallRule -DisplayName "MailFlow UDP 2023"  -Direction Inbound -Protocol UDP -LocalPort 2023  -Action Allow
New-NetFirewallRule -DisplayName "MailFlow UDP 20230" -Direction Inbound -Protocol UDP -LocalPort 20230 -Action Allow
```

> `20230` là cổng **dò server**. `2023` là cổng **trao đổi dữ liệu**.

### 5.4. Chạy server trên Máy 1

```powershell
.\run-server.bat
```

Dashboard hiện:

- **● DB: MySQL** (xanh)
- Địa chỉ IP LAN
- Trạng thái lắng nghe UDP `2023`

### 5.5. Chạy client trên Máy 2

Copy thư mục project sang Máy 2, sau đó:

```powershell
.\run-client.bat
```

Client tự động:

1. Gửi gói `DISCOVER|MAILFLOW` tới broadcast LAN + localhost.
2. Nhận phản hồi từ server Máy 1.
3. Kết nối tới đúng IP/cổng và hiển thị trạng thái **CONNECTED**.

Nếu muốn chắc chắn, xem dòng trạng thái server ở góc trên client.

### 5.6. Tạo tài khoản và gửi thử

1. Trên **Máy 2**, mở client → **Đăng ký** tài khoản mới.
2. Nhập **username**, **mật khẩu**, **email thật**, **tên hiển thị**.
3. Đăng nhập → mở tab **Danh bạ** → thấy tài khoản Máy 1 đang online.
4. Chọn người nhận → **Soạn thư** → chọn:
   - **Gửi LAN nội bộ**: không cần App Password, thư đi trong hệ thống.
   - **Gửi thật qua Gmail**: cần App Password của chính email đang đăng nhập.
5. Mở **Hộp thư** trên Máy 1 để thấy thư vừa nhận.

### 5.7. Nếu client không tìm thấy server

| Kiểm tra | Cách xử lý |
|---|---|
| Hai máy khác subnet | Kết nối cùng một router WiFi, tắt VPN |
| Firewall chặn UDP | Thêm rule `2023` và `20230` như trên |
| Server chưa chạy | Chạy `run-server.bat` trước |
| WiFi chặn broadcast | Dùng **Hotspot điện thoại** hoặc bật `Network discovery` trong Windows |
| Vẫn lỗi | Trên client, kiểm tra log; đảm bảo server hiện đúng IP LAN |

---

## 6. Cấu hình Google SMTP

Google **không cho** ứng dụng Java đăng nhập bằng mật khẩu thường. Bạn cần **App Password 16 ký tự**.

### 6.1. Tạo App Password

1. Đăng nhập đúng tài khoản sẽ dùng để gửi.
2. Bật **xác minh 2 bước (2-Step Verification)**.
3. Mở <https://myaccount.google.com/apppasswords>.
4. Tạo App Password → nhận 16 ký tự.
5. Trong client: **Cấu hình → Google SMTP / IMAP** → dán App Password → **Kiểm tra Google** → **Lưu**.

> **Mỗi email cần App Password riêng.** App Password của tài khoản A không dùng được cho tài khoản B.
> Tài khoản `@vku.udn.vn` (Google Workspace) cũng dùng App Password và gửi qua `smtp.gmail.com`.

### 6.2. File cấu hình client

`client-mail.properties` được tạo tự động trên từng máy:

```properties
active.email=ban@gmail.com
appPassword.ban@gmail.com=xxxx xxxx xxxx xxxx
appPassword.ten-khac@vku.udn.vn=yyyy yyyy yyyy yyyy
send.mode=GMAIL
default.to=nguoi-nhan@example.com
```

- File này **chỉ nằm trên máy client**, không gửi lên server.
- Đăng nhập email nào thì dùng đúng App Password của email đó.

### 6.3. Tên hiển thị người gửi

Mở **Cấu hình & Hồ sơ** → nhập **Tên hiển thị** + **Email thật** → **LƯU HỒ SƠ**.

- Tên này được lưu ở `accounts.display_name`.
- Header gửi đi có dạng: `From: "Tên hiển thị" <email>`.
- Điện thoại/Gmail người nhận sẽ thấy đúng tên, không phải username.

---

## 7. Ảnh đại diện Gmail thật (OAuth)

Client hiển thị **ảnh thật** trong danh sách thư, ô đọc thư, danh bạ và góc tài khoản.

- Có OAuth Google → dùng ảnh profile thật, tự đồng bộ mỗi **15 phút**.
- Chưa có OAuth → thử ảnh công khai theo email (unavatar/Gravatar).
- Không có ảnh → hiển thị **chữ cái đầu** trên nền gradient.

### Kết nối một lần

1. Bấm vào **ảnh đại diện** góc phải.
2. Dán **OAuth Client ID + Client Secret**.
3. Bấm **ĐĂNG NHẬP GOOGLE** → chọn tài khoản → **Allow**.
4. Ảnh được tải về `avatars/` và dùng ngay cả khi mất mạng.

### Tạo OAuth Client ID

1. Vào <https://console.cloud.google.com/apis/credentials>.
2. Tạo project → **OAuth consent screen** → `External` → thêm email vào **Test users**.
3. **Create credentials → OAuth client ID → Desktop app**.
4. Dán **Client ID + Client Secret** vào hộp thoại ảnh Google.

> OAuth chỉ đọc **tên + ảnh đại diện**, không đọc nội dung mail và không thay App Password.
> Refresh token chỉ lưu trên máy client.

---

## 8. Hướng dẫn sử dụng giao diện

### 8.1. Danh sách thư (sidebar)

- **Hộp thư / Đã gửi / Danh bạ / Test 2 máy / Cấu hình**.
- Chấm xanh = **chưa đọc**; mở thư → chấm mất, chữ chuyển thường.
- Hover để highlight; click để mở thư bên phải.
- Tìm kiếm theo người gửi, tiêu đề hoặc nội dung.

### 8.2. Ô đọc thư

- Header gradient xanh → teal, avatar thật, tên + email người gửi.
- Badge **MySQL** và **IP máy gửi**.
- Nội dung HTML/ảnh/tệp đính kèm hiển thị đầy đủ.
- Nút **TRẢ LỜI**, **Gửi lại**, **Xóa**.

### 8.3. Soạn thư

- **Đến**, **Cc / Bcc** (bấm để mở), **Tiêu đề**.
- Thanh định dạng **B / I / U**.
- **Đính kèm file** hoặc **Chèn ảnh** — tối đa **20 MB**.
- Chọn **Gửi thật qua Gmail** hoặc **Gửi LAN nội bộ**.

### 8.4. Gửi nhiều email từ Danh bạ

1. Mở **Danh bạ**.
2. Giữ `Ctrl` và click chọn nhiều người.
3. Bấm **GỬI NHIỀU EMAIL**.
4. Các email được điền vào ô **Đến**, cách nhau bằng dấu phẩy.
5. Gửi → tất cả người nhận đều nhận được thư.

### 8.5. Hiệu ứng gửi thành công

- Nút Gửi có **spinner xoay** trong lúc chờ SMTP.
- Thành công → **modal ✔ xanh** + toast góc phải + mã `emails.id`.
- Thất bại → modal đỏ + hướng dẫn kiểm tra App Password.

---

## 9. Kịch bản demo cho giảng viên

### Demo A — Gửi mail thật, có lưu MySQL

1. Server hiện **● DB: MySQL**.
2. Client đăng nhập, cấu hình App Password đúng email.
3. **Soạn thư** → **Gửi thật qua Gmail**.
4. Mở Gmail/điện thoại người nhận → **có thư**, đúng tên hiển thị.
5. Kiểm tra MySQL → **có bản ghi mới**.
6. Modal hiện `lab8_mail.emails #<id>`.

### Demo B — Gửi thành công nhưng KHÔNG lưu MySQL

Mở `src/server/ServerCore.java`, dòng 19:

```java
private static final boolean SAVE_MAIL_TO_DATABASE = false;
```

- `false` = **không ghi email mới vào MySQL** (vẫn ghi file mail cho người nhận).
- `true` = lưu MySQL bình thường.

Quy trình:

1. Ghi lại số dòng hiện tại:
   ```sql
   SELECT COUNT(*) AS truoc FROM lab8_mail.emails;
   ```
2. Đặt `SAVE_MAIL_TO_DATABASE = false`.
3. **Chạy lại `run-server.bat`** (bắt buộc để compile code mới).
4. Gửi mail → mở hộp thư người nhận → **vẫn có thư**.
5. Đếm lại:
   ```sql
   SELECT COUNT(*) AS sau FROM lab8_mail.emails;
   ```
   → `truoc = sau` là **đúng yêu cầu**.
6. Demo xong đổi lại `true`, chạy lại server.

> Không cần xóa dữ liệu cũ. `false` chỉ ngăn **ghi dòng mới**, không xóa dòng đã có.
> Nếu muốn bảng sạch để demo, dùng `DELETE FROM emails;` (tuỳ chọn).

### Demo C — Máy A gửi → điện thoại trả lời → quay về client

1. Máy A gửi thư thật tới email người cầm điện thoại.
2. Điện thoại mở Gmail → **Trả lời**.
3. Client A tự đồng bộ IMAP (~7 giây) hoặc bấm **ĐỒNG BỘ GMAIL NGAY**.
4. Thư trả lời xuất hiện trong **Hộp thư**, có `from_name` đúng người trả lời.
5. MySQL có thêm 1 dòng `folder = INBOX`.

### Demo D — Hai máy LAN

1. Máy 1 chạy server; Máy 2 chạy client.
2. Cả hai đăng nhập tài khoản riêng.
3. Mở **Danh bạ** → thấy nhau online.
4. Gửi **LAN nội bộ** → thư hiện trong Hộp thư/Đã gửi của cả hai.
5. Chi tiết thư hiển thị **IP máy gửi**.

---

## 10. Kiểm tra MySQL

### Dòng mới nhất

```sql
SELECT id, folder, sender_user, from_name, from_email,
       to_email, subject, sent_at
FROM lab8_mail.emails
ORDER BY id DESC
LIMIT 10;
```

### Đếm số thư

```sql
SELECT COUNT(*) AS total FROM lab8_mail.emails;
SELECT folder, COUNT(*) FROM lab8_mail.emails GROUP BY folder;
```

### Tài khoản

```sql
SELECT id, username, email, display_name FROM lab8_mail.accounts;
```

Hoặc mở **phpMyAdmin** → `lab8_mail` → bảng `emails`.

---

## 11. Xử lý lỗi thường gặp

| Lỗi | Nguyên nhân / Cách xử lý |
|---|---|
| `535-5.7.8 Username and Password not accepted` | App Password sai, hết hiệu lực, hoặc của tài khoản khác. Tạo App Password mới đúng email. |
| `Chưa có Google App Password` | Vào **Cấu hình** nhập App Password riêng cho email đang đăng nhập. |
| Gửi được nhưng DB vẫn tăng khi `false` | Chưa chạy lại server, hoặc còn dòng cũ. So sánh `COUNT(*)` trước/sau. |
| Client không tìm thấy server | Kiểm tra firewall UDP `2023` + `20230`, cùng WiFi, tắt VPN. |
| `DB: OFFLINE` | Bật MySQL, kiểm tra `db.properties` (3306/3307), import lại schema. |
| `Illegal start of expression` | Comment thiếu dấu `}`. Dùng công tắc `SAVE_MAIL_TO_DATABASE` thay vì comment code. |
| Gmail không nhận thư | Kiểm tra **Spam**, bật 2-Step Verification, xác nhận đúng tài khoản gửi. |
| Ảnh đại diện là chữ cái | Chưa kết nối OAuth. Bấm ảnh góc phải → **Đồng bộ ảnh Gmail thật**. |
| `redirect_uri_mismatch` | OAuth Client ID tạo sai loại — phải là **Desktop app**. |
| Giao diện giật khi refresh | Đã sửa: chỉ cập nhật danh sách khi dữ liệu thay đổi. Đảm bảo dùng bản mới nhất. |

---

## 12. Cấu trúc project

```text
Lab8_Mail_Real/
├── src/
│   ├── client/
│   │   ├── ClientApp.java          # Giao diện JavaFX chính
│   │   ├── ClientCore.java         # UDP + auto discovery
│   │   ├── GmailSmtpService.java   # Gửi SMTP (To/Cc/Bcc, đính kèm)
│   │   ├── GmailImapService.java   # Đồng bộ IMAP, HTML, tệp đính kèm
│   │   ├── GmailPop3Service.java   # POP3 dự phòng
│   │   ├── GoogleOAuthService.java # Lấy ảnh đại diện Google
│   │   └── MailConfig.java         # App Password theo email, OAuth token
│   ├── server/
│   │   ├── ServerApp.java          # Dashboard JavaFX
│   │   ├── ServerCore.java         # UDP server + xử lý lệnh
│   │   └── MailDatabase.java       # MySQL (accounts, emails)
│   └── mailflow.css
├── lib/                            # JavaFX, Ikonli, MySQL, Jakarta Mail
├── sql/schema.sql                  # Tạo DB + bảng
├── avatars/                        # Ảnh Gmail tải về (git ignore)
├── server_mail/                    # File backup thư (git ignore)
├── db.properties                   # Kết nối MySQL
├── client-mail.properties          # Cấu hình client A (git ignore)
├── client-mail-b.properties        # Cấu hình client B (git ignore)
├── run-server.bat
├── run-client.bat
└── run-client-2.bat
```

---

## 13. Giao thức UDP

Server lắng nghe cổng **2023**, discovery **20230**.

| Lệnh | Ý nghĩa |
|---|---|
| `DISCOVER\|MAILFLOW` | Client dò server trong LAN |
| `LOGIN\|user\|pass` | Đăng nhập |
| `REGISTER\|user\|pass\|email\|name` | Tạo tài khoản |
| `PROFILE\|user\|pass\|name\|email` | Cập nhật hồ sơ |
| `LIST` | Danh bạ + trạng thái online |
| `SEND\|...` | Gửi thư + lưu MySQL |
| `RECEIVE\|...` | Lưu thư nhận/trả lời |
| `SEND_SYNC\|...` | Đồng bộ thư đã gửi từ Gmail |
| `MAILBOX\|user\|pass\|folder` | Tải hộp thư |
| `SUMMARY\|user` | Số liệu cho tab Test 2 máy |
| `DBINFO` | Thông tin MySQL |
| `DELETE\|id` | Xóa thư |

---

## 🔐 Ghi chú bảo mật

- App Password và OAuth refresh token **chỉ lưu trên máy client**.
- Server không nhận mật khẩu Gmail.
- Không commit `client-mail.properties`, `avatars/`, `server_mail/` lên Git.
- Với môi trường thật, nên dùng **HTTPS/TLS** thay vì UDP broadcast trong LAN công cộng.

---

<p align="center">
  <b>MailFlow</b> — Lab 8 · JavaFX · Google SMTP/IMAP · MySQL
</p>
