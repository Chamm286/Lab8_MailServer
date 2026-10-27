package client;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * OAuth 2.0 Desktop (loopback + PKCE) chỉ để lấy ảnh đại diện Google thật.
 *
 * Phạm vi: openid email profile. Không xin quyền đọc mail, không thay App Password.
 * Luồng: mở trình duyệt hệ thống -> Google trả code về 127.0.0.1 -> đổi code lấy
 * access_token + refresh_token -> gọi userinfo để lấy email/name/picture.
 */
public final class GoogleOAuthService {
    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_ENDPOINT = "https://openidconnect.googleapis.com/v1/userinfo";
    private static final String SCOPE = "openid email profile";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private GoogleOAuthService() { }

    public static final class Result {
        public final String email;
        public final String name;
        public final String pictureUrl;
        public final String refreshToken;

        Result(String email, String name, String pictureUrl, String refreshToken) {
            this.email = email == null ? "" : email;
            this.name = name == null ? "" : name;
            this.pictureUrl = pictureUrl == null ? "" : pictureUrl;
            this.refreshToken = refreshToken == null ? "" : refreshToken;
        }
    }

    public static final class UserInfo {
        public final String email;
        public final String name;
        public final String pictureUrl;

        UserInfo(String email, String name, String pictureUrl) {
            this.email = email == null ? "" : email;
            this.name = name == null ? "" : name;
            this.pictureUrl = pictureUrl == null ? "" : pictureUrl;
        }
    }

    /**
     * Mở trình duyệt và chờ người dùng đồng ý. Hàm này chạy ở thread nền.
     *
     * @param openBrowser callback mở URL (ClientApp truyền HostServices.showDocument)
     */
    public static Result authorize(String clientId, String clientSecret, String loginHint,
                                   Consumer<String> openBrowser, int timeoutSeconds) throws Exception {
        if (clientId == null || clientId.isBlank()) {
            throw new IOException("Thiếu OAuth Client ID.");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IOException("Thiếu OAuth Client Secret: Google bắt buộc gửi secret khi đổi code "
                    + "(Desktop app luôn có Client Secret).");
        }
        if (openBrowser == null) {
            throw new IOException("Không mở được trình duyệt.");
        }

        String state = randomUrlSafe(32);
        String verifier = randomUrlSafe(64);
        String challenge = base64Url(sha256(verifier));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        // Dùng đúng địa chỉ đang listen. Nếu dùng "localhost", Windows có thể phân giải
        // thành IPv6 ::1 trong khi server chỉ mở IPv4 → trình duyệt không gọi được callback.
        String redirectUri = "http://127.0.0.1:" + port + "/oauth2callback";
        CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();

        server.createContext("/oauth2callback", exchange -> {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            boolean error = params.containsKey("error");
            respondHtml(exchange, error
                    ? "Không thể kết nối Google. Bạn có thể đóng tab này và thử lại trong MailFlow."
                    : "Đã kết nối Google. Bạn có thể đóng tab này và quay lại MailFlow.");
            callback.complete(params);
        });
        server.start();

        try {
            String authUrl = AUTH_ENDPOINT
                    + "?client_id=" + enc(clientId)
                    + "&redirect_uri=" + enc(redirectUri)
                    + "&response_type=code"
                    + "&scope=" + enc(SCOPE)
                    + "&access_type=offline"
                    + "&prompt=consent"
                    + "&include_granted_scopes=true"
                    + "&state=" + enc(state)
                    + "&code_challenge=" + enc(challenge)
                    + "&code_challenge_method=S256"
                    + (loginHint == null || loginHint.isBlank()
                            ? "" : "&login_hint=" + enc(loginHint));

            openBrowser.accept(authUrl);

            Map<String, String> params;
            try {
                params = callback.get(Math.max(30, timeoutSeconds), TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                throw new IOException("Hết thời gian chờ đăng nhập Google. Kiểm tra: OAuth client phải là loại "
                        + "Desktop app, rồi thử lại và bấm Allow trong trình duyệt.");
            }

            if (!state.equals(params.get("state"))) {
                throw new IOException("Phản hồi Google không hợp lệ (state không khớp).");
            }
            if (params.containsKey("error")) {
                throw new IOException(friendlyAuthError(params.getOrDefault("error", ""),
                        params.getOrDefault("error_description", "")));
            }
            String code = params.getOrDefault("code", "");
            if (code.isBlank()) throw new IOException("Google không trả mã đăng nhập.");

            Map<String, String> token = postForm(TOKEN_ENDPOINT, form(
                    "code", code,
                    "client_id", clientId,
                    "client_secret", clientSecret,
                    "redirect_uri", redirectUri,
                    "grant_type", "authorization_code",
                    "code_verifier", verifier));
            String accessToken = token.getOrDefault("access_token", "");
            String refreshToken = token.getOrDefault("refresh_token", "");
            if (accessToken.isBlank()) {
                throw new IOException(tokenError(token, "Không nhận được access token từ Google."));
            }
            if (refreshToken.isBlank()) {
                throw new IOException("Google không trả refresh token. Hãy thử lại và bấm Allow.");
            }

            UserInfo info = fetchUserInfo(accessToken);
            if (info.email.isBlank() || info.pictureUrl.isBlank()) {
                throw new IOException("Google không trả ảnh đại diện cho tài khoản này.");
            }
            return new Result(info.email, info.name, info.pictureUrl, refreshToken);
        } finally {
            server.stop(0);
        }
    }

    /** Làm mới access token từ refresh token rồi lấy lại ảnh hiện tại. */
    public static UserInfo fetchUserInfoWithRefresh(String clientId, String clientSecret,
                                                    String refreshToken) throws Exception {
        if (clientId == null || clientId.isBlank() || refreshToken == null || refreshToken.isBlank()) {
            throw new IOException("Chưa có OAuth Client ID hoặc refresh token.");
        }
        Map<String, String> token = postForm(TOKEN_ENDPOINT, form(
                "client_id", clientId,
                "client_secret", clientSecret,
                "refresh_token", refreshToken,
                "grant_type", "refresh_token"));
        String accessToken = token.getOrDefault("access_token", "");
        if (accessToken.isBlank()) {
            throw new IOException(tokenError(token,
                    "Refresh token Google không còn hiệu lực. Hãy đăng nhập Google lại."));
        }
        return fetchUserInfo(accessToken);
    }

    private static UserInfo fetchUserInfo(String accessToken) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(USERINFO_ENDPOINT))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, String> json = jsonStrings(response.body());
        if (response.statusCode() != 200) {
            throw new IOException(tokenError(json, "Không đọc được thông tin Google (HTTP "
                    + response.statusCode() + ")."));
        }
        return new UserInfo(json.getOrDefault("email", ""),
                json.getOrDefault("name", ""),
                json.getOrDefault("picture", ""));
    }

    /**
     * Ảnh Google trả về thường có hậu tố =s96-c (96 px). Đổi sang kích thước lớn hơn
     * để ảnh đại diện trên app nét, không bị vỡ khi phóng to.
     */
    public static String pictureUrlForSize(String pictureUrl, int size) {
        if (pictureUrl == null || pictureUrl.isBlank()) return "";
        String url = pictureUrl.trim();
        if (url.matches(".*=s\\d+(-c)?$")) {
            return url.replaceFirst("=s\\d+(-c)?$", "=s" + Math.max(48, size) + "-c");
        }
        return url;
    }

    /**
     * Tải ảnh thật từ Google rồi lưu một bản trong thư mục avatars/ của app.
     *
     * Lý do: URL ảnh của Google có thể đổi/không còn tải được sau một thời gian.
     * Lưu bản trên máy giúp ảnh hiện ngay khi mở app và vẫn hiện khi mất mạng.
     * Mỗi lần đồng bộ sẽ ghi đè bản cũ của đúng email đó.
     */
    public static File cachePicture(String email, String pictureUrl) throws Exception {
        String sized = pictureUrlForSize(pictureUrl, 256);
        byte[] bytes = downloadImage(sized);
        if (bytes.length < 16) throw new IOException("Google trả về ảnh rỗng.");

        String safe = (email == null ? "account" : email.trim().toLowerCase(Locale.ROOT))
                .replaceAll("[^a-z0-9._-]", "_");
        File dir = new File(System.getProperty("user.dir"), "avatars");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Không tạo được thư mục avatars.");
        }

        // Xóa bản cũ (có thể khác phần mở rộng) để không tích tụ file rác.
        File[] oldFiles = dir.listFiles((d, name) -> name.startsWith("google-" + safe + "."));
        if (oldFiles != null) {
            for (File old : oldFiles) {
                if (!old.delete()) old.deleteOnExit();
            }
        }

        File target = new File(dir, "google-" + safe + extensionFor(bytes));
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        }
        return target;
    }

    /** Tải ảnh về bộ nhớ, theo redirect và không yêu cầu cookie/token (ảnh profile công khai). */
    private static byte[] downloadImage(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MailFlow/1.0")
                .header("Accept", "image/png,image/jpeg,image/gif;q=0.9,*/*;q=0.5")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Không tải được ảnh Google (HTTP " + response.statusCode() + ").");
        }
        return response.body();
    }

    private static String extensionFor(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return ".png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return ".jpg";
        }
        if (bytes.length >= 4 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return ".gif";
        }
        return ".png";
    }

    private static Map<String, String> postForm(String url, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, String> json = jsonStrings(response.body());
        if (response.statusCode() != 200) {
            throw new IOException(tokenError(json, "Google trả lỗi HTTP " + response.statusCode() + "."));
        }
        return json;
    }

    private static String tokenError(Map<String, String> json, String fallback) {
        String error = json.getOrDefault("error", "").trim();
        String description = json.getOrDefault("error_description", "").trim();
        String hint = tokenHint(error, description);
        if (!hint.isEmpty()) return error.isBlank() ? hint : error + " — " + hint;
        if (!error.isEmpty() && !description.isEmpty()) return error + " — " + description;
        if (!description.isEmpty()) return description;
        if (!error.isEmpty()) return error;
        return fallback;
    }

    /** Dịch các mã lỗi thường gặp của Google thành việc cần làm, thay vì chỉ hiện mã lỗi. */
    private static String tokenHint(String error, String description) {
        String lower = description.toLowerCase(Locale.ROOT);
        return switch (error) {
            case "invalid_client" -> "Client ID hoặc Client Secret không đúng. Kiểm tra lại OAuth Client ID loại Desktop app.";
            case "invalid_grant" -> lower.contains("expired") || lower.contains("revoked")
                    ? "Refresh token Google đã hết hạn hoặc bị thu hồi. Hãy đăng nhập Google lại."
                    : "Mã đăng nhập không còn hiệu lực. Hãy đăng nhập Google lại.";
            case "invalid_request" -> lower.contains("client_secret")
                    ? "Thiếu Client Secret: Desktop app bắt buộc phải dán cả Client Secret." : "";
            case "redirect_uri_mismatch" -> "OAuth Client ID phải là loại Desktop app (không phải Web application).";
            case "access_denied" -> "Bạn đã hủy đăng nhập Google hoặc ứng dụng chưa được cấp quyền.";
            default -> "";
        };
    }

    /** Lỗi Google trả về qua callback (trước khi đổi code) — cũng cần hướng dẫn cụ thể. */
    private static String friendlyAuthError(String error, String description) {
        return switch (error) {
            case "access_denied" -> "Bạn đã hủy đăng nhập Google, hoặc email này chưa có trong Test users "
                    + "của OAuth consent screen.";
            case "redirect_uri_mismatch" -> "OAuth Client ID phải là loại Desktop app. Hãy tạo lại đúng loại rồi thử lại.";
            case "invalid_client" -> "Client ID hoặc Client Secret không đúng.";
            case "invalid_scope" -> "Ứng dụng Google chưa được cấp quyền openid/email/profile.";
            case "org_internal" -> "OAuth app đang giới hạn nội bộ. Thêm email này vào Test users hoặc chọn External.";
            case "admin_policy_enforced" -> "Quản trị viên Google Workspace đã chặn ứng dụng này.";
            default -> description.isBlank() ? "Google từ chối kết nối (" + error + ")."
                    : "Google từ chối kết nối: " + description;
        };
    }

    private static String form(String... pairs) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = pairs[i];
            String value = pairs[i + 1] == null ? "" : pairs[i + 1];
            if ("client_secret".equals(key) && value.isBlank()) continue;
            if (out.length() > 0) out.append('&');
            out.append(enc(key)).append('=').append(enc(value));
        }
        return out.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            String value = eq < 0 ? "" : part.substring(eq + 1);
            out.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void respondHtml(com.sun.net.httpserver.HttpExchange exchange, String message) throws IOException {
        byte[] bytes = ("<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<title>MailFlow · Google</title></head>"
                + "<body style=\"font-family:Segoe UI,Arial,sans-serif;background:#F8FAFC;color:#0F172A;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0\">"
                + "<div style=\"max-width:520px;padding:28px 32px;background:white;border:1px solid #E2E8F0;"
                + "border-radius:16px;box-shadow:0 12px 30px rgba(15,23,42,.08);text-align:center\">"
                + "<div style=\"font-size:13px;font-weight:700;letter-spacing:.12em;color:#2563EB\">MAILFLOW</div>"
                + "<h2 style=\"margin:12px 0 8px;font-size:20px\">Google OAuth</h2>"
                + "<p style=\"margin:0;color:#475569;line-height:1.55\">" + escapeHtml(message) + "</p>"
                + "</div></body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String randomUrlSafe(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return base64Url(data);
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
    }

    /** Đọc các cặp key/value dạng chuỗi ở cấp ngoài của JSON nhỏ từ Google. */
    private static Map<String, String> jsonStrings(String json) {
        Map<String, String> out = new HashMap<>();
        if (json == null) return out;
        int index = 0;
        while (index < json.length()) {
            int keyStart = json.indexOf('"', index);
            if (keyStart < 0) break;
            int keyEnd = stringEnd(json, keyStart + 1);
            if (keyEnd < 0) break;
            String key = unescape(json.substring(keyStart + 1, keyEnd));
            int colon = json.indexOf(':', keyEnd + 1);
            if (colon < 0) break;
            int valueStart = skipWhitespace(json, colon + 1);
            if (valueStart >= json.length()) break;
            if (json.charAt(valueStart) == '"') {
                int valueEnd = stringEnd(json, valueStart + 1);
                if (valueEnd < 0) break;
                out.put(key, unescape(json.substring(valueStart + 1, valueEnd)));
                index = valueEnd + 1;
            } else {
                index = valueStart + 1;
            }
        }
        return out;
    }

    private static int skipWhitespace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        return index;
    }

    private static int stringEnd(String text, int from) {
        boolean escaped = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        try {
                            out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ignored) { }
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }
}
