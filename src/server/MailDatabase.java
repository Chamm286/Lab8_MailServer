package server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * MySQL/MariaDB trên localhost — mỗi email gửi đi = 1 bản ghi trong bảng emails.
 * Mở phpMyAdmin để database lab8_mail để bảng emails để xem nội dung thư.
 */
public final class MailDatabase {

    public static final class EmailRow {
        public final long id;
        public final String senderUser;
        public final String fromName;
        public final String fromEmail;
        public final String toEmail;
        public final String subject;
        public final String body;
        public final String fromIp;
        public final String folder;
        public final String recipientUser;
        public final Timestamp sentAt;
        public final boolean gmailDelivered;
        public final String messageId;

        public EmailRow(long id, String senderUser, String fromName, String fromEmail, String toEmail,
                        String subject, String body, String fromIp, String folder,
                        String recipientUser, Timestamp sentAt, boolean gmailDelivered,
                        String messageId) {
            this.id = id;
            this.senderUser = senderUser;
            this.fromName = fromName;
            this.fromEmail = fromEmail;
            this.toEmail = toEmail;
            this.subject = subject;
            this.body = body;
            this.fromIp = fromIp;
            this.folder = folder;
            this.recipientUser = recipientUser;
            this.sentAt = sentAt;
            this.gmailDelivered = gmailDelivered;
            this.messageId = messageId;
        }
    }

    public static final class AccountRow {
        public final long id;
        public final String username;
        public final String email;
        public final String displayName;
        public final Timestamp createdAt;

        public AccountRow(long id, String username, String email, String displayName, Timestamp createdAt) {
            this.id = id;
            this.username = username;
            this.email = email;
            this.displayName = displayName;
            this.createdAt = createdAt;
        }
    }

    private final String url;
    private final String user;
    private final String password;
    private volatile boolean ready;

    public MailDatabase(ServerCore.Logger logger) {
        Properties props = loadProperties();
        this.url = props.getProperty("jdbc.url",
                "jdbc:mysql://127.0.0.1:3307/lab8_mail?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8");
        this.user = props.getProperty("jdbc.user", "root");
        this.password = props.getProperty("jdbc.password", "");
        String driver = props.getProperty("jdbc.driver", "com.mysql.cj.jdbc.Driver");
        try {
            Class.forName(driver);
            ensureSchema();
            ready = true;
            logger.log("[DB] Connected: " + maskUrl(url));
        } catch (Exception e) {
            ready = false;
            logger.log("[DB] Unavailable: " + e.getMessage());
            logger.log("[DB] Check db.properties and start MySQL (XAMPP). Schema: sql/schema.sql");
        }
    }

    public boolean isReady() { return ready; }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public void registerAccount(String username, String pass, String email, String displayName) throws SQLException {
        requireReady();
        String sql = "INSERT INTO accounts (username, password, email, display_name) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE password = VALUES(password), email = VALUES(email), display_name = VALUES(display_name)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, pass);
            ps.setString(3, email);
            ps.setString(4, displayName == null || displayName.isBlank() ? username : displayName);
            ps.executeUpdate();
        }
    }

    /** Cập nhật hồ sơ: tên hiển thị (hiện trên điện thoại) và email thật. */
    public void updateProfile(String username, String email, String displayName) throws SQLException {
        requireReady();
        String sql = "UPDATE accounts SET email = ?, display_name = ? WHERE username = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, displayName == null || displayName.isBlank() ? username : displayName);
            ps.setString(3, username);
            ps.executeUpdate();
        }
    }

    public boolean authenticate(String username, String pass) throws SQLException {
        requireReady();
        String sql = "SELECT password FROM accounts WHERE username = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                return pass.equals(rs.getString(1));
            }
        }
    }

    public boolean accountExists(String username) throws SQLException {
        requireReady();
        String sql = "SELECT 1 FROM accounts WHERE username = ? LIMIT 1";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public String getAccountDisplayName(String username) throws SQLException {
        requireReady();
        String sql = "SELECT display_name FROM accounts WHERE username = ? LIMIT 1";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public String getAccountEmail(String username) throws SQLException {
        requireReady();
        String sql = "SELECT email FROM accounts WHERE username = ? LIMIT 1";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Tìm username nội bộ theo email thật (để thư Gmail cũng hiện trong Hộp thư). */
    public String findUsernameByEmail(String email) throws SQLException {
        requireReady();
        String sql = "SELECT username FROM accounts WHERE LOWER(email) = LOWER(?) LIMIT 1";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Mỗi lần gửi thư = đúng 1 INSERT vào bảng emails. */
    public long insertEmail(String senderUser, String fromEmail, String toEmail,
                            String subject, String body, String fromIp,
                            String folder, String recipientUser, boolean gmailDelivered) throws SQLException {
        return insertEmail(senderUser, null, fromEmail, toEmail, subject, body, fromIp,
                folder, recipientUser, gmailDelivered, null);
    }

    public long insertEmail(String senderUser, String fromEmail, String toEmail,
                            String subject, String body, String fromIp,
                            String folder, String recipientUser, boolean gmailDelivered,
                            String messageId) throws SQLException {
        return insertEmail(senderUser, null, fromEmail, toEmail, subject, body, fromIp,
                folder, recipientUser, gmailDelivered, messageId);
    }

    public long insertEmail(String senderUser, String fromName, String fromEmail, String toEmail,
                            String subject, String body, String fromIp,
                            String folder, String recipientUser, boolean gmailDelivered,
                            String messageId) throws SQLException {
        requireReady();
        String sql = "INSERT INTO emails (sender_user, from_name, from_email, to_email, subject, body, from_ip, folder, recipient_user, gmail_delivered, message_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, senderUser);
            ps.setString(2, fromName == null || fromName.isBlank() ? null : fromName);
            ps.setString(3, fromEmail);
            ps.setString(4, toEmail);
            ps.setString(5, subject);
            ps.setString(6, body);
            ps.setString(7, fromIp);
            ps.setString(8, folder);
            ps.setString(9, recipientUser);
            ps.setBoolean(10, gmailDelivered);
            ps.setString(11, messageId == null || messageId.isBlank() ? null : messageId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        return -1;
    }

    public static final class SentSyncResult {
        public final long id;
        public final boolean alreadySynced;

        public SentSyncResult(long id, boolean alreadySynced) {
            this.id = id;
            this.alreadySynced = alreadySynced;
        }
    }

    /**
     * Đồng bộ thư do chính tài khoản gửi từ điện thoại/Gmail (nằm trong Sent Mail).
     * Nếu thư đã tồn tại dưới dạng INBOX của người nhận thì nâng cấp chính bản ghi đó
     * thành SENT để cả người gửi và người nhận cùng thấy, tránh tạo bản ghi trùng.
     */
    public SentSyncResult syncSentEmail(String senderUser, String fromName, String fromEmail,
                                        String toEmail, String subject, String body, String fromIp,
                                        String recipientUser, String messageId) throws SQLException {
        requireReady();
        if (messageId == null || messageId.isBlank()) {
            return new SentSyncResult(insertEmail(senderUser, fromName, fromEmail, toEmail,
                    subject, body, fromIp, "SENT", recipientUser, true, null), false);
        }

        long existingId = -1;
        String existingSender = null;
        String existingFolder = null;
        String select = "SELECT id, sender_user, folder FROM emails WHERE message_id = ? ORDER BY id ASC LIMIT 1";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(select)) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    existingId = rs.getLong("id");
                    existingSender = rs.getString("sender_user");
                    existingFolder = rs.getString("folder");
                }
            }
        }

        if (existingId > 0) {
            boolean ownSent = senderUser.equalsIgnoreCase(existingSender)
                    && "SENT".equalsIgnoreCase(existingFolder);
            if (ownSent) return new SentSyncResult(existingId, true);

            boolean canLink = existingSender == null
                    || existingSender.isBlank()
                    || "external".equalsIgnoreCase(existingSender)
                    || senderUser.equalsIgnoreCase(existingSender);
            if (!canLink) return new SentSyncResult(-2, false);

            String update = "UPDATE emails SET sender_user=?, from_name=?, from_email=?, to_email=?, "
                    + "folder='SENT', gmail_delivered=1, from_ip=COALESCE(from_ip, ?), "
                    + "recipient_user=COALESCE(recipient_user, ?) WHERE id=?";
            try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(update)) {
                ps.setString(1, senderUser);
                ps.setString(2, fromName == null || fromName.isBlank() ? null : fromName);
                ps.setString(3, fromEmail);
                ps.setString(4, toEmail);
                ps.setString(5, fromIp);
                ps.setString(6, recipientUser);
                ps.setLong(7, existingId);
                ps.executeUpdate();
            }
            return new SentSyncResult(existingId, false);
        }

        return new SentSyncResult(insertEmail(senderUser, fromName, fromEmail, toEmail,
                subject, body, fromIp, "SENT", recipientUser, true, messageId), false);
    }

    /** Thư trả lời đọc từ Gmail: lưu 1 lần theo Message-ID, không nhân đôi khi nhiều client cùng đồng bộ. */
    public long insertIncomingEmail(String recipientUser, String fromEmail, String toEmail,
                                    String subject, String body, String messageId) throws SQLException {
        return insertIncomingEmail(recipientUser, null, fromEmail, toEmail, subject, body, messageId);
    }

    public long insertIncomingEmail(String recipientUser, String fromName, String fromEmail, String toEmail,
                                    String subject, String body, String messageId) throws SQLException {
        requireReady();
        if (messageId != null && !messageId.isBlank() && messageIdExists(messageId)) return -1;
        String sql = "INSERT INTO emails (sender_user, from_name, from_email, to_email, subject, body, from_ip, folder, recipient_user, gmail_delivered, message_id) "
                + "VALUES ('external', ?, ?, ?, ?, ?, NULL, 'INBOX', ?, 1, ?)";
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, fromName == null || fromName.isBlank() ? null : fromName);
            ps.setString(2, fromEmail);
            ps.setString(3, toEmail);
            ps.setString(4, subject);
            ps.setString(5, body);
            ps.setString(6, recipientUser);
            ps.setString(7, messageId == null || messageId.isBlank() ? null : messageId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        return -1;
    }

    public boolean messageIdExists(String messageId) throws SQLException {
        requireReady();
        String sql = "SELECT 1 FROM emails WHERE message_id = ? LIMIT 1";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Hộp thư đến: thư có recipient_user = tài khoản này (1 bản ghi / 1 lần gửi). */
    public List<EmailRow> listInbox(String username) throws SQLException {
        requireReady();
        String sql = "SELECT * FROM emails WHERE recipient_user = ? ORDER BY sent_at DESC, id DESC";
        return queryEmails(sql, username);
    }

    /** Thư đã gửi: mỗi bản ghi sender_user = tài khoản này (không gồm thư nhận vào hộp). */
    public List<EmailRow> listSent(String username) throws SQLException {
        requireReady();
        String sql = "SELECT * FROM emails WHERE sender_user = ? AND folder = 'SENT' ORDER BY sent_at DESC, id DESC";
        return queryEmails(sql, username);
    }

    public List<EmailRow> listAllEmails(int limit) throws SQLException {
        requireReady();
        String sql = "SELECT * FROM emails ORDER BY sent_at DESC, id DESC LIMIT ?";
        List<EmailRow> rows = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(mapEmail(rs));
            }
        }
        return rows;
    }

    public EmailRow getEmail(long id) throws SQLException {
        requireReady();
        String sql = "SELECT * FROM emails WHERE id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapEmail(rs);
            }
        }
        return null;
    }

    public int countEmails() throws SQLException {
        requireReady();
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM emails")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public int countInbox(String username) throws SQLException {
        requireReady();
        String sql = "SELECT COUNT(*) FROM emails WHERE recipient_user = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    public int countSent(String username) throws SQLException {
        requireReady();
        String sql = "SELECT COUNT(*) FROM emails WHERE sender_user = ? AND folder = 'SENT'";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    public int countAccounts() throws SQLException {
        requireReady();
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM accounts")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public List<AccountRow> listAccounts() throws SQLException {
        requireReady();
        List<AccountRow> rows = new ArrayList<>();
        String sql = "SELECT id, username, email, display_name, created_at FROM accounts ORDER BY username";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new AccountRow(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getTimestamp("created_at")));
            }
        }
        return rows;
    }

    public boolean deleteEmail(long id) throws SQLException {
        requireReady();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("DELETE FROM emails WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    private List<EmailRow> queryEmails(String sql, String username) throws SQLException {
        List<EmailRow> rows = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(mapEmail(rs));
            }
        }
        return rows;
    }

    private EmailRow mapEmail(ResultSet rs) throws SQLException {
        return new EmailRow(
                rs.getLong("id"),
                rs.getString("sender_user"),
                rs.getString("from_name"),
                rs.getString("from_email"),
                rs.getString("to_email"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getString("from_ip"),
                rs.getString("folder"),
                rs.getString("recipient_user"),
                rs.getTimestamp("sent_at"),
                rs.getBoolean("gmail_delivered"),
                rs.getString("message_id"));
    }

    private void ensureSchema() throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS lab8_mail CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            st.execute("USE lab8_mail");
            st.execute("CREATE TABLE IF NOT EXISTS accounts ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "username VARCHAR(64) NOT NULL UNIQUE,"
                    + "password VARCHAR(255) NOT NULL,"
                    + "email VARCHAR(128) NOT NULL,"
                    + "display_name VARCHAR(128) NULL,"
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE IF NOT EXISTS emails ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "sender_user VARCHAR(64) NOT NULL,"
                    + "from_name VARCHAR(128) NULL,"
                    + "from_email VARCHAR(255) NOT NULL,"
                    + "to_email VARCHAR(255) NOT NULL,"
                    + "subject VARCHAR(500) NOT NULL,"
                    + "body TEXT NOT NULL,"
                    + "from_ip VARCHAR(64) NULL,"
                    + "folder VARCHAR(16) NOT NULL DEFAULT 'SENT',"
                    + "recipient_user VARCHAR(64) NULL,"
                    + "sent_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "gmail_delivered TINYINT(1) NOT NULL DEFAULT 1,"
                    + "message_id VARCHAR(255) NULL,"
                    + "INDEX idx_sender (sender_user),"
                    + "INDEX idx_recipient (recipient_user),"
                    + "INDEX idx_sent_at (sent_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            if (!columnExists(c, "accounts", "display_name")) {
                st.execute("ALTER TABLE accounts ADD COLUMN display_name VARCHAR(128) NULL");
            }
            if (!columnExists(c, "emails", "from_name")) {
                st.execute("ALTER TABLE emails ADD COLUMN from_name VARCHAR(128) NULL AFTER sender_user");
            }
            if (!columnExists(c, "emails", "message_id")) {
                st.execute("ALTER TABLE emails ADD COLUMN message_id VARCHAR(255) NULL");
            }
            if (!indexExists(c, "emails", "ux_emails_message_id")) {
                try { st.execute("CREATE UNIQUE INDEX ux_emails_message_id ON emails(message_id)"); }
                catch (SQLException duplicate) {
                    // Dữ liệu cũ có thể trùng Message-ID; vẫn chạy được, chỉ mất chống trùng ở mức DB.
                }
            }
        }
    }

    private boolean columnExists(Connection c, String table, String column) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private boolean indexExists(Connection c, String table, String index) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private void requireReady() throws SQLException {
        if (!ready) throw new SQLException("Database is not connected. Check db.properties / MySQL.");
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        File file = new File(System.getProperty("user.dir"), "db.properties");
        if (!file.isFile()) return props;
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (IOException ignored) { }
        return props;
    }

    private static String maskUrl(String jdbcUrl) {
        int q = jdbcUrl.indexOf('?');
        return q > 0 ? jdbcUrl.substring(0, q) : jdbcUrl;
    }
}
