package client;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.HeaderTerm;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Đọc hộp thư Gmail qua IMAP SSL (imap.gmail.com:993).
 * Hỗ trợ cả nội dung HTML, ảnh inline và file đính kèm để client hiển thị như một mail client thật.
 */
public final class GmailImapService {

    public static final class AttachmentInfo {
        public String fileName = "";
        public String contentType = "";
        public String contentId = "";
        public byte[] data = new byte[0];

        public boolean isImage() {
            return contentType != null && contentType.toLowerCase().startsWith("image/");
        }

        public boolean isInlineImage() {
            return isImage() && contentId != null && !contentId.isBlank();
        }
    }

    public static final class MessageInfo {
        public long uid;
        public String fromName = "";
        public String fromAddress = "";
        public String to = "";
        public List<String> toAddresses = new ArrayList<>();
        public String subject = "";
        public String body = "";
        public String htmlBody = "";
        public String messageId = "";
        public String inReplyTo = "";
        public Date sentDate;
        public List<AttachmentInfo> attachments = new ArrayList<>();
    }

    public static final class SyncSnapshot {
        public final List<MessageInfo> inbox;
        public final List<MessageInfo> sent;

        public SyncSnapshot(List<MessageInfo> inbox, List<MessageInfo> sent) {
            this.inbox = inbox;
            this.sent = sent;
        }
    }

    private static final long MAX_ATTACHMENT_BYTES = 22L * 1024 * 1024;

    /**
     * Trạng thái đọc tăng dần theo UID. Lần đầu đọc N thư mới nhất, các lần sau chỉ đọc
     * thư có UID mới nên đồng bộ thư trả lời từ điện thoại gần như tức thì.
     */
    private static final class FolderCursor {
        long uidValidity;
        long lastUid;
        long lastFullScanAt;

        FolderCursor(long uidValidity, long lastUid, long lastFullScanAt) {
            this.uidValidity = uidValidity;
            this.lastUid = lastUid;
            this.lastFullScanAt = lastFullScanAt;
        }
    }

    private static final Map<String, FolderCursor> CURSORS = new ConcurrentHashMap<>();
    private static final Map<String, String> SENT_FOLDER_NAMES = new ConcurrentHashMap<>();
    private static final long FULL_RESCAN_INTERVAL_MS = 60L * 60 * 1000;

    private GmailImapService() { }

    /** Đọc cả INBOX và Sent Mail trong cùng một kết nối IMAP để giảm số lần đăng nhập Gmail. */
    public static SyncSnapshot fetchRecentBoth(String email, String appPassword, int limit) throws Exception {
        int max = Math.max(1, Math.min(limit, 50));
        Session session = Session.getInstance(imapProperties(), null);
        try (Store store = session.getStore("imaps")) {
            store.connect("imap.gmail.com", email, normalizePassword(appPassword));
            List<MessageInfo> inbox = readFolderIncremental(store.getFolder("INBOX"), max, false, email);
            Folder sentFolder = findSentFolder(store);
            List<MessageInfo> sent = sentFolder == null
                    ? new ArrayList<>()
                    : readFolderIncremental(sentFolder, max, false, email);
            return new SyncSnapshot(inbox, sent);
        }
    }

    public static List<MessageInfo> fetchRecent(String email, String appPassword, int limit) throws Exception {
        int max = Math.max(1, Math.min(limit, 50));
        Session session = Session.getInstance(imapProperties(), null);
        try (Store store = session.getStore("imaps")) {
            store.connect("imap.gmail.com", email, normalizePassword(appPassword));
            return readFolder(store.getFolder("INBOX"), max, false);
        }
    }

    /** Đọc thư mới nhất kèm HTML/ảnh đính kèm để hiển thị đầy đủ trong reader. */
    public static List<MessageInfo> fetchRecentRich(String email, String appPassword, int limit) throws Exception {
        int max = Math.max(1, Math.min(limit, 50));
        Session session = Session.getInstance(imapProperties(), null);
        try (Store store = session.getStore("imaps")) {
            store.connect("imap.gmail.com", email, normalizePassword(appPassword));
            return readFolder(store.getFolder("INBOX"), max, true);
        }
    }

    /**
     * Đọc thư do chính tài khoản này gửi từ điện thoại/Gmail.
     * Thư gửi từ điện thoại dùng cùng tài khoản không nằm trong INBOX mà nằm ở Sent Mail.
     */
    public static List<MessageInfo> fetchRecentSent(String email, String appPassword, int limit) throws Exception {
        int max = Math.max(1, Math.min(limit, 50));
        Session session = Session.getInstance(imapProperties(), null);
        try (Store store = session.getStore("imaps")) {
            store.connect("imap.gmail.com", email, normalizePassword(appPassword));
            Folder folder = findSentFolder(store);
            return folder == null ? new ArrayList<>() : readFolder(folder, max, false);
        }
    }

    /**
     * Tìm lại đúng thư theo Message-ID để lấy HTML/ảnh đính kèm khi người dùng mở thư đã đồng bộ.
     * sentFolder = true khi đang mở mục "Thư đã gửi", false khi mở hộp thư đến.
     */
    public static MessageInfo fetchByMessageId(String email, String appPassword, String messageId,
                                               boolean sentFolder) throws Exception {
        if (messageId == null || messageId.isBlank()) return null;
        Session session = Session.getInstance(imapProperties(), null);
        try (Store store = session.getStore("imaps")) {
            store.connect("imap.gmail.com", email, normalizePassword(appPassword));
            Folder folder = sentFolder ? findSentFolder(store) : store.getFolder("INBOX");
            if (folder == null) return null;
            folder.open(Folder.READ_ONLY);
            try {
                Message found = findMessage(folder, messageId);
                if (found == null) return null;
                UIDFolder uidFolder = folder instanceof UIDFolder ? (UIDFolder) folder : null;
                return readMessage(found, uidFolder, true, true);
            } finally {
                try { folder.close(false); } catch (Exception ignored) { }
            }
        }
    }

    private static Message findMessage(Folder folder, String messageId) throws Exception {
        try {
            Message[] matches = folder.search(new HeaderTerm("Message-ID", messageId));
            if (matches.length > 0) return matches[matches.length - 1];
        } catch (Exception ignored) { }

        int total = folder.getMessageCount();
        if (total <= 0) return null;
        int start = Math.max(1, total - 80 + 1);
        Message[] recent = folder.getMessages(start, total);
        for (int i = recent.length - 1; i >= 0; i--) {
            String id = firstHeader(recent[i], "Message-ID");
            if (id.equalsIgnoreCase(messageId)
                    || (!id.isBlank() && id.contains(messageId))
                    || (!id.isBlank() && messageId.contains(id))) {
                return recent[i];
            }
        }
        return null;
    }

    private static Properties imapProperties() {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", "imap.gmail.com");
        props.put("mail.imaps.port", "993");
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.connectiontimeout", "12000");
        props.put("mail.imaps.timeout", "30000");
        props.put("mail.imaps.partialfetch", "false");
        return props;
    }

    private static List<MessageInfo> readFolderIncremental(Folder folder, int max, boolean rich,
                                                           String email) throws Exception {
        List<MessageInfo> result = new ArrayList<>();
        String key = email.toLowerCase(Locale.ROOT) + "|" + folder.getFullName();
        folder.open(Folder.READ_ONLY);
        try {
            UIDFolder uidFolder = folder instanceof UIDFolder ? (UIDFolder) folder : null;
            long validity = uidFolder == null ? -1 : uidFolder.getUIDValidity();
            FolderCursor cursor = CURSORS.get(key);
            long now = System.currentTimeMillis();
            boolean fullScan = uidFolder == null || cursor == null
                    || cursor.uidValidity != validity
                    || now - cursor.lastFullScanAt >= FULL_RESCAN_INTERVAL_MS;

            Message[] messages;
            if (fullScan) {
                int total = folder.getMessageCount();
                if (total <= 0) {
                    if (uidFolder != null) {
                        CURSORS.put(key, new FolderCursor(validity, 0, now));
                    }
                    return result;
                }
                int start = Math.max(1, total - max + 1);
                messages = folder.getMessages(start, total);
            } else {
                long startUid = cursor.lastUid + 1;
                long lastUid = uidFolder.getUIDNext() - 1;
                if (startUid > lastUid) return result;
                messages = uidFolder.getMessagesByUID(startUid, lastUid);
            }

            if (messages.length == 0) return result;

            // Gom header của toàn bộ thư mới vào 1 lượt FETCH thay vì mỗi thư một lượt.
            try {
                FetchProfile profile = new FetchProfile();
                profile.add(FetchProfile.Item.ENVELOPE);
                profile.add(FetchProfile.Item.FLAGS);
                profile.add("Message-ID");
                profile.add("In-Reply-To");
                folder.fetch(messages, profile);
            } catch (Exception ignored) { }

            long newestUid = cursor == null ? 0 : cursor.lastUid;
            for (int i = messages.length - 1; i >= 0; i--) {
                // Lần quét đầu/quét lại toàn bộ chỉ lấy header cho nhanh.
                // Thư mới (UID tăng dần) mới lấy thêm text preview, còn nội dung đầy đủ
                // được tải theo Message-ID khi người dùng mở thư.
                int size = -1;
                try { size = messages[i].getSize(); } catch (Exception ignored) { }
                // Khi quét đầu tiên, chỉ lấy nội dung của thư mới nhất (thường là thư điện thoại
                // vừa trả lời); các thư cũ chỉ cần header. Các thư mới tiếp theo luôn lấy nội dung.
                boolean newestOnFullScan = fullScan && i == messages.length - 1;
                boolean withBody = (!fullScan || newestOnFullScan)
                        && (size <= 0 || size <= 2 * 1024 * 1024);
                MessageInfo info = readMessage(messages[i], uidFolder, rich, withBody);
                if (info != null) {
                    result.add(info);
                    if (uidFolder != null) newestUid = Math.max(newestUid, info.uid);
                }
            }
            if (uidFolder != null) {
                CURSORS.put(key, new FolderCursor(validity, newestUid,
                        fullScan ? now : cursor.lastFullScanAt));
            }
        } finally {
            try { folder.close(false); } catch (Exception ignored) { }
        }
        return result;
    }

    private static List<MessageInfo> readFolder(Folder folder, int max, boolean rich) throws Exception {
        List<MessageInfo> result = new ArrayList<>();
        folder.open(Folder.READ_ONLY);
        try {
            int total = folder.getMessageCount();
            if (total <= 0) return result;
            int start = Math.max(1, total - max + 1);
            Message[] messages = folder.getMessages(start, total);
            UIDFolder uidFolder = folder instanceof UIDFolder ? (UIDFolder) folder : null;
            for (int i = messages.length - 1; i >= 0; i--) {
                MessageInfo info = readMessage(messages[i], uidFolder, rich, true);
                if (info != null) result.add(info);
            }
        } finally {
            try { folder.close(false); } catch (Exception ignored) { }
        }
        return result;
    }

    private static MessageInfo readMessage(Message message, UIDFolder uidFolder, boolean rich,
                                           boolean includeBody) throws Exception {
        MessageInfo info = new MessageInfo();
        if (uidFolder != null) info.uid = uidFolder.getUID(message);
        info.messageId = firstHeader(message, "Message-ID");
        info.inReplyTo = firstHeader(message, "In-Reply-To");
        info.subject = safe(message.getSubject());
        info.sentDate = message.getSentDate();
        info.to = recipients(message, Message.RecipientType.TO, info.toAddresses);
        Address[] from = message.getFrom();
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress ia) {
            info.fromAddress = safe(ia.getAddress());
            info.fromName = safe(ia.getPersonal());
        }
        if (includeBody) info.body = extractText(message);
        if (rich) extractRich(message, info);
        return info;
    }

    /** Gmail có thể đổi tên hiển thị của Sent Mail theo ngôn ngữ. */
    private static Folder findSentFolder(Store store) throws MessagingException {
        String cached = SENT_FOLDER_NAMES.get(storeKey(store));
        if (cached != null) {
            try {
                Folder folder = store.getFolder(cached);
                if (folder.exists()) return folder;
            } catch (MessagingException ignored) { }
            SENT_FOLDER_NAMES.remove(storeKey(store));
        }

        for (String name : new String[]{
                "[Gmail]/Sent Mail", "[Google Mail]/Sent Mail", "Sent Mail", "Sent"}) {
            try {
                Folder folder = store.getFolder(name);
                if (folder.exists()) {
                    SENT_FOLDER_NAMES.put(storeKey(store), name);
                    return folder;
                }
            } catch (MessagingException ignored) { }
        }

        try {
            Folder[] folders = store.getDefaultFolder().list("*");
            for (Folder folder : folders) {
                String name = folder.getName() == null ? "" : folder.getName().toLowerCase(Locale.ROOT);
                String fullName = folder.getFullName() == null ? "" : folder.getFullName().toLowerCase(Locale.ROOT);
                if (name.contains("sent") || fullName.contains("sent")
                        || name.contains("đã gửi") || fullName.contains("đã gửi")) {
                    SENT_FOLDER_NAMES.put(storeKey(store), folder.getFullName());
                    return folder;
                }
            }
        } catch (MessagingException ignored) { }

        return null;
    }

    private static String storeKey(Store store) {
        String user = "";
        try { user = store.getURLName() == null ? "" : safe(store.getURLName().getUsername()); }
        catch (Exception ignored) { }
        return user.toLowerCase(Locale.ROOT);
    }

    private static String recipients(Message message, Message.RecipientType type, List<String> addresses) throws Exception {
        Address[] list = message.getRecipients(type);
        if (list == null) return "";
        StringBuilder joined = new StringBuilder();
        for (Address address : list) {
            String value = address instanceof InternetAddress ia ? safe(ia.getAddress()) : safe(address.toString());
            if (value.isEmpty()) continue;
            addresses.add(value);
            if (joined.length() > 0) joined.append(", ");
            joined.append(value);
        }
        return joined.toString();
    }

    private static String firstHeader(Message message, String name) throws Exception {
        return headerValue(message, name);
    }

    private static String headerValue(Part part, String name) throws MessagingException {
        String[] values = part.getHeader(name);
        return values != null && values.length > 0 ? safe(values[0]) : "";
    }

    private static String extractText(Part part) throws Exception {
        if (part == null) return "";
        try {
            if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) return "";
            if (part.isMimeType("text/plain")) return safe(part.getContent());
            if (part.isMimeType("text/html")) return htmlToText(safe(part.getContent()));
            if (part.isMimeType("multipart/*")) {
                Multipart multipart = (Multipart) part.getContent();
                String html = "";
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart child = multipart.getBodyPart(i);
                    if (Part.ATTACHMENT.equalsIgnoreCase(child.getDisposition())) continue;
                    String text = extractText(child);
                    if (text.isBlank()) continue;
                    if (child.isMimeType("text/html")) {
                        html = text;
                    } else {
                        // multipart/alternative hoặc multipart/related lồng nhau:
                        // trả về phần text/plain đầu tiên tìm thấy.
                        return text;
                    }
                }
                return html;
            }
            if (part.isMimeType("message/rfc822")) return extractText((Part) part.getContent());
        } catch (Exception ignored) { }
        return "";
    }

    private static void extractRich(Part part, MessageInfo info) {
        if (part == null) return;
        try {
            String disposition = part.getDisposition();
            String fileName = part.getFileName();
            String contentType = safe(part.getContentType()).split(";")[0].trim();
            String contentId = stripCid(headerValue(part, "Content-ID"));
            boolean attachmentDisp = Part.ATTACHMENT.equalsIgnoreCase(disposition);

            if (!attachmentDisp && part.isMimeType("text/html")) {
                if (info.htmlBody.isBlank()) {
                    String html = safe(part.getContent());
                    if (html.length() > 3_000_000) html = html.substring(0, 3_000_000);
                    info.htmlBody = html;
                }
                return;
            }
            if (!attachmentDisp && part.isMimeType("text/plain")) {
                return;
            }
            if (part.isMimeType("multipart/*")) {
                Multipart multipart = (Multipart) part.getContent();
                for (int i = 0; i < multipart.getCount(); i++) {
                    extractRich(multipart.getBodyPart(i), info);
                }
                return;
            }
            if (part.isMimeType("message/rfc822")) {
                extractRich((Part) part.getContent(), info);
                return;
            }

            boolean inlineImage = contentType.toLowerCase().startsWith("image/")
                    && (!contentId.isBlank() || Part.INLINE.equalsIgnoreCase(disposition));
            if (attachmentDisp || (fileName != null && !fileName.isBlank()) || inlineImage) {
                long current = 0;
                for (AttachmentInfo existing : info.attachments) current += existing.data.length;
                if (current >= MAX_ATTACHMENT_BYTES) return;

                byte[] data = part.getInputStream().readAllBytes();
                if (data.length == 0 || current + data.length > MAX_ATTACHMENT_BYTES) return;

                AttachmentInfo attachment = new AttachmentInfo();
                attachment.fileName = decodeFileName(fileName);
                if (attachment.fileName.isBlank()) {
                    attachment.fileName = "tep-" + (info.attachments.size() + 1) + extensionFor(contentType);
                }
                attachment.contentType = contentType.isBlank() ? "application/octet-stream" : contentType;
                attachment.contentId = contentId;
                attachment.data = data;
                info.attachments.add(attachment);
            }
        } catch (Exception ignored) { }
    }

    private static String stripCid(String value) {
        String cid = safe(value);
        if (cid.startsWith("<") && cid.endsWith(">")) cid = cid.substring(1, cid.length() - 1);
        return cid.trim();
    }

    private static String decodeFileName(String value) {
        if (value == null || value.isBlank()) return "";
        try { return MimeUtility.decodeText(value).trim(); }
        catch (Exception ignored) { return value.trim(); }
    }

    private static String extensionFor(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (type.contains("jpeg") || type.contains("jpg")) return ".jpg";
        if (type.contains("png")) return ".png";
        if (type.contains("gif")) return ".gif";
        if (type.contains("webp")) return ".webp";
        if (type.contains("pdf")) return ".pdf";
        if (type.contains("zip")) return ".zip";
        return ".bin";
    }

    private static String htmlToText(String html) {
        String text = html.replaceAll("(?is)<(script|style).*?</\\1>", " ");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</p>", "\n");
        text = text.replaceAll("(?is)<[^>]+>", " ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'");
        return text.replaceAll("[ \\t]{2,}", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String normalizePassword(String value) {
        return value == null ? "" : value.replaceAll("\\s", "");
    }

    private static String safe(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
