package client;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Gửi thư thật qua Google SMTP (SSL 465) — Gmail hoặc Google Workspace như @vku.udn.vn.
 * App Password chỉ nằm trên máy client, không gửi lên server.
 */
public final class GmailSmtpService {
    private static final String HOST = "smtp.gmail.com";
    private static final int PORT = 465;

    private GmailSmtpService() { }

    /** Kiểm tra tài khoản + App Password mà không gửi thư. */
    public static void verify(String from, String appPassword) throws IOException {
        try (Session session = new Session()) {
            session.authenticate(from, appPassword);
            session.quit();
        }
    }

    /** Gửi thư và trả về Message-ID để lưu vào database, tránh đồng bộ trùng khi nhận lại. */
    public static String send(String from, String appPassword, String to, String subject, String body) throws IOException {
        return send(from, "", appPassword, to, subject, body);
    }

    public static String send(String from, String fromName, String appPassword, String to,
                              String subject, String body) throws IOException {
        return send(from, from, fromName, appPassword, to, subject, body);
    }

    /**
     * Gửi bằng email của tài khoản đang đăng nhập. Header From dùng đúng email đó,
     * xác thực SMTP bằng chính tài khoản + App Password riêng của tài khoản.
     * (Tham số authUser/from tách riêng để hỗ trợ alias Google Workspace sau này.)
     */
    public static String send(String authUser, String from, String fromName, String appPassword,
                              String to, String subject, String body) throws IOException {
        return send(authUser, from, fromName, appPassword, to, subject, body, List.of());
    }

    /** Gửi thư kèm file/ảnh. Ảnh được nhúng inline bằng Content-ID để hiện ngay trong nội dung. */
    public static String send(String authUser, String from, String fromName, String appPassword,
                              String to, String subject, String body, List<File> attachments) throws IOException {
        return send(authUser, from, fromName, appPassword, to, List.of(), List.of(),
                subject, body, attachments);
    }

    /** Gửi thư kèm Cc/Bcc và tệp đính kèm. Bcc chỉ dùng để gửi, không xuất hiện trong header. */
    public static String send(String authUser, String from, String fromName, String appPassword,
                              String to, List<String> cc, List<String> bcc,
                              String subject, String body, List<File> attachments) throws IOException {
        return send(authUser, from, fromName, appPassword, List.of(to), cc, bcc,
                subject, body, attachments);
    }

    /** Gửi thư cho nhiều người nhận (To), kèm Cc/Bcc và tệp đính kèm. */
    public static String send(String authUser, String from, String fromName, String appPassword,
                              List<String> to, List<String> cc, List<String> bcc,
                              String subject, String body, List<File> attachments) throws IOException {
        String auth = clean(authUser);
        String sender = clean(from);
        List<String> toList = cleanList(to);
        if (toList.isEmpty()) throw new IOException("Chưa có người nhận.");
        String messageId = buildMessageId(sender);
        List<File> files = attachments == null ? List.of() : attachments;
        List<String> ccList = cleanList(cc);
        List<String> bccList = cleanList(bcc);

        long totalBytes = 0;
        for (File file : files) {
            if (file == null || !file.isFile() || !file.canRead()) {
                throw new IOException("Không đọc được tệp đính kèm: " + (file == null ? "" : file.getName()));
            }
            totalBytes += file.length();
        }
        if (totalBytes > 20L * 1024 * 1024) {
            throw new IOException("Tổng dung lượng tệp đính kèm vượt quá 20 MB.");
        }

        try (Session session = new Session()) {
            session.authenticate(auth, appPassword);

            session.command("MAIL FROM:<" + auth + ">", "250", "địa chỉ người gửi");
            for (String toAddress : toList) addRecipient(session, toAddress);
            for (String ccAddress : ccList) addRecipient(session, ccAddress);
            for (String bccAddress : bccList) addRecipient(session, bccAddress);

            session.command("DATA", "354", "bắt đầu nội dung thư");
            session.out.write(buildMessage(sender, fromName, toList, ccList,
                    subject == null ? "" : subject, body == null ? "" : body, messageId, files));
            session.out.write("\r\n.\r\n");
            session.out.flush();
            session.expect(session.read(), "250", "gửi nội dung thư");
            session.quit();
        }
        return messageId;
    }

    private static void addRecipient(Session session, String address) throws IOException {
        String reply = session.command("RCPT TO:<" + address + ">", null, "địa chỉ người nhận");
        if (!reply.startsWith("250") && !reply.startsWith("251")) {
            throw new IOException("Google từ chối người nhận " + address + ": " + reply);
        }
    }

    private static List<String> cleanList(List<String> values) throws IOException {
        List<String> result = new ArrayList<>();
        if (values == null) return result;
        for (String value : values) {
            String address = clean(value);
            if (!address.isEmpty() && !result.contains(address)) result.add(address);
        }
        return result;
    }

    private static String buildMessageId(String from) {
        int at = from.indexOf('@');
        String domain = at > 0 ? from.substring(at + 1) : "mailflow.local";
        return "<" + UUID.randomUUID().toString().replace("-", "") + "@" + domain + ">";
    }

    private static String buildMessage(String from, String fromName, List<String> to, List<String> cc,
                                       String subject, String body, String messageId,
                                       List<File> attachments) throws IOException {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
        String fromHeader = (fromName == null || fromName.isBlank())
                ? from
                : encodeWord(fromName) + " <" + from + ">";
        StringBuilder message = new StringBuilder();
        message.append("From: ").append(fromHeader).append("\r\n")
                .append("To: ").append(String.join(", ", to)).append("\r\n")
                .append("Reply-To: ").append(from).append("\r\n");
        if (cc != null && !cc.isEmpty()) {
            message.append("Cc: ").append(String.join(", ", cc)).append("\r\n");
        }
        message
                .append("Subject: ").append(encodeSubject(subject)).append("\r\n")
                .append("Date: ").append(date).append("\r\n")
                .append("Message-ID: ").append(messageId).append("\r\n")
                .append("MIME-Version: 1.0\r\n")
                .append("X-Mailer: Lab8 MailFlow Client\r\n");

        if (attachments == null || attachments.isEmpty()) {
            message.append("Content-Type: text/plain; charset=UTF-8\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n\r\n")
                    .append(base64Text(body));
            return message.toString();
        }

        List<File> images = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File file : attachments) {
            if (isImageFile(file)) images.add(file);
            else files.add(file);
        }

        String mixed = boundary("Mixed");
        String related = boundary("Related");
        String alternative = boundary("Alt");

        message.append("Content-Type: multipart/mixed; boundary=\"")
                .append(mixed).append("\"\r\n\r\n");

        String alternativePart = buildAlternativePart(body, images, alternative);
        if (!images.isEmpty()) {
            message.append("--").append(mixed).append("\r\n")
                    .append("Content-Type: multipart/related; boundary=\"")
                    .append(related).append("\"; type=\"multipart/alternative\"\r\n\r\n")
                    .append("--").append(related).append("\r\n")
                    .append(alternativePart);
            for (int i = 0; i < images.size(); i++) {
                message.append("--").append(related).append("\r\n")
                        .append(buildAttachmentPart(images.get(i), "inline", cidFor(i)));
            }
            message.append("--").append(related).append("--\r\n");
        } else {
            message.append("--").append(mixed).append("\r\n")
                    .append(alternativePart);
        }

        for (File file : files) {
            message.append("--").append(mixed).append("\r\n")
                    .append(buildAttachmentPart(file, "attachment", null));
        }
        message.append("--").append(mixed).append("--\r\n");
        return message.toString();
    }

    private static String buildAlternativePart(String body, List<File> images, String boundary) {
        StringBuilder part = new StringBuilder();
        part.append("Content-Type: multipart/alternative; boundary=\"")
                .append(boundary).append("\"\r\n\r\n");
        part.append("--").append(boundary).append("\r\n")
                .append("Content-Type: text/plain; charset=UTF-8\r\n")
                .append("Content-Transfer-Encoding: base64\r\n\r\n")
                .append(base64Text(body)).append("\r\n");
        part.append("--").append(boundary).append("\r\n")
                .append("Content-Type: text/html; charset=UTF-8\r\n")
                .append("Content-Transfer-Encoding: base64\r\n\r\n")
                .append(base64Text(buildHtmlBody(body, images))).append("\r\n");
        part.append("--").append(boundary).append("--\r\n");
        return part.toString();
    }

    private static String buildHtmlBody(String body, List<File> images) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><body style=\"font-family:Arial,Helvetica,sans-serif;")
                .append("font-size:14px;color:#222222;line-height:1.55;\">")
                .append("<div style=\"white-space:pre-wrap;\">")
                .append(escapeHtml(body == null ? "" : body))
                .append("</div>");
        for (int i = 0; i < images.size(); i++) {
            File image = images.get(i);
            html.append("<div style=\"margin-top:16px;\"><img src=\"cid:")
                    .append(cidFor(i)).append("\" alt=\"")
                    .append(escapeHtml(image.getName()))
                    .append("\" style=\"max-width:100%;height:auto;border-radius:8px;\"></div>");
        }
        return html.append("</body></html>").toString();
    }

    private static String buildAttachmentPart(File file, String disposition, String cid) throws IOException {
        String name = safeFileName(file.getName());
        String encodedName = encodeWord(name);
        StringBuilder part = new StringBuilder();
        part.append("Content-Type: ").append(mimeType(file))
                .append("; name=\"").append(encodedName).append("\"\r\n")
                .append("Content-Disposition: ").append(disposition)
                .append("; filename=\"").append(encodedName).append("\"\r\n");
        if (cid != null && !cid.isBlank()) {
            part.append("Content-ID: <").append(cid).append(">\r\n");
        }
        part.append("Content-Transfer-Encoding: base64\r\n\r\n")
                .append(base64Bytes(Files.readAllBytes(file.toPath())))
                .append("\r\n");
        return part.toString();
    }

    private static boolean isImageFile(File file) {
        return file != null && mimeType(file).toLowerCase().startsWith("image/");
    }

    private static String mimeType(File file) {
        if (file == null) return "application/octet-stream";
        try {
            String type = Files.probeContentType(file.toPath());
            if (type != null && !type.isBlank()) return type;
        } catch (IOException ignored) { }
        String type = URLConnection.guessContentTypeFromName(file.getName());
        return type == null || type.isBlank() ? "application/octet-stream" : type;
    }

    private static String safeFileName(String value) {
        String name = value == null ? "" : value.replace("\r", " ").replace("\n", " ")
                .replace("\"", "'").trim();
        if (name.isBlank()) name = "tep-dinh-kem";
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private static String boundary(String kind) {
        return "=_MailFlow_" + kind + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String cidFor(int index) {
        return "mailflow-image-" + index + "@mailflow";
    }

    private static String base64Text(String value) {
        return base64Bytes((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Bytes(byte[] value) {
        return Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(value == null ? new byte[0] : value);
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Mã hóa UTF-8 để tiếng Việt hiển thị đúng trên Gmail/VKU. */
    private static String encodeSubject(String subject) {
        String value = subject.replace("\r", " ").replace("\n", " ").trim();
        if (value.length() > 400) value = value.substring(0, 400);
        return encodeWord(value);
    }

    /** Tên hiển thị trên điện thoại/Gmail của người nhận. */
    private static String encodeWord(String value) {
        String text = value == null ? "" : value.replace("\r", " ").replace("\n", " ").trim();
        String base64 = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        return "=?UTF-8?B?" + base64 + "?=";
    }

    private static String clean(String value) throws IOException {
        String address = value == null ? "" : value.trim();
        if (address.isEmpty()
                || address.indexOf('\r') >= 0
                || address.indexOf('\n') >= 0
                || address.indexOf('<') >= 0
                || address.indexOf('>') >= 0
                || address.indexOf(' ') >= 0) {
            throw new IOException("Địa chỉ email không hợp lệ: " + value);
        }
        return address;
    }

    private static String normalizePassword(String value) {
        return value == null ? "" : value.replaceAll("\\s", "");
    }

    private static final class Session implements Closeable {
        private final SSLSocket socket;
        private final BufferedReader in;
        private final BufferedWriter out;

        Session() throws IOException {
            socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(HOST, PORT);
            socket.setSoTimeout(20000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            expect(read(), "220", "kết nối Google SMTP");
        }

        void authenticate(String from, String appPassword) throws IOException {
            String password = normalizePassword(appPassword);
            if (password.isEmpty()) {
                throw new IOException("Chưa có Google App Password. Mở Cấu hình Google để nhập một lần.");
            }

            command("EHLO mailflow.local", "250", "EHLO");
            command("AUTH LOGIN", "334", "AUTH LOGIN");
            command(Base64.getEncoder().encodeToString(clean(from).getBytes(StandardCharsets.UTF_8)),
                    "334", "tài khoản Google");

            String authReply = command(
                    Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)),
                    null, "App Password");

            if (!authReply.startsWith("235")) {
                if (authReply.startsWith("535") || authReply.startsWith("534") || authReply.startsWith("530")) {
                    throw new IOException("Google từ chối App Password. Hãy tạo App Password mới (16 ký tự) tại "
                            + "myaccount.google.com/apppasswords rồi nhập lại. Phản hồi Google: " + authReply);
                }
                throw new IOException("Đăng nhập Google thất bại: " + authReply);
            }
        }

        String command(String line, String expectedCode, String step) throws IOException {
            out.write(line);
            out.write("\r\n");
            out.flush();
            String reply = read();
            if (expectedCode != null) expect(reply, expectedCode, step);
            return reply;
        }

        String read() throws IOException {
            String line = in.readLine();
            if (line == null) throw new IOException("Máy chủ Google đóng kết nối bất ngờ.");
            StringBuilder reply = new StringBuilder(line);
            while (line.length() > 3 && line.charAt(3) == '-') {
                line = in.readLine();
                if (line == null) break;
                reply.append('\n').append(line);
            }
            return reply.toString();
        }

        void expect(String response, String code, String step) throws IOException {
            if (response == null || !response.startsWith(code)) {
                throw new IOException("Google SMTP lỗi ở bước " + step + ": " + response);
            }
        }

        void quit() {
            try { command("QUIT", null, "QUIT"); } catch (IOException ignored) { }
        }

        @Override public void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
