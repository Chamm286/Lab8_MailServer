package server;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class ServerCore {

    public static final String SERVER_DIR = new File(System.getProperty("user.dir"), "server_mail").getAbsolutePath();
    public static final int DISCOVERY_PORT = 20230;
    public static final int    PORT       = 2023;

    /**
     * CÔNG TẮC DEMO CỦA THẦY — chỉ cần sửa đúng 1 dòng này, KHÔNG comment code.
     * true  = lưu mỗi thư vào MySQL (chạy bình thường).
     * false = KHÔNG lưu MySQL, nhưng vẫn ghi file mail cho người nhận.
     */
    private static final boolean SAVE_MAIL_TO_DATABASE = true;

    public interface Logger {
        void log(String msg);
    }

    private final Logger logger;
    private final MailDatabase database;
    private volatile boolean running = true;
    private volatile DatagramSocket serverSocket;
    private volatile DatagramSocket discoverySocket;
    private volatile int boundPort;

    public static class ClientInfo {
        public String username;
        public String ip;
        public long   lastSeen;
        ClientInfo(String u, String i, long t) { username = u; ip = i; lastSeen = t; }
    }
    private final Map<String, ClientInfo> onlineClients = new LinkedHashMap<>();

    public ServerCore(Logger logger) {
        this.logger = logger;
        new File(SERVER_DIR).mkdirs();
        this.database = new MailDatabase(logger);
    }

    public int getBoundPort() { return boundPort; }
    public MailDatabase getDatabase() { return database; }

    public Map<String, ClientInfo> getOnlineClients() {
        long now = System.currentTimeMillis();
        synchronized (onlineClients) {
            onlineClients.entrySet().removeIf(e -> (now - e.getValue().lastSeen) > 10000);
            return new LinkedHashMap<>(onlineClients);
        }
    }

    private boolean isOnline(String username) {
        synchronized (onlineClients) {
            ClientInfo info = onlineClients.get(username);
            return info != null && (System.currentTimeMillis() - info.lastSeen) <= 10000;
        }
    }

    public void start(int port) {
        new Thread(() -> {
            try {
                DatagramSocket socket = null;
                for (int candidate = port; candidate < port + 20 && socket == null; candidate++) {
                    try { socket = new DatagramSocket(candidate, InetAddress.getByName("0.0.0.0")); }
                    catch (BindException occupied) { logger.log("[WARN] UDP port " + candidate + " is occupied; checking the next port"); }
                }
                if (socket == null) throw new BindException("No free UDP port in the selected range");
                serverSocket = socket;
                boundPort = socket.getLocalPort();
                logger.log("[OK] Server started on port " + boundPort);
                logger.log("[DIR] " + new File(SERVER_DIR).getAbsolutePath());
                startDiscoveryResponder();

                byte[] recvBuf = new byte[65507];
                while (running) {
                    DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
                    socket.receive(recvPkt);

                    InetAddress ip = recvPkt.getAddress();
                    int portCli = recvPkt.getPort();

                    String req = new String(recvPkt.getData(), 0, recvPkt.getLength(), "UTF-8").trim();
                    logger.log("[RECV] " + ip.getHostAddress() + ":" + portCli + " | " + shorten(req));

                    String resp = handle(req, ip);

                    if ((req.startsWith("LOGIN|") || req.startsWith("MAILBOX|")) && resp.startsWith("OK")) {
                        String[] parts = req.split("\\|", -1);
                        if (parts.length >= 2) {
                            synchronized (onlineClients) {
                                onlineClients.put(parts[1], new ClientInfo(parts[1], ip.getHostAddress(), System.currentTimeMillis()));
                            }
                        }
                    }
                    byte[] sendBuf = resp.getBytes("UTF-8");
                    socket.send(new DatagramPacket(sendBuf, sendBuf.length, ip, portCli));
                }
                socket.close();
            } catch (Exception e) {
                logger.log("[ERR] Server error: " + e.getMessage());
            }
        }, "UDP-Server").start();
    }

    public void stop() { running = false; if (serverSocket != null) serverSocket.close(); if (discoverySocket != null) discoverySocket.close(); }

    private void startDiscoveryResponder() {
        Thread responder = new Thread(() -> {
            try (DatagramSocket discovery = new DatagramSocket(DISCOVERY_PORT, InetAddress.getByName("0.0.0.0"))) {
                discoverySocket = discovery;
                byte[] buffer = new byte[128];
                while (running) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length); discovery.receive(packet);
                    String query = new String(packet.getData(), packet.getOffset(), packet.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                    if (query.startsWith("DISCOVER|MAILFLOW")) {
                        byte[] response = ("OK|SERVER|" + boundPort).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        discovery.send(new DatagramPacket(response, response.length, packet.getAddress(), packet.getPort()));
                    }
                }
            } catch (Exception e) { if (running) logger.log("[ERR] LAN discovery unavailable: " + e.getMessage()); }
        }, "UDP-Discovery"); responder.setDaemon(true); responder.start();
    }

    private String shorten(String s) {
        if (s.length() > 120) return s.substring(0, 120) + "...";
        return s;
    }

    private String handle(String req, InetAddress clientIP) {
        String[] p = req.split("\\|", -1);
        if (p.length == 0 || p[0].isEmpty()) return "ERROR|Empty request";

        switch (p[0].toUpperCase()) {
            case "DISCOVER":
                return "OK|SERVER|" + boundPort;
            case "REGISTER":
                if (p.length < 3) return "ERROR|Missing username/password";
                return register(p[1], p[2], p.length >= 4 ? p[3] : null, p.length >= 5 ? p[4] : null);
            case "LOGIN":
                if (p.length < 3) return "ERROR|Missing username/password";
                return login(p[1], p[2]);
            case "SEND":
                if (p.length < 6) return "ERROR|Missing fields";
                boolean viaGmail = p.length >= 7 && "GMAIL".equalsIgnoreCase(p[6]);
                return sendMail(p[1], p[2], p[3], p[4], p[5], clientIP, viaGmail, p.length >= 8 ? p[7] : null);
            case "SEND_SYNC":
                if (p.length < 7) return "ERROR|Missing sync fields";
                return syncSentMail(p[1], p[2], p[3], p[4], p[5], clientIP, p[6]);
            case "RECEIVE":
                if (p.length < 6) return "ERROR|Missing receive fields";
                return receiveMail(p[1], p[2], p[3], p[4], p[5], p.length >= 7 ? p[6] : null,
                        p.length >= 8 ? p[7] : null);
            case "PROFILE":
                if (p.length < 4) return "ERROR|Missing profile fields";
                return updateProfile(p[1], p[2], p[3], p.length >= 5 ? p[4] : null);
            case "SUMMARY":
                if (p.length < 2) return "ERROR|Missing account";
                return mailboxSummary(p[1]);
            case "DBINFO":
                return databaseInfo();
            case "FIND":
                return findAccount(p.length >= 2 ? p[1] : null);
            case "LIST":
                return listUsers();
            case "MAILBOX":
                // Dùng cho việc tự động làm mới hộp thư (không ghi log đăng nhập)
                if (p.length < 3) return "ERROR|Missing username/password";
                if (p.length >= 4 && "SENT".equalsIgnoreCase(p[3])) return listMailbox(p[1], "sent", "SENT");
                return login(p[1], p[2]);
            case "SENT":
                if (p.length < 2) return "ERROR|Missing account";
                return listMailbox(p[1], "sent", "SENT");
            case "GET":
                if (p.length < 3) return "ERROR|Missing params";
                return getMailContent(p[1], p.length >= 4 ? p[2] : "INBOX", p.length >= 4 ? p[3] : p[2]);
            case "DELETE":
                if (p.length < 3) return "ERROR|Missing params";
                return deleteMail(p[1], p[2]);
            case "DBSTATUS":
                return database.isReady() ? "OK|DATABASE|CONNECTED" : "ERROR|DATABASE|OFFLINE";
            case "PING":
                return "OK|PONG";
            default:
                return "ERROR|Unknown command";
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  YÊU CẦU #1: ĐĂNG KÝ
    //  Tạo folder: server_mail/<user>/
    //  CHỈ 1 FILE DUY NHẤT: new_email.txt (3 dòng User/Password/Created)
    //  KHÔNG có folder con
    // ═══════════════════════════════════════════════════════════
    private String register(String user, String pass, String emailOpt, String displayNameOpt) {
        try {
            if (user == null || !user.matches("[A-Za-z0-9._-]{1,32}")) return "ERROR|Username must use 1-32 letters, numbers, dots, underscores, or hyphens";
            if (pass == null || pass.length() < 6 || pass.contains("|") || pass.contains("\n")) return "ERROR|Password must have at least 6 characters and cannot contain a pipe or line break";
            File dir = new File(SERVER_DIR, user);
            if (dir.exists()) return "ERROR|Account already exists";
            if (database.isReady() && database.accountExists(user)) return "ERROR|Account already exists";

            dir.mkdirs();
            new File(dir, "inbox").mkdirs();
            new File(dir, "sent").mkdirs();
            new File(dir, "trash").mkdirs();

            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String email = (emailOpt != null && !emailOpt.isBlank()) ? emailOpt.trim() : user + "@gmail.com";
            if (!isValidEmail(email)) return "ERROR|Email không hợp lệ. Ví dụ: ten@gmail.com, ten@vku.udn.vn, ten@congty.vn";
            String displayName = (displayNameOpt == null || displayNameOpt.isBlank()) ? user : displayNameOpt.trim();
            if (displayName.contains("|") || displayName.contains("\n")) return "ERROR|Tên hiển thị không hợp lệ";
            if (displayName.length() > 64) displayName = displayName.substring(0, 64);

            write(new File(dir, "new_email.txt"),
                  "User     : " + user + "\n" +
                  "Password : " + pass + "\n" +
                  "Email    : " + email + "\n" +
                  "Display  : " + displayName + "\n" +
                  "Created  : " + now  + "\n");

            if (database.isReady()) {
                database.registerAccount(user, pass, email, displayName);
                logger.log("[DB] Account saved: " + user + " / " + email + " / " + displayName);
            }

            logger.log("[OK] Registered: " + user + " (" + email + ")");
            return "OK|Register successful. Your email: " + email;
        } catch (Exception e) {
            return "ERROR|" + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CẬP NHẬT HỒ SƠ — tên hiển thị xuất hiện trên điện thoại người nhận
    // ═══════════════════════════════════════════════════════════
    private String updateProfile(String user, String pass, String displayName, String emailOpt) {
        try {
            if (user == null || !user.matches("[A-Za-z0-9._-]{1,32}")) return "ERROR|Invalid account";
            File dir = new File(SERVER_DIR, user);
            File info = new File(dir, "new_email.txt");
            if (!info.isFile() && !(database.isReady() && database.accountExists(user)))
                return "ERROR|Account not found";

            boolean ok;
            if (database.isReady() && database.accountExists(user)) {
                ok = database.authenticate(user, pass);
            } else {
                ok = info.isFile() && pass != null && pass.equals(extract(read(info), "Password"));
            }
            if (!ok) return "ERROR|Wrong username or password";

            String name = displayName == null ? "" : displayName.trim();
            if (name.isBlank()) return "ERROR|Tên hiển thị không được để trống";
            if (name.contains("|") || name.contains("\n")) return "ERROR|Tên hiển thị không hợp lệ";
            if (name.length() > 64) name = name.substring(0, 64);

            String email = emailOpt == null ? "" : emailOpt.trim();
            if (email.isEmpty()) {
                email = info.isFile() ? extract(read(info), "Email") : user + "@gmail.com";
            }
            if (!isValidEmail(email)) return "ERROR|Email không hợp lệ";

            if (database.isReady()) database.updateProfile(user, email, name);

            dir.mkdirs();
            String created = info.isFile() ? extract(read(info), "Created") : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            write(info,
                  "User     : " + user + "\n" +
                  "Password : " + pass + "\n" +
                  "Email    : " + email + "\n" +
                  "Display  : " + name + "\n" +
                  "Created  : " + created + "\n");

            logger.log("[PROFILE] " + user + " | " + name + " | " + email);
            return "OK|PROFILE|" + name.replace("|", "/") + "|" + email;
        } catch (Exception e) {
            return "ERROR|" + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  LOGIN
    //  Đọc User/Password từ new_email.txt
    //  Trả về danh sách TÊN FILE trong folder account
    // ═══════════════════════════════════════════════════════════
    private String login(String user, String pass) {
        try {
            File dir = new File(SERVER_DIR, user);
            File info = new File(dir, "new_email.txt");
            boolean ok = false;

            if (database.isReady() && database.accountExists(user)) {
                ok = database.authenticate(user, pass);
            } else if (info.exists()) {
                String content = read(info);
                ok = user.equals(extract(content, "User")) && pass.equals(extract(content, "Password"));
            } else {
                return "ERROR|Account not found";
            }
            if (!ok) return "ERROR|Wrong username or password";

            String myEmail = user + "@gmail.com";
            String myName = user;
            boolean dbEmailFound = false;
            boolean dbNameFound = false;
            if (database.isReady()) {
                String dbEmail = database.getAccountEmail(user);
                if (dbEmail != null && !dbEmail.isBlank()) { myEmail = dbEmail; dbEmailFound = true; }
                String dbName = database.getAccountDisplayName(user);
                if (dbName != null && !dbName.isBlank()) { myName = dbName; dbNameFound = true; }
            }
            if (info.exists()) {
                String content = read(info);
                if (!dbEmailFound) {
                    String fileEmail = extract(content, "Email");
                    if (fileEmail != null && !fileEmail.isBlank()) myEmail = fileEmail;
                }
                if (!dbNameFound) {
                    String fileDisplay = extract(content, "Display");
                    if (fileDisplay != null && !fileDisplay.isBlank()) myName = fileDisplay;
                }
            }

            StringBuilder sb = new StringBuilder("OK|Login successful\n");
            sb.append("EMAIL|").append(myEmail).append("\n");
            sb.append("NAME|").append(myName.replace("|", "/")).append("\n");
            sb.append("SECTION|INBOX\n");
            if (database.isReady()) sb.append("DB|CONNECTED\n");

            if (database.isReady()) {
                appendDbMailRows(sb, database.listInbox(user), "INBOX");
            } else {
                appendMailRows(sb, new File(dir, "inbox"), "INBOX");
            }
            return sb.toString();
        } catch (Exception e) {
            return "ERROR|" + e.getMessage();
        }
    }

    private String getMailContent(String user, String folder, String fileName) {
        try {
            // DB id: db_123
            if (fileName.startsWith("db_") && database.isReady()) {
                long id = Long.parseLong(fileName.substring(3));
                MailDatabase.EmailRow row = database.getEmail(id);
                if (row == null) return "ERROR|Mail not found";
                return "OK|" + formatEmailContent(row);
            }
            if (!fileName.matches("[A-Za-z0-9._-]{1,160}")) return "ERROR|Invalid mail id";
            File dir = new File(new File(SERVER_DIR, user), folder.toLowerCase(Locale.ROOT));
            File mail = new File(dir, fileName);
            if (!mail.exists() && "inbox".equals(folder.toLowerCase(Locale.ROOT))) mail = new File(new File(SERVER_DIR, user), fileName);
            if (!mail.exists()) return "ERROR|Mail not found";
            return "OK|" + read(mail);
        } catch (Exception e) {
            return "ERROR|" + e.getMessage();
        }
    }

    private String deleteMail(String user, String fileName) {
        try {
            if (fileName.startsWith("db_") && database.isReady()) {
                long id = Long.parseLong(fileName.substring(3));
                return database.deleteEmail(id) ? "OK|Deleted" : "ERROR|Mail not found";
            }
            File dir = new File(SERVER_DIR, user);
            File mail = new File(dir, fileName);
            if (!mail.exists()) mail = new File(new File(dir, "inbox"), fileName);
            if (!mail.exists()) mail = new File(new File(dir, "sent"), fileName);
            if (!mail.exists()) return "ERROR|Mail not found";
            mail.delete();
            return "OK|Deleted";
        } catch (Exception e) {
            return "ERROR|" + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  YÊU CẦU #2: GỬI MAIL
    //  Tạo 1 FILE trong thư mục NGƯỜI NHẬN với 4 thông tin:
    //    FromIP / SentTime / Subject / Body
    // ═══════════════════════════════════════════════════════════
    private String sendMail(String sender, String fromEmail, String toEmail,
                            String subject, String content,
                            InetAddress senderIP, boolean gmailDelivered, String messageId) {
        try {
            if (toEmail == null || toEmail.isBlank()) return "ERROR|Thiếu địa chỉ người nhận";
            if (subject == null) subject = "";
            if (content == null) content = "";
            if (subject.isBlank()) return "ERROR|Tiêu đề không được để trống";
            if (content.isBlank()) return "ERROR|Nội dung không được để trống";

            // Người nhận nội bộ: username (LAN) hoặc email thật đã đăng ký.
            // Hỗ trợ gửi cho nhiều email, phân tách bằng dấu phẩy/chấm phẩy.
            java.util.List<String> recipientEmails = new java.util.ArrayList<>();
            for (String token : toEmail.split("[,;\\s]+")) {
                if (!token.isBlank()) recipientEmails.add(token.trim());
            }
            boolean emailRecipient = toEmail.contains("@");
            if (emailRecipient) {
                if (recipientEmails.isEmpty()) return "ERROR|Thiếu địa chỉ người nhận";
                for (String address : recipientEmails) {
                    if (!isValidEmail(address)) return "ERROR|Email người nhận không hợp lệ: " + address;
                }
            }
            if (gmailDelivered && !emailRecipient) {
                return "ERROR|Gửi thư thật cần địa chỉ email người nhận hợp lệ, không phải username: " + toEmail;
            }
            String recipientUser;
            if (emailRecipient) {
                recipientUser = resolveAccountByEmail(recipientEmails.get(0));
            } else {
                boolean localAccount = toEmail.matches("[A-Za-z0-9._-]{1,32}")
                        && (new File(new File(SERVER_DIR, toEmail), "new_email.txt").isFile()
                            || (database.isReady() && database.accountExists(toEmail)));
                recipientUser = localAccount ? toEmail : null;
            }
            if (!gmailDelivered && recipientUser == null) {
                return "ERROR|Không tìm thấy người nhận nội bộ: " + toEmail;
            }

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
            String fname = "email_" + sender + "_" + ts + ".txt";
            String sentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String fromIp = senderIP.getHostAddress();
            String displayFrom = (fromEmail != null && !fromEmail.isEmpty()) ? fromEmail : sender + "@gmail.com";
            String fromName = sender;
            if (database.isReady()) {
                String dbName = database.getAccountDisplayName(sender);
                if (dbName != null && !dbName.isBlank()) fromName = dbName;
            }
            if (fromName == null || fromName.isBlank()) fromName = sender;

            StringBuilder body = new StringBuilder();
            body.append("From: ").append(displayFrom).append("\n");
            body.append("FromName: ").append(fromName.replace("|", "/")).append("\n");
            body.append("FromIP: ").append(fromIp).append("\n");
            body.append("To: ").append(toEmail).append("\n");
            body.append("Subject: ").append(subject).append("\n");
            body.append("SentTime: ").append(sentTime).append("\n");
            body.append("\n");
            body.append(content).append("\n");

            if (recipientUser != null) {
                File inboxDir = new File(new File(SERVER_DIR, recipientUser), "inbox");
                inboxDir.mkdirs();
                write(new File(inboxDir, fname), body.toString());
            }
            File sentDir = new File(new File(SERVER_DIR, sender), "sent");
            if (!sentDir.exists() && !sentDir.mkdirs()) throw new IOException("Could not create sent-mail folder");
            write(new File(sentDir, fname), body.toString());

            // DATABASE: mỗi lần gửi thư = đúng 1 bản ghi (bảng emails)
            // ═══════════════════════════════════════════════════════════════
            // [DEMO THẦY] Dòng lưu MySQL ở ngay dưới.
            // - Demo "không lưu database": comment dòng dbId = database.insertEmail(...)
            //   (giữ nguyên phần ghi file inbox/sent phía trên → người nhận vẫn có mail).
            // - Sau demo: bỏ comment dòng đó rồi chạy lại server.
            // ═══════════════════════════════════════════════════════════════
            long dbId = -1;
            if (database.isReady() && SAVE_MAIL_TO_DATABASE) {
                // folder=SENT; inbox lấy theo recipient_user; sent lấy theo sender_user.
                dbId = database.insertEmail(
                        sender, fromName, displayFrom, toEmail, subject, content, fromIp,
                        "SENT", recipientUser, gmailDelivered, messageId);
                logger.log("[DB] Insert emails.id=" + dbId + " | " + sender + " -> " + toEmail
                        + (recipientUser == null ? "" : " | inbox=" + recipientUser));
            } else if (database.isReady()) {
                logger.log("[DB] Bỏ qua lưu MySQL vì SAVE_MAIL_TO_DATABASE = false (chế độ demo).");
            }

            logger.log("[SEND] " + sender + " -> " + toEmail + " | " + subject
                    + (gmailDelivered ? " | GMAIL" : " | LAN"));
            return "OK|" + (gmailDelivered ? "Gmail SMTP" : "LAN")
                    + (dbId > 0 ? " + database emails.id=" + dbId : " (không lưu database)")
                    + (recipientUser != null ? " inbox=" + recipientUser : "");
        } catch (Exception e) {
            return "ERROR|" + e.getMessage();
        }
    }

    /**
     * Thư do chính tài khoản gửi từ điện thoại/Gmail (Sent Mail) được client đồng bộ lên.
     * Không gửi lại qua SMTP, chỉ liên kết/ghi MySQL để thư hiện trong mục "Thư đã gửi".
     */
    private String syncSentMail(String sender, String fromEmail, String toEmail,
                                String subject, String content,
                                InetAddress senderIP, String messageId) {
        try {
            if (sender == null || !sender.matches("[A-Za-z0-9._-]{1,32}"))
                return "ERROR|Invalid sender account";
            if (messageId == null || messageId.isBlank())
                return "ERROR|Missing Message-ID";
            if (fromEmail == null || !isValidEmail(fromEmail))
                return "ERROR|Invalid sender email";
            if (toEmail == null || !isValidEmail(toEmail))
                return "ERROR|Invalid recipient email";
            if (subject == null) subject = "";
            if (content == null) content = "";
            if (subject.isBlank()) subject = "(Không có tiêu đề)";

            boolean accountExists = (database.isReady() && database.accountExists(sender))
                    || new File(new File(SERVER_DIR, sender), "new_email.txt").isFile();
            if (!accountExists) return "ERROR|Sender account not found";
            if (!database.isReady()) return "ERROR|Database offline";

            String recipientUser = resolveAccountByEmail(toEmail);
            String fromName = sender;
            String dbName = database.getAccountDisplayName(sender);
            if (dbName != null && !dbName.isBlank()) fromName = dbName;

            MailDatabase.SentSyncResult result = database.syncSentEmail(sender, fromName, fromEmail, toEmail,
                    subject, content, senderIP == null ? "gmail-imap" : senderIP.getHostAddress(),
                    recipientUser, messageId);
            if (result.id == -2) return "ERROR|Message-ID belongs to another account";
            if (result.id <= 0) return "ERROR|Could not save sent email";
            if (result.alreadySynced) {
                return "OK|Already synchronized emails.id=" + result.id;
            }

            logger.log("[SEND-SYNC] " + sender + " -> " + toEmail + " | " + subject
                    + " | emails.id=" + result.id + (recipientUser == null ? "" : " | inbox=" + recipientUser));
            return "OK|SentSynced emails.id=" + result.id;
        } catch (Exception e) {
            return "ERROR|" + e.getMessage();
        }
    }

    /**
     * Thư trả lời từ điện thoại/Gmail được client đồng bộ qua IMAP rồi gửi lên đây.
     * Chống trùng bằng Message-ID để nhiều client cùng online không tạo nhiều bản ghi.
     */
    private String receiveMail(String recipientUser, String fromEmail, String toEmail,
                               String subject, String content, String messageId, String fromName) {
        try {
            if (recipientUser == null || !recipientUser.matches("[A-Za-z0-9._-]{1,32}"))
                return "ERROR|Invalid recipient account";
            boolean accountExists = (database.isReady() && database.accountExists(recipientUser))
                    || new File(new File(SERVER_DIR, recipientUser), "new_email.txt").isFile();
            if (!accountExists) return "ERROR|Recipient account not found";
            if (fromEmail == null || !isValidEmail(fromEmail)) return "ERROR|Invalid sender email";
            if (toEmail == null || !isValidEmail(toEmail)) return "ERROR|Invalid recipient email";
            String safeSubject = subject == null ? "(Không có tiêu đề)" : subject;
            String safeContent = content == null ? "" : content;
            String safeName = (fromName == null || fromName.isBlank()) ? fromEmail : fromName.trim();
            if (safeName.length() > 64) safeName = safeName.substring(0, 64);

            long id = -1;
            if (database.isReady()) {
                id = database.insertIncomingEmail(recipientUser, safeName, fromEmail, toEmail,
                        safeSubject, safeContent, messageId);
                if (id <= 0) return "OK|Already synchronized";
            }

            // Ghi 1 file backup trong hộp thư của người nhận (song song với MySQL).
            try {
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
                File inboxDir = new File(new File(SERVER_DIR, recipientUser), "inbox");
                inboxDir.mkdirs();
                String fname = "email_" + recipientUser + "_" + ts + ".txt";
                write(new File(inboxDir, fname),
                        "From: " + fromEmail + "\n" +
                        "FromName: " + safeName.replace("|", "/") + "\n" +
                        "FromIP: gmail-sync\n" +
                        "To: " + toEmail + "\n" +
                        "Subject: " + safeSubject.replace("\n", " ") + "\n" +
                        "SentTime: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n" +
                        "\n" + safeContent + "\n");
            } catch (Exception ignored) { }

            if (id > 0) {
                logger.log("[RECV] Reply from " + fromEmail + " -> " + recipientUser
                        + " | " + safeSubject + " | emails.id=" + id);
                return "OK|Received emails.id=" + id;
            }
            return "OK|Received (file backup)";
        } catch (Exception e) {
            return "ERROR|" + e.getMessage();
        }
    }

    /** Tìm tài khoản nội bộ theo email hoặc plus-alias (vd: chamm4930+friend@gmail.com). */
    private String findAccount(String email) {
        try {
            if (email == null || email.isBlank()) return "ERROR|Missing email";
            String user = resolveAccountByEmail(email.trim());
            if (user == null) return "ERROR|Account not found";
            String display = user;
            String accountEmail = user + "@gmail.com";
            if (database.isReady()) {
                String dbName = database.getAccountDisplayName(user);
                if (dbName != null && !dbName.isBlank()) display = dbName;
                String dbEmail = database.getAccountEmail(user);
                if (dbEmail != null && !dbEmail.isBlank()) accountEmail = dbEmail;
            }
            return "OK|" + user + "|" + accountEmail + "|" + display.replace("|", "/");
        } catch (Exception e) {
            return "ERROR|" + e.getMessage();
        }
    }

    /** Số liệu MySQL để dashboard/client hiển thị cho giảng viên thấy dữ liệu thật. */
    private String databaseInfo() {
        try {
            if (!database.isReady()) return "ERROR|DATABASE|OFFLINE";
            int emails = database.countEmails();
            int accounts = database.countAccounts();
            String lastId = "", lastTo = "", lastSubject = "", lastTime = "";
            java.util.List<MailDatabase.EmailRow> latest = database.listAllEmails(1);
            if (!latest.isEmpty()) {
                MailDatabase.EmailRow row = latest.get(0);
                lastId = String.valueOf(row.id);
                lastTo = safe(row.toEmail);
                lastSubject = safe(row.subject).replace("|", "/");
                lastTime = row.sentAt == null ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(row.sentAt);
            }
            return "OK|DB|" + emails + "|" + accounts + "|" + lastId + "|" + lastTo
                    + "|" + lastSubject + "|" + lastTime;
        } catch (Exception e) {
            return "ERROR|" + e.getMessage();
        }
    }

    /**
     * Tóm tắt hộp thư của một tài khoản (dùng cho màn hình Demo 2 máy).
     * OK|SUMMARY|inbox|sent|totalEmails|totalAccounts|lastInboxId|lastInboxName|lastInboxFrom|lastInboxSubject|lastInboxTime|lastSentId|lastSentTo|lastSentSubject|lastSentTime
     */
    private String mailboxSummary(String user) {
        try {
            if (user == null || !user.matches("[A-Za-z0-9._-]{1,32}")) return "ERROR|Invalid account";
            if (!database.isReady()) return "ERROR|Database offline";
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            int inbox = database.countInbox(user);
            int sent = database.countSent(user);
            int totalEmails = database.countEmails();
            int totalAccounts = database.countAccounts();

            String liId = "", liName = "", liFrom = "", liSubject = "", liTime = "";
            java.util.List<MailDatabase.EmailRow> in = database.listInbox(user);
            if (!in.isEmpty()) {
                MailDatabase.EmailRow r = in.get(0);
                liId = String.valueOf(r.id);
                liName = safe(r.fromName);
                liFrom = safe(r.fromEmail);
                liSubject = safe(r.subject).replace("|", "/");
                liTime = r.sentAt == null ? "" : fmt.format(r.sentAt);
            }
            String lsId = "", lsTo = "", lsSubject = "", lsTime = "";
            java.util.List<MailDatabase.EmailRow> out = database.listSent(user);
            if (!out.isEmpty()) {
                MailDatabase.EmailRow r = out.get(0);
                lsId = String.valueOf(r.id);
                lsTo = safe(r.toEmail);
                lsSubject = safe(r.subject).replace("|", "/");
                lsTime = r.sentAt == null ? "" : fmt.format(r.sentAt);
            }
            return "OK|SUMMARY|" + inbox + "|" + sent + "|" + totalEmails + "|" + totalAccounts
                    + "|" + liId + "|" + liName + "|" + liFrom + "|" + liSubject + "|" + liTime
                    + "|" + lsId + "|" + lsTo + "|" + lsSubject + "|" + lsTime;
        } catch (Exception e) {
            return "ERROR|" + e.getMessage();
        }
    }

    private String listUsers() {
        try {
            StringBuilder sb = new StringBuilder("OK|USERLIST\n");
            Set<String> seen = new LinkedHashSet<>();
            if (database.isReady()) {
                for (MailDatabase.AccountRow a : database.listAccounts()) {
                    seen.add(a.username);
                    String display = a.displayName == null || a.displayName.isBlank() ? a.username : a.displayName;
                    sb.append("USER|").append(a.username).append("|").append(safe(a.email)).append("|")
                      .append(isOnline(a.username) ? "1" : "0").append("|")
                      .append(display.replace("|", "/")).append("\n");
                }
            }
            File root = new File(SERVER_DIR);
            File[] subs = root.listFiles();
            if (subs != null) {
                Arrays.sort(subs, Comparator.comparing(File::getName));
                for (File f : subs) {
                    if (f.isDirectory() && new File(f, "new_email.txt").exists() && seen.add(f.getName())) {
                        String infoText = read(new File(f, "new_email.txt"));
                        String email = extract(infoText, "Email");
                        if (email.isBlank()) email = f.getName() + "@mailflow.local";
                        String display = extract(infoText, "Display");
                        if (display.isBlank()) display = f.getName();
                        sb.append("USER|").append(f.getName()).append("|").append(email).append("|")
                          .append(isOnline(f.getName()) ? "1" : "0").append("|")
                          .append(display.replace("|", "/")).append("\n");
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "ERROR|" + e.getMessage();
        }
    }

    private void appendMailRows(StringBuilder response, File folder, String section) throws IOException {
        File[] mails = folder.listFiles((d, n) -> n.startsWith("email_") && n.endsWith(".txt"));
        if (mails == null) return;
        Arrays.sort(mails, Comparator.comparing(File::getName).reversed());
        for (File f : mails) {
            String text = read(f);
            response.append("MAIL|").append(section).append("|").append(f.getName()).append("|")
                    .append(extract(text, "From")).append("|").append(extract(text, "To")).append("|")
                    .append(extract(text, "Subject").replace("|", "/")).append("|")
                    .append(extract(text, "SentTime")).append("|")
                    .append(extractBody(text).replace("\n", " ").replace("|", "/")).append("|0|")
                    .append(extract(text, "FromName").replace("|", "/")).append("\n");
        }
    }

    private void appendDbMailRows(StringBuilder response, List<MailDatabase.EmailRow> rows, String section) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (MailDatabase.EmailRow row : rows) {
            String time = row.sentAt != null ? fmt.format(row.sentAt) : "";
            String preview = row.body == null ? "" : row.body.replace("\n", " ").replace("|", "/");
            if (preview.length() > 120) preview = preview.substring(0, 120) + "...";
            response.append("MAIL|").append(section).append("|db_").append(row.id).append("|")
                    .append(safe(row.fromEmail)).append("|").append(safe(row.toEmail)).append("|")
                    .append(safe(row.subject).replace("|", "/")).append("|")
                    .append(time).append("|").append(preview).append("|0|")
                    .append(safe(row.fromName).replace("|", "/")).append("|")
                    .append(safe(row.messageId).replace("|", "/")).append("\n");
        }
    }

    private String formatEmailContent(MailDatabase.EmailRow row) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return "From: " + row.fromEmail + "\n"
                + "FromName: " + safe(row.fromName) + "\n"
                + "FromIP: " + (row.fromIp == null ? "" : row.fromIp) + "\n"
                + "To: " + row.toEmail + "\n"
                + "Subject: " + row.subject + "\n"
                + "SentTime: " + (row.sentAt != null ? fmt.format(row.sentAt) : "") + "\n"
                + "DbId: " + row.id + "\n"
                + "MessageId: " + safe(row.messageId) + "\n"
                + "Gmail: " + (row.gmailDelivered ? "yes" : "no") + "\n"
                + "\n"
                + row.body + "\n";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isValidEmail(String value) {
        return value != null && value.length() <= 254
                && value.matches("(?i)^[^\\s@|]+@[^\\s@|]+\\.[^\\s@|]+$");
    }

    /** Email thường hoặc plus-alias (local+username@domain) -> username nội bộ. */
    private String resolveAccountByEmail(String email) {
        String direct = findLocalUserByEmail(email);
        if (direct != null) return direct;
        if (email == null) return null;
        int at = email.indexOf('@');
        int plus = email.indexOf('+');
        if (plus <= 0 || at <= plus) return null;
        String tag = email.substring(plus + 1, at);
        if (!tag.matches("[A-Za-z0-9._-]{1,32}")) return null;
        try {
            if (database.isReady() && database.accountExists(tag)) return tag;
        } catch (Exception ignored) { }
        if (new File(new File(SERVER_DIR, tag), "new_email.txt").isFile()) return tag;
        return null;
    }

    /** Tìm tài khoản nội bộ có email thật trùng với người nhận. */
    private String findLocalUserByEmail(String email) {
        if (email == null || email.isBlank()) return null;
        if (database.isReady()) {
            try {
                String user = database.findUsernameByEmail(email.trim());
                if (user != null) return user;
            } catch (Exception e) {
                logger.log("[DB] Recipient lookup skipped: " + e.getMessage());
            }
        }
        File root = new File(SERVER_DIR);
        File[] subs = root.listFiles();
        if (subs != null) {
            for (File f : subs) {
                if (!f.isDirectory()) continue;
                File info = new File(f, "new_email.txt");
                if (!info.isFile()) continue;
                try {
                    String stored = extract(read(info), "Email");
                    if (email.equalsIgnoreCase(stored)) return f.getName();
                } catch (IOException ignored) { }
            }
        }
        return null;
    }

    private String listMailbox(String user, String folder, String section) {
        if (!user.matches("[A-Za-z0-9._-]{1,32}")) return "ERROR|Invalid account";
        StringBuilder response = new StringBuilder("OK|" + section + "\n");
        try {
            if (database.isReady()) {
                List<MailDatabase.EmailRow> rows = "SENT".equalsIgnoreCase(section)
                        ? database.listSent(user)
                        : database.listInbox(user);
                appendDbMailRows(response, rows, section);
                return response.toString();
            }
            File account = new File(SERVER_DIR, user);
            if (!new File(account, "new_email.txt").isFile()) return "ERROR|Account not found";
            appendMailRows(response, new File(account, folder), section);
            return response.toString();
        } catch (Exception e) {
            return "ERROR|" + e.getMessage();
        }
    }

    private String extract(String content, String key) {
        for (String line : content.split("\n")) {
            if (line.startsWith(key + ":")) return line.substring(key.length() + 1).trim();
            if (line.startsWith(key)) {
                int idx = line.indexOf(':');
                if (idx > 0) return line.substring(idx + 1).trim();
            }
        }
        return "";
    }

    private String extractBody(String content) {
        int idx = content.indexOf("\n\n");
        if (idx > 0) return content.substring(idx + 2).trim();
        return "";
    }

    private static void write(File f, String data) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(f), "UTF-8"))) {
            bw.write(data);
        }
    }
    private static String read(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String l; while ((l = br.readLine()) != null) sb.append(l).append("\n");
        }
        return sb.toString();
    }
}
