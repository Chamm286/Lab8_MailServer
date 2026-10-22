package client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Lưu cấu hình gửi thư trên máy client (không gửi App Password lên server).
 *
 * Mỗi tài khoản đăng nhập dùng chính email thật của tài khoản đó để gửi/nhận.
 * Google App Password là riêng cho từng tài khoản Google/Gmail, nên App Password
 * được lưu theo từng email trong file cấu hình cục bộ.
 *
 * Hai chế độ:
 *   GMAIL — gửi SMTP thật (Internet) rồi lưu qua UDP server / MySQL
 *   LAN   — chỉ gửi nội bộ Client ↔ Server trên cùng máy hoặc cùng WiFi
 */
public final class MailConfig {
    public static final String MODE_GMAIL = "GMAIL";
    public static final String MODE_LAN = "LAN";

    /** Dự phòng trước khi xác định được tài khoản; không áp dụng chéo giữa các email. */
    public static final String ENV_APP_PASSWORD = "MAILFLOW_GMAIL_APP_PASSWORD";
    public static final String ENV_DEFAULT_TO = "MAILFLOW_DEFAULT_TO";

    private static final String CONFIG_PROPERTY = "mailflow.config";
    private static final String DEFAULT_CONFIG_NAME = "client-mail.properties";
    private static final String PASSWORD_PREFIX = "appPassword.";
    private static final String AVATAR_PREFIX = "avatar.";
    private static final String OAUTH_CLIENT_ID_KEY = "oauth.clientId";
    private static final String OAUTH_CLIENT_SECRET_KEY = "oauth.clientSecret";
    private static final String OAUTH_REFRESH_PREFIX = "oauth.refreshToken.";
    private static final String GOOGLE_PHOTO_PREFIX = "google.photo.";

    private static final File SHARED_FILE = new File(System.getProperty("user.dir"), DEFAULT_CONFIG_NAME);
    private static final File FILE = new File(System.getProperty("user.dir"),
            System.getProperty(CONFIG_PROPERTY, DEFAULT_CONFIG_NAME));

    public static final String EMAIL_PATTERN = "(?i)^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";

    /** Email của tài khoản đang đăng nhập (lấy từ server). */
    private String activeEmail = "";
    private String sendMode = MODE_GMAIL;
    private String defaultTo = "tramntb.24it@vku.udn.vn";

    /** App Password riêng cho từng email; key là email viết thường. */
    private final Properties accountPasswords = new Properties();

    /** URL ảnh đại diện riêng cho từng email (tùy chọn). */
    private final Properties accountAvatars = new Properties();

    /** OAuth dùng chung cho app (Desktop client); refresh token và ảnh lưu theo từng email. */
    private String oauthClientId = "";
    private String oauthClientSecret = "";
    private final Properties accountRefreshTokens = new Properties();
    private final Properties accountGooglePhotos = new Properties();

    /** File cấu hình đang dùng (mỗi cửa sổ client có thể có file riêng). */
    public static String getConfigFileName() {
        return FILE.getName();
    }

    /** Gọi ngay sau khi đăng nhập để mọi thao tác Gmail dùng đúng email tài khoản. */
    public void activate(String email) {
        activeEmail = email == null ? "" : email.trim();
    }

    public String getActiveEmail() {
        return activeEmail;
    }

    /** Email dùng để gửi/nhận Gmail — chính là email thật của tài khoản đang đăng nhập. */
    public String getGmailAddress() {
        return activeEmail;
    }

    /** Giữ tương thích với code cũ: đổi tài khoản đang dùng. */
    public void setGmailAddress(String value) {
        activate(value);
    }

    public String getGmailAppPassword() {
        if (isBlank(activeEmail)) {
            String env = System.getenv(ENV_APP_PASSWORD);
            return isBlank(env) ? "" : normalizeAppPassword(env);
        }
        // App Password phải là của chính tài khoản đang đăng nhập — không dùng chéo.
        return getAppPassword(activeEmail);
    }

    public String getAppPassword(String email) {
        if (isBlank(email)) return "";
        return accountPasswords.getProperty(passwordKey(email), "");
    }

    /** Lưu App Password cho đúng email đang đăng nhập. */
    public void setGmailAppPassword(String value) {
        setAppPassword(activeEmail, value);
    }

    public void setAppPassword(String email, String value) {
        if (isBlank(email)) return;
        String key = passwordKey(email);
        String password = normalizeAppPassword(value);
        if (password.isEmpty()) accountPasswords.remove(key);
        else accountPasswords.setProperty(key, password);
    }

    public boolean hasPasswordFor(String email) {
        return !isBlank(getAppPassword(email));
    }

    /** Ảnh đại diện đã cấu hình cho email; rỗng nghĩa là dùng Gravatar/chữ cái đầu. */
    public String getAvatarUrl(String email) {
        if (isBlank(email)) return "";
        return accountAvatars.getProperty(avatarKey(email), "");
    }

    public String getAvatarUrl() {
        return getAvatarUrl(activeEmail);
    }

    public void setAvatarUrl(String email, String value) {
        if (isBlank(email)) return;
        String key = avatarKey(email);
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) accountAvatars.remove(key);
        else accountAvatars.setProperty(key, url);
    }

    public void setAvatarUrl(String value) {
        setAvatarUrl(activeEmail, value);
    }

    // ── Google OAuth: lấy ảnh đại diện thật (openid/email/profile) ──

    public String getOAuthClientId() {
        return oauthClientId;
    }

    public void setOAuthClientId(String value) {
        oauthClientId = value == null ? "" : value.trim();
    }

    public String getOAuthClientSecret() {
        return oauthClientSecret;
    }

    public void setOAuthClientSecret(String value) {
        oauthClientSecret = value == null ? "" : value.trim();
    }

    public String getOAuthRefreshToken(String email) {
        if (isBlank(email)) return "";
        return accountRefreshTokens.getProperty(refreshTokenKey(email), "");
    }

    public void setOAuthRefreshToken(String email, String value) {
        if (isBlank(email)) return;
        String key = refreshTokenKey(email);
        String token = value == null ? "" : value.trim();
        if (token.isEmpty()) accountRefreshTokens.remove(key);
        else accountRefreshTokens.setProperty(key, token);
    }

    public boolean hasGoogleOAuth(String email) {
        return !isBlank(getOAuthClientId()) && !isBlank(getOAuthRefreshToken(email));
    }

    public String getGooglePhotoUrl(String email) {
        if (isBlank(email)) return "";
        return accountGooglePhotos.getProperty(googlePhotoKey(email), "");
    }

    public void setGooglePhotoUrl(String email, String value) {
        if (isBlank(email)) return;
        String key = googlePhotoKey(email);
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) accountGooglePhotos.remove(key);
        else accountGooglePhotos.setProperty(key, url);
    }

    /**
     * Lưu ảnh Google mới. Chỉ thay avatar đang dùng nếu avatar đó đang trống
     * hoặc vẫn là ảnh Google lần trước — không ghi đè ảnh người dùng tự chọn.
     */
    public void applyGooglePhoto(String email, String photoUrl) {
        if (isBlank(email) || isBlank(photoUrl)) return;
        String previousGoogle = getGooglePhotoUrl(email);
        String currentAvatar = getAvatarUrl(email);
        setGooglePhotoUrl(email, photoUrl);
        if (isBlank(currentAvatar) || currentAvatar.equals(previousGoogle)) {
            setAvatarUrl(email, photoUrl);
        }
    }

    /** Sau khi đăng nhập OAuth: luôn dùng ảnh Google vừa lấy. */
    public void connectGooglePhoto(String email, String photoUrl, String refreshToken) {
        if (isBlank(email)) return;
        setOAuthRefreshToken(email, refreshToken);
        setGooglePhotoUrl(email, photoUrl);
        setAvatarUrl(email, photoUrl);
    }

    /** Ngắt OAuth và bỏ ảnh Google; chỉ xóa avatar nếu avatar đó là ảnh Google. */
    public void clearGoogleOAuth(String email) {
        if (isBlank(email)) return;
        String googlePhoto = getGooglePhotoUrl(email);
        setOAuthRefreshToken(email, "");
        setGooglePhotoUrl(email, "");
        if (!isBlank(googlePhoto) && googlePhoto.equals(getAvatarUrl(email))) {
            setAvatarUrl(email, "");
        }
    }

    public String getSendMode() { return sendMode; }

    public String getDefaultTo() {
        if (isBlank(defaultTo)) {
            String env = System.getenv(ENV_DEFAULT_TO);
            if (!isBlank(env)) return env.trim();
        }
        return defaultTo;
    }

    public void setSendMode(String v) {
        sendMode = MODE_LAN.equalsIgnoreCase(v) ? MODE_LAN : MODE_GMAIL;
    }

    public void setDefaultTo(String v) { defaultTo = v == null ? "" : v.trim(); }

    /** Google chỉ chấp nhận App Password 16 ký tự (có thể nhập kèm dấu cách). */
    public static String normalizeAppPassword(String value) {
        return value == null ? "" : value.replaceAll("\\s", "").trim();
    }

    public static boolean isValidAppPassword(String value) {
        String p = normalizeAppPassword(value);
        return p.length() == 16 && p.matches("[A-Za-z0-9]+");
    }

    /** True khi email tài khoản hợp lệ và đã có App Password cho chính email đó. */
    public boolean hasGmailCredentials() {
        return activeEmail.matches(EMAIL_PATTERN) && !getGmailAppPassword().isEmpty();
    }

    /** True khi App Password của tài khoản hiện tại đúng định dạng 16 ký tự. */
    public boolean hasValidGmailCredentials() {
        return activeEmail.matches(EMAIL_PATTERN)
                && isValidAppPassword(getGmailAppPassword());
    }

    /** Cảnh báo khi email không phải Gmail/Google Workspace — SMTP Gmail sẽ không gửi thay được. */
    public static boolean isGoogleMailbox(String email) {
        if (email == null) return false;
        String value = email.trim().toLowerCase(Locale.ROOT);
        return value.endsWith("@gmail.com") || value.endsWith("@googlemail.com")
                || value.endsWith("@vku.udn.vn");
    }

    private static String passwordKey(String email) {
        return PASSWORD_PREFIX + email.trim().toLowerCase(Locale.ROOT);
    }

    private static String avatarKey(String email) {
        return AVATAR_PREFIX + email.trim().toLowerCase(Locale.ROOT);
    }

    private static String refreshTokenKey(String email) {
        return OAUTH_REFRESH_PREFIX + email.trim().toLowerCase(Locale.ROOT);
    }

    private static String googlePhotoKey(String email) {
        return GOOGLE_PHOTO_PREFIX + email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public boolean isGmailMode() {
        return MODE_GMAIL.equalsIgnoreCase(sendMode);
    }

    public void load() {
        File source = FILE.isFile() ? FILE
                : (!FILE.equals(SHARED_FILE) && SHARED_FILE.isFile() ? SHARED_FILE : null);
        if (source == null) {
            save();
            return;
        }

        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException ignored) { }

        // Đọc App Password theo từng tài khoản.
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(PASSWORD_PREFIX)) continue;
            String email = key.substring(PASSWORD_PREFIX.length()).trim();
            String password = p.getProperty(key, "");
            if (!isBlank(email) && !isBlank(password)) setAppPassword(email, password);
        }

        // Đọc ảnh đại diện riêng của từng tài khoản.
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(AVATAR_PREFIX)) continue;
            String email = key.substring(AVATAR_PREFIX.length()).trim();
            String avatar = p.getProperty(key, "");
            if (!isBlank(email) && !isBlank(avatar)) setAvatarUrl(email, avatar);
        }

        // OAuth client dùng chung; refresh token và ảnh Google theo từng email.
        oauthClientId = p.getProperty(OAUTH_CLIENT_ID_KEY, "").trim();
        oauthClientSecret = p.getProperty(OAUTH_CLIENT_SECRET_KEY, "").trim();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(OAUTH_REFRESH_PREFIX)) {
                String email = key.substring(OAUTH_REFRESH_PREFIX.length()).trim();
                String token = p.getProperty(key, "");
                if (!isBlank(email) && !isBlank(token)) setOAuthRefreshToken(email, token);
            } else if (key.startsWith(GOOGLE_PHOTO_PREFIX)) {
                String email = key.substring(GOOGLE_PHOTO_PREFIX.length()).trim();
                String photo = p.getProperty(key, "");
                if (!isBlank(email) && !isBlank(photo)) setGooglePhotoUrl(email, photo);
            }
        }

        // Tương thích file cấu hình cũ: gmail.address + gmail.appPassword.
        String legacyAddress = p.getProperty("gmail.address", "").trim();
        String legacyPassword = p.getProperty("gmail.appPassword", "");
        if (!isBlank(legacyAddress) && !isBlank(legacyPassword)) {
            setAppPassword(legacyAddress, legacyPassword);
        }

        activeEmail = p.getProperty("active.email", legacyAddress).trim();
        setSendMode(p.getProperty("send.mode", MODE_GMAIL));
        setDefaultTo(p.getProperty("default.to", defaultTo));

        // Cửa sổ thứ hai được "thừa kế" App Password từ file dùng chung.
        // Đồng thời ghi lại theo định dạng mới (appPassword.<email>).
        save();
    }

    public synchronized void save() {
        Properties p = new Properties();
        p.setProperty("active.email", activeEmail);
        p.setProperty("send.mode", sendMode);
        p.setProperty("default.to", defaultTo);
        if (!isBlank(oauthClientId)) p.setProperty(OAUTH_CLIENT_ID_KEY, oauthClientId);
        if (!isBlank(oauthClientSecret)) p.setProperty(OAUTH_CLIENT_SECRET_KEY, oauthClientSecret);

        List<String> keys = new ArrayList<>(accountPasswords.stringPropertyNames());
        Collections.sort(keys);
        for (String key : keys) p.setProperty(key, accountPasswords.getProperty(key, ""));

        keys = new ArrayList<>(accountAvatars.stringPropertyNames());
        Collections.sort(keys);
        for (String key : keys) p.setProperty(key, accountAvatars.getProperty(key, ""));

        keys = new ArrayList<>(accountRefreshTokens.stringPropertyNames());
        Collections.sort(keys);
        for (String key : keys) p.setProperty(key, accountRefreshTokens.getProperty(key, ""));

        keys = new ArrayList<>(accountGooglePhotos.stringPropertyNames());
        Collections.sort(keys);
        for (String key : keys) p.setProperty(key, accountGooglePhotos.getProperty(key, ""));

        // Giữ tương thích với client bản cũ: ghi thêm cặp gmail.address/appPassword của tài khoản hiện tại.
        String currentPassword = getGmailAppPassword();
        if (!isBlank(activeEmail) && !isBlank(currentPassword)) {
            p.setProperty("gmail.address", activeEmail);
            p.setProperty("gmail.appPassword", currentPassword);
        }

        File parent = FILE.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(parent, FILE.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            p.store(w, "Lab8 Mail Client — Google credentials stay on this PC only");
        } catch (IOException ignored) { return; }
        try {
            Files.move(tmp.toPath(), FILE.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException moveError) {
            try { Files.move(tmp.toPath(), FILE.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ignored) { }
        }
    }
}
