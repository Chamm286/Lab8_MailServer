package client;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.Interpolator;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MailFlow Client — giao diện mail workspace.
 *
 * Luồng demo 2 máy:
 *  1. Máy A gửi thư thật qua Google SMTP (From: "Tên hiển thị" <email>).
 *  2. Điện thoại/máy B nhận thư, thấy đúng tên người gửi, bấm Trả lời.
 *  3. Client A tự đọc Gmail bằng IMAP, đẩy thư trả lời vào server.
 *  4. Server ghi 1 bản ghi MySQL + 1 file backup; client hiện thư trong Hộp thư.
 */
public class ClientApp extends Application {

    // ── Mỗi cửa sổ client có thể chạy song song với một tài khoản demo khác ──
    private static final String INSTANCE = System.getProperty("mailflow.instance", "A");
    private static final String INSTANCE_LABEL = "B".equalsIgnoreCase(INSTANCE) ? "CLIENT B" : "CLIENT A";
    private static final String DEFAULT_LOGIN_USER =
            System.getProperty("mailflow.login", "chamm4930");

    // ── Bảng màu: navy / blue / teal (không dùng tím) ──
    private static final String BG          = "#F1F5F9";
    private static final String SURFACE     = "#FFFFFF";
    private static final String NAVY        = "#0A1224";
    private static final String NAVY_2      = "#111C33";
    private static final String INK         = "#0F172A";
    private static final String SLATE       = "#334155";
    private static final String MUTED       = "#64748B";
    private static final String DIM         = "#94A3B8";
    private static final String LINE        = "#E2E8F0";
    private static final String SOFT        = "#EFF6FF";
    private static final String ACCENT      = "#2563EB";
    private static final String ACCENT_DARK = "#1D4ED8";
    private static final String SKY         = "#0EA5E9";
    private static final String TEAL        = "#0D9488";
    private static final String GREEN       = "#059669";
    private static final String GREEN_BG    = "#ECFDF5";
    private static final String AMBER       = "#D97706";
    private static final String AMBER_BG    = "#FFFBEB";
    private static final String RED         = "#DC2626";
    private static final String RED_BG      = "#FEF2F2";

    private ClientCore core;
    private Stage primaryStage;

    private String currentUser = null;
    private String currentEmail = null;
    private Stage googleAvatarDialog = null;
    private String currentName = null;
    private String currentPass = null;
    private String myIP = "unknown";

    private TextField     loginUserField, regUserField, regEmailField, regNameField;
    private PasswordField loginPassField, regPassField, regPassConfirmField;
    private VBox          loginForm, registerForm;
    private Button        tabLogin, tabRegister, loginButton, registerButton;
    private Label         loginFeedback, registerFeedback, authServerText, authDbText, authGmailText;

    private ListView<MailItem> mailList;
    private ListView<Contact>  contactList;
    private Label              listTitleLabel, listCountLabel;
    private Label              readerSubject, readerFrom, readerFromEmail, readerTo, readerTime,
                               readerFromIp, readerDb, readerHint, readerAvatar;
    private TextArea           readerBody;
    private WebView            readerWeb;
    private VBox               readerAttachments;
    private VBox               readerEmpty, readerDetail;
    private MailItem           selectedMail;
    private final List<MailItem> allMail = new ArrayList<>();
    private boolean            suppressReader = false;

    private Label      headerAvatar, headerName, headerEmail;
    private Label      sidebarAvatar, sidebarName, sidebarEmail;
    private Label      settingsAvatarPreview;
    private static final Map<String, Image> AVATAR_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> AVATAR_FAILED = ConcurrentHashMap.newKeySet();
    private static final Map<Label, String> AVATAR_WATCHED = new WeakHashMap<>();
    private Label      gmailStatusLabel, dbStatusLabel, statusServer, statusIp, statusWait;
    private TextField  searchField;
    private TextArea   activityLog, proofLog;
    private VBox       logBox;
    private Button     logToggle;
    private StackPane  rootStack, centerContent;
    private HBox       authPage;
    private BorderPane mainPage;
    private StackPane  modalLayer;
    private Timeline   autoRefreshTimer, gmailSyncTimer, googleAvatarTimer;
    private VBox       toastContainer;
    private long       lastGoogleAvatarSyncAt = 0L;
    private boolean    googleAvatarPromptShown = false;

    private boolean isSending = false;
    private boolean refreshing = false;
    private boolean summaryLoading = false;
    private String  currentFolder = "INBOX";
    private String  currentView = "MAIL";
    private String  lastParsedFolder = "";

    private String  gmailAccount = "";
    private String  gmailAppPassSecret = "";
    private Boolean gmailVerified = null;
    private volatile boolean gmailSyncing = false;
    private long    mailSessionStart = System.currentTimeMillis();
    private String  lastSyncText = "chưa đồng bộ";
    private final Set<String> sentMessageIds = ConcurrentHashMap.newKeySet();
    private final Set<String> readMailIds = ConcurrentHashMap.newKeySet();
    private final MailConfig mailConfig = new MailConfig();
    private final StringBuilder proofBuffer = new StringBuilder();

    private HBox  navInbox, navSent, navContacts, navDemo, navSettings;
    private Label inboxBadge, sentBadge;
    private final List<Contact> contacts = new ArrayList<>();

    private String lastSentId = "", lastSentTo = "", lastSentSubject = "", lastSentTime = "";
    private String lastReplyId = "", lastReplyFrom = "", lastReplyName = "", lastReplySubject = "", lastReplyTime = "";
    private int    lastTotalEmails = 0;

    private Label demoStatEmails, demoStatInbox, demoStatSent, demoStatAccounts;
    private Label demoLastSent, demoLastReply, demoProfile, demoSync, demoDbState;
    private Label demoServerValue, demoAccountValue, demoGoogleValue, demoFromValue;
    private Label step1State, step2State, step3State, step4State;
    private long  lastSummaryAt = 0;
    private int   readerSeq = 0;
    private static final int RICH_CACHE_LIMIT = 20;
    private final Map<String, GmailImapService.MessageInfo> richCache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, GmailImapService.MessageInfo> eldest) {
                    return size() > RICH_CACHE_LIMIT;
                }
            });

    // ══════════════════════════════════════════════════════════════════
    //  MODEL
    // ══════════════════════════════════════════════════════════════════
    public static class MailItem {
        public String folder, fileName, from, to, subject, time, preview, fromName, messageId;
        public boolean starred;
        public String htmlBody = "";
        public List<GmailImapService.AttachmentInfo> attachments = new ArrayList<>();

        public MailItem(String folder, String fileName, String from, String to,
                        String subject, String time, String preview, boolean starred) {
            this(folder, fileName, from, to, subject, time, preview, starred, "");
        }

        public MailItem(String folder, String fileName, String from, String to,
                        String subject, String time, String preview, boolean starred, String fromName) {
            this(folder, fileName, from, to, subject, time, preview, starred, fromName, "");
        }

        public MailItem(String folder, String fileName, String from, String to,
                        String subject, String time, String preview, boolean starred,
                        String fromName, String messageId) {
            this.folder = folder;
            this.fileName = fileName;
            this.from = from;
            this.to = to;
            this.subject = subject;
            this.time = time;
            this.preview = preview;
            this.starred = starred;
            this.fromName = fromName;
            this.messageId = messageId == null ? "" : messageId;
        }

        public boolean isSent() { return "SENT".equalsIgnoreCase(folder); }

        public String title() {
            if (isSent()) return "Đến: " + safeText(to);
            if (fromName != null && !fromName.isBlank()) return fromName;
            return safeText(from);
        }

        public String subtitle() {
            return subject == null || subject.isBlank() ? "(Không có tiêu đề)" : subject;
        }
    }

    public static class Contact {
        public final String username, email, displayName;
        public final boolean online;

        public Contact(String username, String email, boolean online) {
            this(username, email, username, online);
        }

        public Contact(String username, String email, String displayName, boolean online) {
            this.username = username;
            this.email = email;
            this.displayName = displayName == null || displayName.isBlank() ? username : displayName;
            this.online = online;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  START
    // ══════════════════════════════════════════════════════════════════
    @Override
    public void start(Stage stage) {
        try { core = new ClientCore(); }
        catch (Exception e) { e.printStackTrace(); }

        mailConfig.load();
        gmailAccount = mailConfig.getGmailAddress();
        gmailAppPassSecret = mailConfig.getGmailAppPassword();
        myIP = detectMyIP();

        rootStack = new StackPane();
        authPage  = buildAuthPage();
        mainPage  = buildMainPage();

        modalLayer = new StackPane();
        modalLayer.setVisible(false);
        modalLayer.setMouseTransparent(true);
        modalLayer.setStyle("-fx-background-color:rgba(5,10,22,0.66);");

        toastContainer = new VBox(8);
        toastContainer.setAlignment(Pos.TOP_RIGHT);
        toastContainer.setMouseTransparent(true);
        toastContainer.setPadding(new Insets(78, 22, 22, 22));
        StackPane.setAlignment(toastContainer, Pos.TOP_RIGHT);

        StackPane master = new StackPane(rootStack, modalLayer, toastContainer);
        rootStack.getChildren().add(authPage);

        Scene scene = new Scene(master, 1440, 900);
        this.primaryStage = stage;
        stage.setTitle(windowTitle(null));
        stage.setMinWidth(1180);
        stage.setMinHeight(760);
        stage.setScene(scene);
        stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused && currentUser != null) {
                syncGmailReplies(false);
                // Ảnh Gmail có thể vừa được đổi trên điện thoại: kiểm tra lại khi quay về app.
                refreshGoogleAvatarAsync(false);
            }
        });

        // Phím tắt thao tác nhanh như một mail client chuyên nghiệp.
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN),
                () -> { if (currentUser != null) openCompose("", "", ""); });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN),
                () -> { if (currentUser != null) refreshMailboxAsync(true); });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.I, KeyCombination.CONTROL_DOWN),
                () -> { if (currentUser != null) { currentView = "MAIL"; currentFolder = "INBOX";
                    selectNav(navInbox); showMailView(); openFolder("INBOX"); } });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE),
                this::closeModal);

        stage.show();
        Platform.runLater(() -> {
            FadeTransition fade = new FadeTransition(Duration.millis(260), authPage);
            fade.setFromValue(0.15);
            fade.setToValue(1);
            fade.play();
        });
        checkBackendAsync();

        autoRefreshTimer = new Timeline(new KeyFrame(Duration.seconds(3), ev -> {
            if (currentUser == null || isSending) return;
            if ("MAIL".equals(currentView)) refreshMailboxAsync(false);
            else if ("DEMO".equals(currentView) && System.currentTimeMillis() - lastSummaryAt > 5000) loadSummaryAsync();
        }));
        autoRefreshTimer.setCycleCount(Timeline.INDEFINITE);
        autoRefreshTimer.play();

        gmailSyncTimer = new Timeline(new KeyFrame(Duration.seconds(7), ev -> syncGmailReplies(false)));
        gmailSyncTimer.setCycleCount(Timeline.INDEFINITE);
        gmailSyncTimer.play();

        // Ảnh Gmail thật: tự đồng bộ lại định kỳ, không cần upload/chọn ảnh.
        googleAvatarTimer = new Timeline(new KeyFrame(Duration.minutes(15), ev -> {
            if (currentUser != null) refreshGoogleAvatarAsync(false);
        }));
        googleAvatarTimer.setCycleCount(Timeline.INDEFINITE);
        googleAvatarTimer.play();
    }

    private String detectMyIP() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                String name    = ni.getName() != null ? ni.getName().toLowerCase(Locale.ROOT) : "";
                String display = ni.getDisplayName() != null ? ni.getDisplayName().toLowerCase(Locale.ROOT) : "";
                if (name.contains("vbox") || name.contains("vmnet") || name.contains("bluetooth")
                    || name.contains("virtual") || name.contains("docker") || name.contains("hyper-v")
                    || display.contains("virtualbox") || display.contains("vmware")
                    || display.contains("bluetooth") || display.contains("hyper-v")
                    || display.contains("virtual")) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip.contains(".") && !ip.startsWith("169.254") && !ip.startsWith("192.168.56.") && !ip.startsWith("127.")) return ip;
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) { return "unknown"; }
    }

    private String windowTitle(String user) {
        String suffix = user == null || user.isBlank() ? "" : " · @" + user;
        return "MailFlow · " + INSTANCE_LABEL + suffix + " — Gmail thật · LAN · MySQL";
    }

    /** Kiểm tra server + MySQL ngay trên màn hình đăng nhập. */
    private void checkBackendAsync() {
        if (core == null) return;
        Thread worker = new Thread(() -> {
            boolean server = false;
            boolean db = false;
            try {
                server = core.send("PING").startsWith("OK");
                if (server) db = core.send("DBSTATUS").startsWith("OK");
            } catch (Exception ignored) { }
            final boolean serverOk = server;
            final boolean dbOk = db;
            Platform.runLater(() -> updateAuthBackend(serverOk, dbOk));
        }, "backend-check");
        worker.setDaemon(true);
        worker.start();
    }

    private void updateAuthBackend(boolean serverOk, boolean dbOk) {
        if (authServerText != null) {
            authServerText.setText(serverOk
                    ? "Server online · " + core.getServerIP() + ":" + core.getPort()
                    : "Server offline · chạy run-server.bat");
            authServerText.setStyle("-fx-text-fill:" + (serverOk ? "#A7F3D0" : "#FCA5A5")
                    + "; -fx-font-size:10px; -fx-font-weight:bold;");
        }
        if (authDbText != null) {
            authDbText.setText(serverOk
                    ? (dbOk ? "MySQL đã kết nối · lab8_mail" : "MySQL offline · kiểm tra XAMPP")
                    : "MySQL chưa kiểm tra được");
            authDbText.setStyle("-fx-text-fill:" + (dbOk ? "#A7F3D0" : "#FCD34D")
                    + "; -fx-font-size:10px;");
        }
        if (authGmailText != null) {
            authGmailText.setText("Gmail/Workspace · mỗi tài khoản một App Password riêng");
            authGmailText.setStyle("-fx-text-fill:rgba(255,255,255,0.60); -fx-font-size:10px;");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  AUTH PAGE
    // ══════════════════════════════════════════════════════════════════
    private HBox buildAuthPage() {
        StackPane outer = new StackPane();
        outer.setStyle("-fx-background-color: linear-gradient(to bottom right, #040A16, #081A32 46%, #0A2C44);");

        Circle glow1 = new Circle(320, Color.web("#2563EB", 0.22));
        Circle glow2 = new Circle(230, Color.web("#0EA5E9", 0.14));
        Circle glow3 = new Circle(160, Color.web("#0D9488", 0.10));
        StackPane.setAlignment(glow1, Pos.TOP_LEFT);
        StackPane.setMargin(glow1, new Insets(-170, 0, 0, -120));
        StackPane.setAlignment(glow2, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(glow2, new Insets(0, -100, -130, 0));
        StackPane.setAlignment(glow3, Pos.BOTTOM_LEFT);
        StackPane.setMargin(glow3, new Insets(0, 0, 30, 140));

        HBox card = new HBox();
        card.setMaxWidth(1120);
        card.setPrefHeight(680);
        card.setMaxHeight(700);
        card.setStyle("-fx-background-color:" + SURFACE + "; -fx-background-radius:28; "
                + "-fx-border-color:rgba(255,255,255,0.16); -fx-border-radius:28; "
                + "-fx-effect: dropshadow(gaussian, rgba(2,6,18,0.70), 70, 0.24, 0, 26);");

        VBox brand = buildBrandPanel();
        VBox form = buildAuthFormPanel();
        HBox.setHgrow(form, Priority.ALWAYS);
        card.getChildren().addAll(brand, form);

        StackPane cardWrap = new StackPane(card);
        cardWrap.setPadding(new Insets(40));
        outer.getChildren().addAll(glow1, glow2, glow3, cardWrap);

        HBox wrapper = new HBox(outer);
        HBox.setHgrow(outer, Priority.ALWAYS);
        return wrapper;
    }

    private VBox buildBrandPanel() {
        FontIcon logoIc = new FontIcon(FontAwesomeSolid.PAPER_PLANE);
        logoIc.setIconSize(25);
        logoIc.setIconColor(Color.WHITE);
        StackPane logo = new StackPane(logoIc);
        logo.setMinSize(56, 56);
        logo.setMaxSize(56, 56);
        logo.setStyle("-fx-background-color:rgba(255,255,255,0.14); -fx-background-radius:17; "
                + "-fx-border-color:rgba(255,255,255,0.20); -fx-border-radius:17;");

        Label name = new Label("MAILFLOW");
        name.setStyle("-fx-text-fill:white; -fx-font-size:24px; -fx-font-weight:bold;");
        Label tag = new Label("SECURE MAIL WORKSPACE");
        tag.setStyle("-fx-text-fill:rgba(255,255,255,0.60); -fx-font-size:9px; -fx-font-weight:bold;");
        VBox nameBox = new VBox(2, name, tag);
        Region brandSp = new Region();
        HBox.setHgrow(brandSp, Priority.ALWAYS);
        HBox brandRow = new HBox(14, logo, nameBox, brandSp, instanceBadge(true));
        brandRow.setAlignment(Pos.CENTER_LEFT);

        Label eyebrow = new Label("ĐỒ ÁN LAB 8 · UDP + GOOGLE SMTP + MYSQL");
        eyebrow.setStyle("-fx-text-fill:#93C5FD; -fx-font-size:9px; -fx-font-weight:bold;");

        Label headline = new Label("Hộp thư thật.\nKết nối thật.");
        headline.setStyle("-fx-text-fill:white; -fx-font-size:34px; -fx-font-weight:bold; -fx-line-spacing:-3;");
        headline.setWrapText(true);

        Label desc = new Label("Gửi email qua Google SMTP, nhận thư trả lời từ điện thoại, "
                + "đồng bộ giữa hai máy trong LAN và lưu toàn bộ vào MySQL.");
        desc.setWrapText(true);
        desc.setStyle("-fx-text-fill:rgba(255,255,255,0.74); -fx-font-size:12.5px; -fx-line-spacing:2;");
        desc.setMaxWidth(340);

        VBox features = new VBox(14,
                feature(FontAwesomeSolid.PAPER_PLANE, "Gửi Gmail thật", "SMTP bảo mật · tên người gửi hiện đúng trên điện thoại."),
                feature(FontAwesomeSolid.MOBILE_ALT, "Điện thoại trả lời", "Client tự đọc IMAP và đưa thư trả lời về Hộp thư."),
                feature(FontAwesomeSolid.DATABASE, "Lưu MySQL", "Mỗi thư gửi/nhận là một dòng trong bảng emails."));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        authServerText = new Label("Server · đang kiểm tra…");
        authDbText = new Label("MySQL · đang kiểm tra…");
        authGmailText = new Label("Gmail/Workspace · App Password riêng cho từng email");
        authServerText.setStyle("-fx-text-fill:rgba(255,255,255,0.72); -fx-font-size:10px; -fx-font-weight:bold;");
        authDbText.setStyle("-fx-text-fill:rgba(255,255,255,0.60); -fx-font-size:10px;");
        authGmailText.setStyle("-fx-text-fill:rgba(255,255,255,0.60); -fx-font-size:10px;");

        VBox statusBox = new VBox(8,
                authStatusRow(FontAwesomeSolid.WIFI, authServerText),
                authStatusRow(FontAwesomeSolid.DATABASE, authDbText),
                authStatusRow(FontAwesomeSolid.AT, authGmailText));
        statusBox.setPadding(new Insets(13, 14, 13, 14));
        statusBox.setStyle("-fx-background-color:rgba(2,10,24,0.30); -fx-background-radius:13; "
                + "-fx-border-color:rgba(147,197,253,0.16); -fx-border-radius:13;");

        Label localIp = new Label("IP máy này: " + myIP + "   ·   " + MailConfig.getConfigFileName());
        localIp.setStyle("-fx-text-fill:rgba(255,255,255,0.38); -fx-font-size:9px;");

        VBox panel = new VBox(18, brandRow, eyebrow, headline, desc, spacer,
                features, statusBox, localIp);
        panel.setPrefWidth(440);
        panel.setMinWidth(440);
        panel.setPadding(new Insets(38, 38, 28, 38));
        panel.setStyle("-fx-background-color: linear-gradient(to bottom right, #0B2A4A, #0E3A63 45%, #0B4F6C);"
                + " -fx-background-radius:28 0 0 28;");
        VBox.setVgrow(panel, Priority.ALWAYS);
        return panel;
    }

    private HBox authStatusRow(FontAwesomeSolid icon, Label text) {
        FontIcon fi = new FontIcon(icon);
        fi.setIconSize(11);
        fi.setIconColor(Color.web("#93C5FD"));
        StackPane box = new StackPane(fi);
        box.setMinSize(20, 20);
        box.setStyle("-fx-background-color:rgba(147,197,253,0.14); -fx-background-radius:6;");
        HBox row = new HBox(9, box, text);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Label instanceBadge(boolean dark) {
        Label badge = new Label(INSTANCE_LABEL);
        if (dark) {
            badge.setStyle("-fx-text-fill:#DBEAFE; -fx-font-size:9px; -fx-font-weight:bold; "
                    + "-fx-background-color:rgba(37,99,235,0.30); -fx-border-color:rgba(147,197,253,0.28); "
                    + "-fx-border-radius:8; -fx-background-radius:8; -fx-padding:4 9;");
        } else {
            badge.setStyle("-fx-text-fill:" + ACCENT + "; -fx-font-size:9px; -fx-font-weight:bold; "
                    + "-fx-background-color:" + SOFT + "; -fx-border-color:#BFDBFE; "
                    + "-fx-border-radius:8; -fx-background-radius:8; -fx-padding:4 9;");
        }
        return badge;
    }

    private HBox feature(FontAwesomeSolid icon, String title, String desc) {
        FontIcon ic = new FontIcon(icon);
        ic.setIconSize(14);
        ic.setIconColor(Color.web("#BFDBFE"));
        StackPane box = new StackPane(ic);
        box.setMinSize(38, 38);
        box.setMaxSize(38, 38);
        box.setStyle("-fx-background-color:rgba(37,99,235,0.28); -fx-background-radius:12; "
                + "-fx-border-color:rgba(147,197,253,0.22); -fx-border-radius:12;");
        Label t = new Label(title);
        t.setStyle("-fx-text-fill:white; -fx-font-size:12.5px; -fx-font-weight:bold;");
        Label d = new Label(desc);
        d.setStyle("-fx-text-fill:rgba(255,255,255,0.66); -fx-font-size:10.5px;");
        d.setWrapText(true);
        VBox text = new VBox(2, t, d);
        HBox row = new HBox(12, box, text);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox buildAuthFormPanel() {
        Label eyebrow = new Label("CHÀO MỪNG TRỞ LẠI");
        eyebrow.setStyle("-fx-text-fill:" + ACCENT + "; -fx-font-size:9px; -fx-font-weight:bold;");

        Label title = new Label("Đăng nhập MailFlow");
        title.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:25px; -fx-font-weight:bold;");

        Label sub = new Label("Dùng tài khoản do server quản lý để mở hộp thư của bạn.");
        sub.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:11.5px;");

        Region titleSp = new Region();
        HBox.setHgrow(titleSp, Priority.ALWAYS);
        HBox titleRow = new HBox(10, new VBox(3, eyebrow, title), titleSp, instanceBadge(false));
        titleRow.setAlignment(Pos.CENTER_LEFT);

        tabLogin    = authTab("Đăng nhập", true);
        tabRegister = authTab("Tạo tài khoản", false);
        HBox tabs = new HBox(6, tabLogin, tabRegister);
        tabs.setStyle("-fx-background-color:" + BG + "; -fx-background-radius:12; -fx-padding:5;");

        loginUserField = lightField(FontAwesomeSolid.USER, "Tên đăng nhập");
        String defaultUser = DEFAULT_LOGIN_USER;
        if (defaultUser == null || defaultUser.isBlank()) {
            String gmailLocal = mailConfig.getGmailAddress();
            int atSign = gmailLocal.indexOf('@');
            defaultUser = atSign > 0 ? gmailLocal.substring(0, atSign) : "";
        }
        loginUserField.setText(defaultUser);

        loginPassField = new PasswordField();
        VBox loginPassWrapper = passwordFieldWrapper(loginPassField, "Mật khẩu");
        loginPassField.setOnAction(e -> doLogin());

        loginButton = primaryButton("ĐĂNG NHẬP", FontAwesomeSolid.SIGN_IN_ALT);
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setPrefHeight(42);
        loginButton.setOnAction(e -> doLogin());

        loginFeedback = new Label();
        loginFeedback.setWrapText(true);
        loginFeedback.setVisible(false);
        loginFeedback.setManaged(false);

        Label loginHint = noteLabel("Mật khẩu này là mật khẩu tài khoản server, không phải Google App Password.");
        HBox loginMeta = new HBox(7, icon(FontAwesomeSolid.SHIELD_ALT, "#94A3B8", 10), loginHint);
        loginMeta.setAlignment(Pos.CENTER_LEFT);

        Label demoTitle = new Label("Tài khoản demo của " + INSTANCE_LABEL);
        demoTitle.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:11px; -fx-font-weight:bold;");
        Label demoAccount = new Label(DEFAULT_LOGIN_USER + "  /  123456");
        demoAccount.setStyle("-fx-text-fill:" + ACCENT + "; -fx-font-size:10.5px; -fx-font-weight:bold; "
                + "-fx-background-color:" + SURFACE + "; -fx-padding:4 8; -fx-background-radius:7;");
        Button demoFill = ghostButton(FontAwesomeSolid.BOLT, "Đăng nhập nhanh");
        demoFill.setOnAction(e -> {
            loginUserField.setText(DEFAULT_LOGIN_USER);
            loginPassField.setText("123456");
            doLogin();
        });
        Region demoSp = new Region();
        HBox.setHgrow(demoSp, Priority.ALWAYS);
        HBox demoTop = new HBox(8, demoTitle, demoSp, demoAccount);
        demoTop.setAlignment(Pos.CENTER_LEFT);
        Label demoNote = noteLabel("Dùng cho buổi demo: mỗi cửa sổ đăng nhập một tài khoản/email khác nhau. "
                + "Gmail sẽ gửi và nhận bằng chính email của tài khoản đang đăng nhập.");
        VBox demoCard = new VBox(7, demoTop, demoNote, demoFill);
        demoCard.setPadding(new Insets(11, 12, 11, 12));
        demoCard.setStyle("-fx-background-color:" + BG + "; -fx-background-radius:11; "
                + "-fx-border-color:" + LINE + "; -fx-border-radius:11;");

        loginForm = new VBox(12,
                labeledInput(FontAwesomeSolid.USER, "Tên đăng nhập", loginUserField),
                labeledInput(FontAwesomeSolid.LOCK, "Mật khẩu", loginPassWrapper),
                loginButton, loginFeedback, loginMeta, demoCard);
        loginForm.setPadding(new Insets(16, 0, 0, 0));

        regUserField  = lightField(FontAwesomeSolid.USER_PLUS, "Tên đăng nhập mới");
        regNameField  = lightField(FontAwesomeSolid.ADDRESS_CARD, "Tên hiển thị — hiện trên điện thoại người nhận");
        regEmailField = lightField(FontAwesomeSolid.AT, "Email thật — ban@gmail.com, ban@vku.udn.vn…");

        regPassField        = new PasswordField();
        regPassConfirmField = new PasswordField();
        VBox regPassWrapper        = passwordFieldWrapper(regPassField, "Mật khẩu (ít nhất 6 ký tự)");
        VBox regPassConfirmWrapper = passwordFieldWrapper(regPassConfirmField, "Xác nhận mật khẩu");

        registerButton = primaryButton("TẠO TÀI KHOẢN", FontAwesomeSolid.USER_PLUS);
        registerButton.setMaxWidth(Double.MAX_VALUE);
        registerButton.setPrefHeight(42);
        registerButton.setOnAction(e -> doRegister());

        registerFeedback = new Label();
        registerFeedback.setWrapText(true);
        registerFeedback.setVisible(false);
        registerFeedback.setManaged(false);

        Label regHint = noteLabel("Email có thể thuộc bất kỳ tên miền nào. Tên hiển thị là thứ người nhận "
                + "nhìn thấy trên điện thoại khi bạn gửi thư.");

        registerForm = new VBox(9,
                labeledInput(FontAwesomeSolid.USER_PLUS, "Tên đăng nhập", regUserField),
                labeledInput(FontAwesomeSolid.ADDRESS_CARD, "Tên hiển thị", regNameField),
                labeledInput(FontAwesomeSolid.AT, "Email thật", regEmailField),
                labeledInput(FontAwesomeSolid.LOCK, "Mật khẩu", regPassWrapper),
                labeledInput(FontAwesomeSolid.LOCK, "Xác nhận mật khẩu", regPassConfirmWrapper),
                registerButton, registerFeedback, regHint);
        registerForm.setPadding(new Insets(12, 0, 0, 0));
        registerForm.setVisible(false);
        registerForm.setManaged(false);

        tabLogin.setOnAction(e -> switchAuthTab(true));
        tabRegister.setOnAction(e -> switchAuthTab(false));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        FontIcon lockIc = new FontIcon(FontAwesomeSolid.LOCK);
        lockIc.setIconSize(10);
        lockIc.setIconColor(Color.web(GREEN));
        Label secure = new Label("  App Password chỉ lưu trên máy này · Không gửi lên server");
        secure.setGraphic(lockIc);
        secure.setStyle("-fx-text-fill:" + GREEN + "; -fx-font-size:9.5px; -fx-font-weight:bold; "
                + "-fx-background-color:" + GREEN_BG + "; -fx-padding:8 11; -fx-background-radius:9;");

        Label footer = new Label("Mỗi tài khoản gắn với một email thật. Dữ liệu thư được lưu trong MySQL lab8_mail.");
        footer.setWrapText(true);
        footer.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9.5px;");

        VBox panel = new VBox(8, titleRow, sub, tabs, loginForm, registerForm, spacer, secure, footer);
        panel.setPadding(new Insets(36, 46, 26, 46));
        panel.setStyle("-fx-background-color:" + SURFACE + "; -fx-background-radius:0 28 28 0;");
        VBox.setVgrow(panel, Priority.ALWAYS);
        return panel;
    }

    private void switchAuthTab(boolean login) {
        tabLogin.setStyle(authTabStyle(login));
        tabRegister.setStyle(authTabStyle(!login));
        loginForm.setVisible(login);      loginForm.setManaged(login);
        registerForm.setVisible(!login);  registerForm.setManaged(!login);
        setFeedback(loginFeedback, "", true);
        setFeedback(registerFeedback, "", true);
    }

    private Button authTab(String text, boolean active) {
        Button b = new Button(text);
        b.setPrefWidth(158);
        b.setPrefHeight(38);
        b.setStyle(authTabStyle(active));
        return b;
    }

    private String authTabStyle(boolean active) {
        if (active) return "-fx-background-color:" + SURFACE + "; -fx-text-fill:" + ACCENT
                + "; -fx-font-weight:bold; -fx-font-size:12px; -fx-background-radius:9; -fx-cursor:hand;"
                + " -fx-effect: dropshadow(gaussian, rgba(15,23,42,0.10), 8, 0, 0, 2);";
        return "-fx-background-color:transparent; -fx-text-fill:" + MUTED
                + "; -fx-font-size:12px; -fx-background-radius:9; -fx-cursor:hand;";
    }

    // ══════════════════════════════════════════════════════════════════
    //  MAIN LAYOUT
    // ══════════════════════════════════════════════════════════════════
    private BorderPane buildMainPage() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:" + BG + ";");
        root.setTop(buildTopBar());
        root.setLeft(buildSidebar());
        root.setCenter(buildCenterArea());
        root.setBottom(buildStatusBar());
        return root;
    }

    private HBox buildTopBar() {
        FontIcon logoIc = new FontIcon(FontAwesomeSolid.PAPER_PLANE);
        logoIc.setIconSize(17);
        logoIc.setIconColor(Color.WHITE);
        StackPane logoBox = new StackPane(logoIc);
        logoBox.setMinSize(38, 38);
        logoBox.setMaxSize(38, 38);
        logoBox.setStyle("-fx-background-color: linear-gradient(to bottom right, " + ACCENT + ", " + SKY
                + "); -fx-background-radius:12;");

        Label title = new Label("MAILFLOW");
        title.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:14px; -fx-font-weight:bold;");
        Label sub = new Label("Mail workspace");
        sub.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9.5px;");
        VBox titleBox = new VBox(1, title, sub);
        HBox brand = new HBox(10, logoBox, titleBox, instanceBadge(false));
        brand.setAlignment(Pos.CENTER_LEFT);

        Region sp1 = new Region();
        HBox.setHgrow(sp1, Priority.ALWAYS);

        FontIcon searchIc = new FontIcon(FontAwesomeSolid.SEARCH);
        searchIc.setIconSize(12);
        searchIc.setIconColor(Color.web(DIM));
        searchField = new TextField();
        searchField.setPromptText("Tìm theo người gửi, tiêu đề, nội dung…");
        searchField.setPrefWidth(320);
        searchField.setPrefHeight(36);
        searchField.setStyle("-fx-background-color:transparent; -fx-text-fill:" + INK
                + "; -fx-prompt-text-fill:" + DIM + "; -fx-font-size:12px;");
        searchField.textProperty().addListener((o, a, b) -> applyFilter());
        HBox searchBox = new HBox(9, searchIc, searchField);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.setPadding(new Insets(0, 14, 0, 13));
        searchBox.setMaxWidth(370);
        searchBox.setStyle("-fx-background-color:" + BG + "; -fx-background-radius:11; -fx-border-color:" + LINE
                + "; -fx-border-radius:11;");

        Region sp2 = new Region();
        HBox.setHgrow(sp2, Priority.ALWAYS);

        gmailStatusLabel = statusPill("● Google: …", MUTED, "#FFFFFF");
        dbStatusLabel    = statusPill("● DB: …", MUTED, "#FFFFFF");
        serverStatusPill();
        updateGmailStatus();

        headerAvatar = new Label("?");
        headerAvatar.setMinSize(32, 32);
        headerAvatar.setMaxSize(32, 32);
        headerAvatar.setAlignment(Pos.CENTER);
        headerAvatar.setStyle("-fx-background-color: linear-gradient(to bottom right, " + ACCENT + ", " + SKY
                + "); -fx-text-fill:white; -fx-font-size:12px; -fx-font-weight:bold; -fx-background-radius:50;"
                + " -fx-cursor:hand;");
        headerAvatar.setTooltip(new Tooltip("Đồng bộ ảnh Gmail thật"));
        headerAvatar.setOnMouseClicked(e -> {
            e.consume();
            if (currentEmail == null || currentEmail.isBlank()) openSettings();
            else openGoogleAvatarDialog(this::updateProfileLabels);
        });

        headerName = new Label("guest");
        headerName.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:11.5px; -fx-font-weight:bold;");
        headerEmail = new Label("chưa đăng nhập");
        headerEmail.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9.5px;");
        VBox headerText = new VBox(1, headerName, headerEmail);

        HBox userChip = new HBox(8, headerAvatar, headerText);
        userChip.setAlignment(Pos.CENTER_LEFT);
        userChip.setPadding(new Insets(4, 12, 4, 4));
        userChip.setStyle("-fx-background-color:" + BG + "; -fx-background-radius:22; -fx-cursor:hand;");
        userChip.setOnMouseClicked(e -> openSettings());

        Button logout = iconButton(FontAwesomeSolid.SIGN_OUT_ALT, "Đăng xuất", MUTED);
        logout.setOnAction(e -> logout());

        Button quickCompose = iconButton(FontAwesomeSolid.PEN, "Soạn thư mới (Ctrl+N)", ACCENT);
        quickCompose.setStyle("-fx-background-color:" + SOFT + "; -fx-padding:8; -fx-background-radius:9; -fx-cursor:hand;");
        quickCompose.setOnAction(e -> openCompose("", "", ""));

        HBox top = new HBox(10, brand, sp1, searchBox, sp2,
                statusServer, gmailStatusLabel, dbStatusLabel, quickCompose, userChip, logout);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 20, 12, 20));
        top.setStyle("-fx-background-color:" + SURFACE + "; -fx-border-color:transparent transparent " + LINE
                + " transparent; -fx-border-width:0 0 1 0;");
        return top;
    }

    private void serverStatusPill() {
        statusServer = statusPill("● Server: " + core.getServerIP(), "#0369A1", "#E0F2FE");
    }

    private VBox buildSidebar() {
        sidebarAvatar = new Label("?");
        sidebarAvatar.setMinSize(40, 40);
        sidebarAvatar.setMaxSize(40, 40);
        sidebarAvatar.setAlignment(Pos.CENTER);
        sidebarAvatar.setStyle("-fx-background-color: linear-gradient(to bottom right, " + ACCENT + ", " + SKY
                + "); -fx-text-fill:white; -fx-font-size:14px; -fx-font-weight:bold; -fx-background-radius:50;");

        sidebarName = new Label("guest");
        sidebarName.setStyle("-fx-text-fill:white; -fx-font-size:12.5px; -fx-font-weight:bold;");
        sidebarEmail = new Label("chưa đăng nhập");
        sidebarEmail.setStyle("-fx-text-fill:rgba(255,255,255,0.48); -fx-font-size:9.5px;");
        sidebarEmail.setMaxWidth(150);

        VBox accountText = new VBox(1, sidebarName, sidebarEmail);
        HBox accountCard = new HBox(10, sidebarAvatar, accountText);
        accountCard.setAlignment(Pos.CENTER_LEFT);
        accountCard.setPadding(new Insets(10, 10, 10, 10));
        accountCard.setStyle("-fx-background-color:rgba(255,255,255,0.05); -fx-background-radius:14; "
                + "-fx-border-color:rgba(255,255,255,0.07); -fx-border-radius:14; -fx-cursor:hand;");
        accountCard.setOnMouseClicked(e -> openSettings());

        navInbox    = navItem(FontAwesomeSolid.INBOX, "Hộp thư", () -> {
            currentView = "MAIL"; currentFolder = "INBOX"; selectNav(navInbox); showMailView(); openFolder("INBOX");
        });
        navSent     = navItem(FontAwesomeSolid.PAPER_PLANE, "Đã gửi", () -> {
            currentView = "MAIL"; currentFolder = "SENT"; selectNav(navSent); showMailView(); openFolder("SENT");
        });
        navContacts = navItem(FontAwesomeSolid.USERS, "Danh bạ", () -> {
            currentView = "CONTACTS"; selectNav(navContacts); showContactsView();
        });
        navDemo     = navItem(FontAwesomeSolid.MOBILE_ALT, "Test 2 máy", () -> {
            currentView = "DEMO"; selectNav(navDemo); showDemoView();
        });
        navSettings = navItem(FontAwesomeSolid.COG, "Cấu hình", this::openSettings);

        inboxBadge = navBadge();
        sentBadge  = navBadge();
        ((HBox) navInbox.getChildren().get(navInbox.getChildren().size() - 1)).getChildren().add(inboxBadge);
        ((HBox) navSent.getChildren().get(navSent.getChildren().size() - 1)).getChildren().add(sentBadge);
        setBadge(inboxBadge, 0);
        setBadge(sentBadge, 0);

        VBox inboxGroup = new VBox(4, sectionLabel("HỘP THƯ"), navInbox, navSent);
        VBox exploreGroup = new VBox(4, sectionLabel("KẾT NỐI"), navContacts, navDemo);
        VBox systemGroup = new VBox(4, sectionLabel("HỆ THỐNG"), navSettings);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label serverLine = new Label("Server " + core.getServerIP() + ":" + core.getPort());
        serverLine.setStyle("-fx-text-fill:rgba(255,255,255,0.42); -fx-font-size:9px;");
        Label ipLine = new Label("IP máy này: " + myIP);
        ipLine.setStyle("-fx-text-fill:rgba(255,255,255,0.32); -fx-font-size:9px;");
        Label ver = new Label("MAILFLOW v8.2 · " + INSTANCE_LABEL);
        ver.setStyle("-fx-text-fill:rgba(255,255,255,0.22); -fx-font-size:8.5px; -fx-font-weight:bold;");

        VBox rail = new VBox(16, accountCard, inboxGroup, exploreGroup, systemGroup,
                spacer, serverLine, ipLine, ver);
        rail.setPrefWidth(238);
        rail.setMinWidth(238);
        rail.setPadding(new Insets(16, 12, 16, 12));
        rail.setStyle("-fx-background-color: linear-gradient(to bottom, " + NAVY + ", " + NAVY_2 + ");");
        selectNav(navInbox);
        return rail;
    }

    private StackPane buildCenterArea() {
        centerContent = new StackPane();
        centerContent.setStyle("-fx-background-color:" + BG + ";");
        return centerContent;
    }

    private VBox buildStatusBar() {
        Label dot1 = new Label("●");
        dot1.setStyle("-fx-text-fill:" + GREEN + "; -fx-font-size:8px;");
        statusServer = new Label("Server " + core.getServerIP() + ":" + core.getPort());
        statusServer.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10px;");

        Label dot2 = new Label("●");
        dot2.setStyle("-fx-text-fill:" + SKY + "; -fx-font-size:8px;");
        statusIp = new Label("IP " + myIP);
        statusIp.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10px;");

        Label dot3 = new Label("●");
        dot3.setStyle("-fx-text-fill:" + AMBER + "; -fx-font-size:8px;");
        statusWait = new Label("Chưa gửi thư nào trong phiên này");
        statusWait.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10px;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Label gmailFixed = new Label("Gmail: dùng email của tài khoản đang đăng nhập");
        gmailFixed.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9px;");

        activityLog = new TextArea();
        activityLog.setEditable(false);
        activityLog.setWrapText(false);
        activityLog.setPrefRowCount(4);
        activityLog.setStyle("-fx-control-inner-background:" + SURFACE + "; -fx-background-color:" + SURFACE
                + "; -fx-text-fill:#0F766E; -fx-font-family:'Consolas'; -fx-font-size:10.5px;"
                + " -fx-background-radius:9; -fx-border-color:" + LINE + "; -fx-border-radius:9;");

        logBox = new VBox(activityLog);
        logBox.setPadding(new Insets(0, 20, 10, 20));
        logBox.setVisible(false);
        logBox.setManaged(false);
        logBox.setStyle("-fx-background-color:" + SURFACE + ";");

        logToggle = new Button("Nhật ký");
        FontIcon termIc = new FontIcon(FontAwesomeSolid.TERMINAL);
        termIc.setIconSize(10);
        termIc.setIconColor(Color.web(MUTED));
        logToggle.setGraphic(termIc);
        logToggle.setStyle("-fx-background-color:transparent; -fx-text-fill:" + MUTED
                + "; -fx-font-size:10px; -fx-cursor:hand;");
        logToggle.setOnAction(e -> {
            boolean show = !logBox.isVisible();
            logBox.setVisible(show);
            logBox.setManaged(show);
            logToggle.setText(show ? "Ẩn nhật ký" : "Nhật ký");
        });

        HBox bar = new HBox(8, dot1, statusServer, sep(), dot2, statusIp, sep(), dot3, statusWait,
                sp, gmailFixed, sep(), logToggle);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 20, 8, 20));
        bar.setStyle("-fx-background-color:" + SURFACE + "; -fx-border-color:" + LINE
                + " transparent transparent transparent; -fx-border-width:1 0 0 0;");

        VBox bottom = new VBox(logBox, bar);
        bottom.setStyle("-fx-background-color:" + SURFACE + ";");
        return bottom;
    }

    // ══════════════════════════════════════════════════════════════════
    //  MAIL VIEW
    // ══════════════════════════════════════════════════════════════════
    private void showMailView() {
        HBox view = new HBox();
        view.setPadding(new Insets(16, 18, 16, 18));

        VBox leftPanel = new VBox(0);
        leftPanel.setPrefWidth(420);
        leftPanel.setMinWidth(370);
        leftPanel.setMaxWidth(470);
        leftPanel.setStyle(cardStyle(16));

        boolean sent = "SENT".equals(currentFolder);
        FontIcon inboxIc = new FontIcon(sent ? FontAwesomeSolid.PAPER_PLANE : FontAwesomeSolid.INBOX);
        inboxIc.setIconSize(15);
        inboxIc.setIconColor(Color.web(ACCENT));
        listTitleLabel = new Label("  " + (sent ? "Thư đã gửi" : "Hộp thư đến"));
        listTitleLabel.setGraphic(inboxIc);
        listTitleLabel.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:16px; -fx-font-weight:bold;");

        listCountLabel = new Label("0");
        listCountLabel.setStyle("-fx-text-fill:" + ACCENT + "; -fx-font-size:10px; -fx-font-weight:bold; "
                + "-fx-background-color:" + SOFT + "; -fx-padding:3 9; -fx-background-radius:10;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button refreshBtn = iconButton(FontAwesomeSolid.SYNC_ALT, "Làm mới hộp thư", MUTED);
        refreshBtn.setOnAction(e -> { refreshMailboxAsync(true); log("Đang làm mới hộp thư…"); });

        Button composeBtn = primaryButton("SOẠN THƯ", FontAwesomeSolid.PEN);
        composeBtn.setPrefHeight(38);
        composeBtn.setOnAction(e -> openCompose("", "", ""));

        HBox titleRow = new HBox(8, listTitleLabel, listCountLabel, sp, refreshBtn, composeBtn);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.setPadding(new Insets(16, 14, 12, 18));
        titleRow.setStyle("-fx-background-color: linear-gradient(to bottom, #F8FBFF, " + SURFACE + ");"
                + " -fx-border-color:transparent transparent " + LINE + " transparent; -fx-border-width:0 0 1 0;");

        Button syncBtn = ghostButton(FontAwesomeSolid.ROBOT, "Đồng bộ trả lời");
        syncBtn.setOnAction(e -> syncGmailReplies(true));
        Button gmailBtn = ghostButton(FontAwesomeSolid.ENVELOPE_OPEN, "Xem Gmail mới nhất");
        gmailBtn.setOnAction(e -> checkGmailInbox());
        HBox toolsRow = new HBox(8, syncBtn, gmailBtn);
        toolsRow.setPadding(new Insets(0, 14, 12, 16));

        mailList = new ListView<>();
        mailList.setStyle("-fx-background-color:" + SURFACE + "; -fx-border-color:transparent; "
                + "-fx-control-inner-background:" + SURFACE + "; -fx-padding:0;");
        mailList.setPlaceholder(emptyState());
        mailList.setCellFactory(lv -> new MailCell());
        mailList.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (suppressReader) return;
            if (item != null) { selectedMail = item; showReader(item); }
        });
        VBox.setVgrow(mailList, Priority.ALWAYS);
        HBox.setHgrow(leftPanel, Priority.NEVER);

        leftPanel.getChildren().addAll(titleRow, toolsRow, mailList);
        VBox.setVgrow(leftPanel, Priority.ALWAYS);

        StackPane readerPane = buildReaderPane();
        HBox.setHgrow(readerPane, Priority.ALWAYS);
        HBox.setMargin(readerPane, new Insets(0, 0, 0, 16));

        view.getChildren().addAll(leftPanel, readerPane);
        centerContent.getChildren().setAll(view);

        if (selectedMail == null) showReaderEmpty();
        else showReader(selectedMail);
    }

    private class MailCell extends ListCell<MailItem> {
        private final HBox root;
        private final StackPane avatar;
        private final Label avatarText;
        private final Label title, email, subject, preview, time, badge;
        private final Circle unreadDot;
        private boolean hovered;

        MailCell() {
            avatarText = new Label("?");
            avatarText.setMinSize(42, 42);
            avatarText.setMaxSize(42, 42);
            avatarText.setAlignment(Pos.CENTER);
            avatarText.setStyle("-fx-text-fill:white; -fx-font-size:13px; -fx-font-weight:bold;");
            avatar = new StackPane(avatarText);
            avatar.setMinSize(42, 42);
            avatar.setMaxSize(42, 42);
            avatar.setStyle("-fx-background-color:transparent;");

            title = new Label();
            title.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:12.5px; -fx-font-weight:bold;");
            title.setMinWidth(0);
            title.setMaxWidth(Double.MAX_VALUE);
            title.setTextOverrun(OverrunStyle.ELLIPSIS);
            email = new Label();
            email.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9.5px;");
            email.setMinWidth(0);
            email.setMaxWidth(150);
            email.setTextOverrun(OverrunStyle.ELLIPSIS);
            subject = new Label();
            subject.setStyle("-fx-text-fill:" + SLATE + "; -fx-font-size:11.5px;");
            subject.setMinWidth(0);
            subject.setMaxWidth(Double.MAX_VALUE);
            subject.setTextOverrun(OverrunStyle.ELLIPSIS);
            preview = new Label();
            preview.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:10.5px;");
            preview.setMinWidth(0);
            preview.setMaxWidth(Double.MAX_VALUE);
            preview.setTextOverrun(OverrunStyle.ELLIPSIS);
            time = new Label();
            time.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9px;");
            badge = new Label();
            badge.setStyle("-fx-text-fill:" + ACCENT + "; -fx-font-size:8.5px; -fx-font-weight:bold; "
                    + "-fx-background-color:" + SOFT + "; -fx-padding:2 7; -fx-background-radius:6;");

            unreadDot = new Circle(3.5, Color.web(ACCENT));
            unreadDot.setVisible(false);
            HBox nameRow = new HBox(7, unreadDot, title, email);
            nameRow.setAlignment(Pos.CENTER_LEFT);
            nameRow.setMinWidth(0);
            VBox body = new VBox(2, nameRow, subject, preview);
            body.setMinWidth(0);
            HBox.setHgrow(body, Priority.ALWAYS);
            VBox right = new VBox(5, time, badge);
            right.setAlignment(Pos.TOP_RIGHT);
            right.setMinWidth(Region.USE_PREF_SIZE);

            root = new HBox(11, avatar, body, right);
            root.setAlignment(Pos.CENTER_LEFT);
            root.setMinHeight(86);
            root.setMinWidth(0);
            root.setMaxWidth(Double.MAX_VALUE);
            root.setPadding(new Insets(11, 13, 11, 14));

            selectedProperty().addListener((o, a, b) -> {
                if (b && getItem() != null) {
                    readMailIds.add(mailKey(getItem()));
                    unreadDot.setVisible(false);
                    title.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:12.5px; -fx-font-weight:normal;");
                    subject.setStyle("-fx-text-fill:" + SLATE + "; -fx-font-size:11.5px; -fx-font-weight:normal;");
                }
                paint();
            });
            root.setOnMouseEntered(e -> { hovered = true; paint(); });
            root.setOnMouseExited(e -> { hovered = false; paint(); });
        }

        private String mailKey(MailItem item) {
            if (item == null) return "";
            return (item.folder == null ? "" : item.folder) + "|"
                    + (item.fileName == null ? "" : item.fileName) + "|"
                    + (item.messageId == null ? "" : item.messageId);
        }

        private void paint() {
            if (isEmpty() || getItem() == null) {
                root.setStyle("-fx-background-color:" + SURFACE + ";");
                return;
            }
            String background = isSelected() ? SOFT : (hovered ? "#F8FAFC" : SURFACE);
            root.setStyle("-fx-background-color:" + background
                    + "; -fx-border-color:transparent transparent " + LINE + " transparent; -fx-border-width:0 0 1 0;");
        }

        @Override protected void updateItem(MailItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                unreadDot.setVisible(false);
                setGraphic(null); setText(null);
                setStyle("-fx-background-color:" + SURFACE + ";");
                return;
            }
            String titleText = item.title();
            String avatarEmail = item.isSent() ? firstAddress(item.to) : item.from;
            applyAvatar(avatarText, avatarEmail, titleText, 42,
                    titleText.isEmpty() ? "?" : titleText.substring(0, 1).toUpperCase());
            title.setText(titleText);
            boolean unread = "INBOX".equalsIgnoreCase(item.folder) && !readMailIds.contains(mailKey(item));
            unreadDot.setVisible(unread);
            title.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:12.5px; -fx-font-weight:"
                    + (unread ? "bold" : "normal") + ";");
            subject.setStyle("-fx-text-fill:" + (unread ? INK : SLATE) + "; -fx-font-size:11.5px; -fx-font-weight:"
                    + (unread ? "bold" : "normal") + ";");
            String emailText = shortEmail(item.from);
            email.setText(emailText);
            boolean showEmail = !item.isSent() && !emailText.isBlank()
                    && !emailText.equalsIgnoreCase(titleText);
            email.setVisible(showEmail);
            email.setManaged(showEmail);
            subject.setText(item.subtitle());
            preview.setText(item.preview == null ? "" : item.preview);
            time.setText(shortTime(item.time));
            String file = item.fileName == null ? "" : item.fileName;
            if (file.startsWith("db_")) {
                badge.setText("MySQL #" + file.substring(3));
                badge.setVisible(true);
            } else if ("__gmail__".equals(file)) {
                badge.setText("IMAP");
                badge.setVisible(true);
            } else {
                badge.setVisible(false);
            }
            setStyle("-fx-background-color:transparent; -fx-padding:0;");
            paint();
            setGraphic(root);
        }
    }

    private void showReaderEmpty() {
        if (readerEmpty == null || readerDetail == null) return;
        readerEmpty.setVisible(true);  readerEmpty.setManaged(true);
        readerDetail.setVisible(false); readerDetail.setManaged(false);
        selectedMail = null;
    }

    private StackPane buildReaderPane() {
        // ── Empty state ──
        FontIcon bigIc = new FontIcon(FontAwesomeSolid.ENVELOPE_OPEN);
        bigIc.setIconSize(46);
        bigIc.setIconColor(Color.web("#BFDBFE"));
        Label emptyTitle = new Label("Chọn một thư để đọc");
        emptyTitle.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:18px; -fx-font-weight:bold;");
        Label emptySub = new Label("Danh sách bên trái hiển thị thư trong Hộp thư hoặc Thư đã gửi.\n"
                + "Thư trả lời từ điện thoại sẽ tự xuất hiện ở đây sau khi đồng bộ Gmail.");
        emptySub.setWrapText(true);
        emptySub.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        emptySub.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:11.5px; -fx-line-spacing:2;");

        Label tip1 = miniTip(FontAwesomeSolid.MOBILE_ALT, "Điện thoại trả lời → tự đồng bộ mỗi 10 giây");
        Label tip2 = miniTip(FontAwesomeSolid.DATABASE, "Mỗi thư đều có mã MySQL để đối chiếu");
        Label tip3 = miniTip(FontAwesomeSolid.PAPER_PLANE, "Gửi thật qua Gmail, tên hiển thị rõ ràng");
        Label tip4 = miniTip(FontAwesomeSolid.LOCK, "Mỗi email có App Password riêng · chỉ lưu trên máy này");
        VBox tips = new VBox(8, tip1, tip2, tip3, tip4);
        tips.setAlignment(Pos.CENTER_LEFT);
        tips.setMaxWidth(420);

        VBox emptyCard = new VBox(14, bigIc, emptyTitle, emptySub, tips);
        emptyCard.setAlignment(Pos.CENTER);
        emptyCard.setMaxWidth(560);
        emptyCard.setPadding(new Insets(40));
        emptyCard.setStyle(cardStyle(18));
        readerEmpty = new VBox(new StackPane(emptyCard));
        readerEmpty.setAlignment(Pos.CENTER);

        // ── Detail ──
        readerSubject = new Label("Chọn một thư để đọc");
        readerSubject.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:22px; -fx-font-weight:bold;");
        readerSubject.setWrapText(true);

        readerAvatar = new Label("?");
        readerAvatar.setMinSize(46, 46);
        readerAvatar.setMaxSize(46, 46);
        readerAvatar.setAlignment(Pos.CENTER);
        readerAvatar.setStyle("-fx-background-color: linear-gradient(to bottom right, " + ACCENT + ", " + SKY
                + "); -fx-text-fill:white; -fx-font-size:15px; -fx-font-weight:bold; -fx-background-radius:50;");

        readerFrom = new Label("—");
        readerFrom.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:13px; -fx-font-weight:bold;");
        readerFromEmail = new Label("—");
        readerFromEmail.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10.5px;");
        readerTo = new Label("—");
        readerTo.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10.5px;");
        readerTime = new Label("—");
        readerTime.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:10px;");
        readerFromIp = new Label("—");
        readerFromIp.setStyle("-fx-text-fill:" + "#0369A1" + "; -fx-font-size:9.5px; -fx-font-weight:bold; "
                + "-fx-background-color:#E0F2FE; -fx-padding:4 9; -fx-background-radius:7;");
        readerDb = new Label("MySQL: —");
        readerDb.setStyle("-fx-text-fill:#047857; -fx-font-size:9.5px; -fx-font-weight:bold; "
                + "-fx-background-color:" + GREEN_BG + "; -fx-padding:4 9; -fx-background-radius:7;");
        readerHint = new Label();
        readerHint.setWrapText(true);
        readerHint.setStyle("-fx-text-fill:" + AMBER + "; -fx-font-size:10.5px; -fx-background-color:" + AMBER_BG
                + "; -fx-padding:8 11; -fx-background-radius:8;");
        readerHint.setVisible(false);
        readerHint.setManaged(false);

        VBox senderInfo = new VBox(2, readerFrom, readerFromEmail, readerTo);
        HBox senderRow = new HBox(12, readerAvatar, senderInfo);
        senderRow.setAlignment(Pos.CENTER_LEFT);
        senderRow.setPadding(new Insets(10, 13, 10, 10));
        senderRow.setStyle("-fx-background-color: linear-gradient(to right, " + SOFT + ", " + SURFACE + ");"
                + " -fx-background-radius:12; -fx-border-color:#DBEAFE; -fx-border-radius:12;"
                + " -fx-effect: dropshadow(gaussian, rgba(37,99,235,0.10), 10, 0.15, 0, 2);");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        VBox timeBox = new VBox(2, new Label("THỜI GIAN"), readerTime);
        ((Label) timeBox.getChildren().get(0)).setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:8px; -fx-font-weight:bold;");
        timeBox.setAlignment(Pos.TOP_RIGHT);
        HBox metaRow = new HBox(10, senderRow, sp, timeBox);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        HBox chipRow = new HBox(8, readerDb, readerFromIp);
        chipRow.setAlignment(Pos.CENTER_LEFT);

        Button replyBtn = primaryButton("TRẢ LỜI", FontAwesomeSolid.REPLY);
        replyBtn.setOnAction(e -> {
            if (selectedMail != null) openCompose(selectedMail.from, "Re: " + selectedMail.subtitle(),
                    "\n\n--- Thư gốc ---\n" + selectedMail.preview);
        });
        Button deleteBtn = toolButton(FontAwesomeSolid.TRASH_ALT, "Xóa", RED);
        deleteBtn.setOnAction(e -> deleteSelectedMail());
        Button resendBtn = toolButton(FontAwesomeSolid.REDO_ALT, "Gửi lại", TEAL);
        resendBtn.setOnAction(e -> {
            if (selectedMail != null) openCompose(selectedMail.to, "Fwd: " + selectedMail.subtitle(), selectedMail.preview);
        });
        HBox toolbar = new HBox(8, replyBtn, resendBtn, deleteBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox headerCard = new VBox(12, readerSubject, metaRow, chipRow, readerHint, toolbar);
        headerCard.setPadding(new Insets(24, 24, 18, 24));
        headerCard.setStyle("-fx-background-color: linear-gradient(to right, " + ACCENT + ", " + TEAL + "), "
                + SURFACE + "; -fx-background-insets: 0, 4 0 0 0; -fx-background-radius:18;"
                + " -fx-border-color:transparent " + LINE + " " + LINE + " " + LINE + ";"
                + " -fx-border-width:0 1 1 1; -fx-border-radius:18;"
                + " -fx-effect: dropshadow(gaussian, rgba(15,23,42,0.08), 22, 0.18, 0, 6);");

        readerBody = new TextArea();
        readerBody.setEditable(false);
        readerBody.setWrapText(true);
        readerBody.setStyle("-fx-control-inner-background:" + SURFACE + "; -fx-background-color:" + SURFACE
                + "; -fx-text-fill:" + SLATE + "; -fx-font-size:13.5px; -fx-font-family:'Segoe UI';"
                + " -fx-border-color:transparent; -fx-background-radius:14; -fx-border-radius:14; -fx-padding:22;"
                + " -fx-highlight-fill:" + SOFT + "; -fx-highlight-text-fill:" + INK + ";");

        readerWeb = new WebView();
        readerWeb.setContextMenuEnabled(false);
        readerWeb.setStyle("-fx-background-color:transparent;");
        readerWeb.setVisible(false);
        readerWeb.setManaged(false);
        readerWeb.setPrefHeight(520);

        readerAttachments = new VBox(8);
        readerAttachments.setPadding(new Insets(4, 14, 12, 14));
        readerAttachments.setVisible(false);
        readerAttachments.setManaged(false);

        StackPane readerContent = new StackPane(readerBody, readerWeb);
        VBox.setVgrow(readerContent, Priority.ALWAYS);

        FontIcon bodyIc = new FontIcon(FontAwesomeSolid.ALIGN_LEFT);
        bodyIc.setIconSize(10);
        bodyIc.setIconColor(Color.web(ACCENT));
        Label bodyTitle = new Label("  NỘI DUNG THƯ");
        bodyTitle.setGraphic(bodyIc);
        bodyTitle.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:9px; -fx-font-weight:bold;"
                + " -fx-padding:10 0 2 6;");

        VBox bodyCard = new VBox(0, bodyTitle, readerContent, readerAttachments);
        bodyCard.setStyle(cardStyle(16));
        bodyCard.setPadding(new Insets(8, 4, 4, 4));
        VBox.setVgrow(bodyCard, Priority.ALWAYS);
        VBox.setVgrow(readerContent, Priority.ALWAYS);

        readerDetail = new VBox(14, headerCard, bodyCard);
        readerDetail.setVisible(false);
        readerDetail.setManaged(false);

        StackPane stack = new StackPane(readerEmpty, readerDetail);
        StackPane.setAlignment(readerEmpty, Pos.CENTER);
        return stack;
    }

    private Label miniTip(FontAwesomeSolid icon, String text) {
        FontIcon ic = new FontIcon(icon);
        ic.setIconSize(11);
        ic.setIconColor(Color.web(ACCENT));
        Label l = new Label("  " + text);
        l.setGraphic(ic);
        l.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10.5px;");
        return l;
    }

    private void showReader(MailItem item) {
        if (readerDetail == null) return;
        readerEmpty.setVisible(false);  readerEmpty.setManaged(false);
        readerDetail.setVisible(true);  readerDetail.setManaged(true);
        readerDetail.setOpacity(0);
        FadeTransition readerFade = new FadeTransition(Duration.millis(180), readerDetail);
        readerFade.setFromValue(0);
        readerFade.setToValue(1);
        readerFade.play();

        readerSubject.setText(item.subtitle());
        String titleText = item.title();
        String readerAvatarEmail = item.isSent() ? firstAddress(item.to) : item.from;
        applyAvatar(readerAvatar, readerAvatarEmail, titleText, 46,
                titleText.isEmpty() ? "?" : titleText.substring(0, 1).toUpperCase());
        readerFrom.setText(titleText);
        readerFromEmail.setText(item.from == null ? "—" : item.from);
        readerTo.setText("đến " + (item.to == null || item.to.isBlank() ? "—" : item.to));
        readerTime.setText(shortTime(item.time));
        readerDb.setText("MySQL: —");
        readerFromIp.setText("IP: —");
        readerHint.setVisible(false); readerHint.setManaged(false);

        final int seq = ++readerSeq;
        GmailImapService.MessageInfo cachedRich = richCache.get(richKey(item));

        if ("__gmail__".equals(item.fileName)) {
            readerFromIp.setText("Google IMAP — hộp thư ngoài");
            readerDb.setText("Không lưu DB");
            showPlainReader(item.preview);
            if (item.htmlBody != null && !item.htmlBody.isBlank()) {
                showRichReader(item, item.htmlBody, item.attachments);
            } else if (item.attachments != null && !item.attachments.isEmpty()) {
                showRichReader(item, "", item.attachments);
            }
            return;
        }

        if (cachedRich != null) {
            showRichReader(item, cachedRich.htmlBody, cachedRich.attachments);
        } else {
            showPlainReader(item.preview == null ? "" : item.preview + "\n\nĐang tải nội dung đầy đủ…");
        }
        Thread worker = new Thread(() -> {
            String resp;
            try {
                resp = core.send("GET|" + currentUser + "|" + item.folder + "|" + item.fileName);
            } catch (Exception ex) {
                resp = "ERROR|" + ex.getMessage();
            }
            final String result = resp;
            Platform.runLater(() -> {
                if (seq != readerSeq) return;
                if (result.startsWith("OK|")) {
                    String content = result.substring(3);
                    String fromIp = extractLine(content, "FromIP");
                    String fromName = extractLine(content, "FromName");
                    String dbId = extractLine(content, "DbId");
                    String viaGmail = extractLine(content, "Gmail");
                    if (!fromName.isBlank()) readerFrom.setText(fromName);
                    readerFromIp.setText("IP người gửi: " + (fromIp.isEmpty() ? "—" : fromIp));
                    if (!dbId.isEmpty()) {
                        readerDb.setText("MySQL #" + dbId + ("yes".equalsIgnoreCase(viaGmail) ? " · Gmail" : " · LAN"));
                    } else {
                        readerDb.setText("MySQL: —");
                    }
                    String body = extractBody(content);
                    GmailImapService.MessageInfo knownRich = richCache.get(richKey(item));
                    if (knownRich != null) {
                        showRichReader(item, knownRich.htmlBody, knownRich.attachments);
                    } else {
                        // Thư đồng bộ nhanh chỉ lưu header; body có thể rỗng cho tới khi
                        // fetchRichForReader tải HTML/ảnh theo Message-ID.
                        showPlainReader(body.isEmpty() ? "Đang tải nội dung đầy đủ…" : body);
                    }

                    boolean external = "external".equalsIgnoreCase(item.folder)
                            || (item.from != null && !item.from.equalsIgnoreCase(currentEmail)
                                && item.fileName.startsWith("db_") && !item.isSent());
                    if (external) {
                        if (fromIp.isEmpty()) readerFromIp.setText("Nguồn: Gmail IMAP");
                        readerHint.setText("Thư được đồng bộ từ Gmail/điện thoại và đã ghi vào MySQL"
                                + (dbId.isEmpty() ? "." : " (emails.id = " + dbId + ")."));
                        readerHint.setVisible(true); readerHint.setManaged(true);
                    }
                    if (knownRich == null && item.messageId != null && !item.messageId.isBlank()) {
                        fetchRichForReader(item);
                    }
                } else {
                    showPlainReader(item.preview);
                    readerFromIp.setText("IP người gửi: —");
                }
            });
        }, "mail-read");
        worker.setDaemon(true);
        worker.start();
    }

    /** Hiển thị nội dung thuần văn bản (fallback khi chưa có HTML/ảnh). */
    private void showPlainReader(String text) {
        if (readerWeb != null) {
            readerWeb.setVisible(false);
            readerWeb.setManaged(false);
        }
        if (readerBody != null) {
            readerBody.setVisible(true);
            readerBody.setManaged(true);
            readerBody.setText(text == null ? "" : text);
        }
        if (readerAttachments != null) {
            readerAttachments.getChildren().clear();
            readerAttachments.setVisible(false);
            readerAttachments.setManaged(false);
        }
    }

    /** Hiển thị HTML thật của thư, ảnh remote tự tải và ảnh cid được nhúng thành data URI. */
    private void showRichReader(MailItem item, String html,
                                List<GmailImapService.AttachmentInfo> attachments) {
        if (readerWeb == null) {
            showPlainReader(item == null ? "" : item.preview);
            return;
        }
        List<GmailImapService.AttachmentInfo> safeAttachments =
                attachments == null ? new ArrayList<>() : attachments;
        String plain = item == null ? "" : item.preview;
        readerWeb.getEngine().loadContent(buildReaderHtml(html, plain, safeAttachments), "text/html");
        readerWeb.setVisible(true);
        readerWeb.setManaged(true);
        if (readerBody != null) {
            readerBody.setVisible(false);
            readerBody.setManaged(false);
        }
        renderAttachmentBar(safeAttachments);
    }

    /** Tải lại đúng thư từ Gmail theo Message-ID để lấy HTML/ảnh đính kèm đã đồng bộ vào MySQL. */
    private void fetchRichForReader(MailItem item) {
        if (item == null || item.messageId == null || item.messageId.isBlank()) return;
        final String key = richKey(item);
        GmailImapService.MessageInfo cached = richCache.get(key);
        if (cached != null) {
            showRichReader(item, cached.htmlBody, cached.attachments);
            return;
        }
        mailConfig.activate(currentEmail);
        final String account = mailConfig.getGmailAddress();
        final String password = mailConfig.getGmailAppPassword();
        if (account == null || account.isBlank() || password == null || password.isBlank()
                || !MailConfig.isValidAppPassword(password)) {
            return;
        }
        final boolean sentFolder = item.isSent();
        final String messageId = item.messageId;
        Thread worker = new Thread(() -> {
            try {
                GmailImapService.MessageInfo rich =
                        GmailImapService.fetchByMessageId(account, password, messageId, sentFolder);
                if (rich == null) return;
                richCache.put(key, rich);
                Platform.runLater(() -> {
                    // Không chặn kết quả chỉ vì danh sách vừa tự refresh:
                    // chỉ bỏ qua nếu người dùng đã mở một thư khác.
                    if (!sameMail(selectedMail, item)) return;
                    if ((rich.htmlBody != null && !rich.htmlBody.isBlank())
                            || (rich.attachments != null && !rich.attachments.isEmpty())) {
                        showRichReader(item, rich.htmlBody, rich.attachments);
                    }
                });
            } catch (Exception ignored) {
                // Không có mạng/Gmail tạm lỗi: vẫn giữ nội dung text đã hiển thị.
            }
        }, "mail-rich");
        worker.setDaemon(true);
        worker.start();
    }

    private String richKey(MailItem item) {
        if (item == null) return "";
        if (item.messageId != null && !item.messageId.isBlank()) return item.messageId;
        return (item.folder == null ? "" : item.folder) + "|" + (item.fileName == null ? "" : item.fileName);
    }

    private boolean sameMail(MailItem a, MailItem b) {
        if (a == null || b == null) return false;
        if (a.messageId != null && !a.messageId.isBlank()
                && b.messageId != null && !b.messageId.isBlank()) {
            return a.messageId.equalsIgnoreCase(b.messageId);
        }
        return java.util.Objects.equals(a.folder, b.folder)
                && java.util.Objects.equals(a.fileName, b.fileName);
    }

    private String buildReaderHtml(String html, String plain,
                                   List<GmailImapService.AttachmentInfo> attachments) {
        String body;
        if (html != null && !html.isBlank()) {
            body = sanitizeHtml(html);
        } else {
            body = "<div style=\"white-space:pre-wrap;\">" + escapeHtml(plain == null ? "" : plain) + "</div>";
        }

        for (GmailImapService.AttachmentInfo attachment : attachments) {
            if (attachment == null || !attachment.isImage() || attachment.data == null
                    || attachment.data.length == 0) continue;
            String dataUri = dataUri(attachment);
            if (attachment.contentId != null && !attachment.contentId.isBlank()) {
                String regex = "(?i)cid:" + Pattern.quote(attachment.contentId);
                if (Pattern.compile(regex).matcher(body).find()) {
                    body = body.replaceAll(regex, Matcher.quoteReplacement(dataUri));
                    continue;
                }
            }
            body += "<div style=\"margin-top:14px;\"><img src=\"" + dataUri + "\" alt=\""
                    + escapeHtml(attachment.fileName) + "\"></div>";
        }

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>"
                + "html,body{background:#FFFFFF;overflow-x:hidden;}"
                + "body{font-family:'Segoe UI',Arial,sans-serif;font-size:13px;color:#334155;"
                + "line-height:1.65;margin:0;padding:6px 4px 10px 4px;word-wrap:break-word;max-width:100%;}"
                + "*{box-sizing:border-box;}"
                + "img{max-width:100%;height:auto;border-radius:8px;}"
                + "a{color:#2563EB;text-decoration:none;}"
                + "blockquote{border-left:3px solid #E2E8F0;margin:8px 0;padding:2px 0 2px 12px;color:#64748B;}"
                + "table{max-width:100%;border-collapse:collapse;}"
                + "td,th{max-width:100%;}"
                + "pre{white-space:pre-wrap;font-family:'Segoe UI',Arial,sans-serif;}"
                + "</style></head><body>" + body + "</body></html>";
    }

    private String sanitizeHtml(String html) {
        if (html == null) return "";
        return html
                .replaceAll("(?is)<script.*?</script>", "")
                .replaceAll("(?is)<style.*?</style>", "")
                .replaceAll("(?is)<(iframe|object|embed|link|meta)[^>]*>", "")
                .replaceAll("(?i)\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "")
                .replaceAll("(?i)javascript:", "");
    }

    private String dataUri(GmailImapService.AttachmentInfo attachment) {
        String type = attachment.contentType == null || attachment.contentType.isBlank()
                ? "application/octet-stream" : attachment.contentType;
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(attachment.data);
    }

    private void renderAttachmentBar(List<GmailImapService.AttachmentInfo> attachments) {
        if (readerAttachments == null) return;
        readerAttachments.getChildren().clear();
        if (attachments == null || attachments.isEmpty()) {
            readerAttachments.setVisible(false);
            readerAttachments.setManaged(false);
            return;
        }

        Label title = new Label("TỆP ĐÍNH KÈM (" + attachments.size() + ")");
        title.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:9px; -fx-font-weight:bold;");
        readerAttachments.getChildren().add(title);

        FlowPane grid = new FlowPane(8, 8);
        for (GmailImapService.AttachmentInfo attachment : attachments) {
            boolean image = attachment.isImage();
            FontIcon icon = new FontIcon(image ? FontAwesomeSolid.IMAGE : FontAwesomeSolid.FILE_ALT);
            icon.setIconSize(13);
            icon.setIconColor(Color.web(image ? ACCENT : MUTED));

            Label name = new Label(shorten(attachment.fileName, 34));
            name.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:10.5px; -fx-font-weight:bold;");
            Label size = new Label(formatBytes(attachment.data == null ? 0 : attachment.data.length));
            size.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9px;");
            VBox info = new VBox(1, name, size);

            Button save = new Button("Lưu");
            save.setStyle("-fx-background-color:" + SOFT + "; -fx-text-fill:" + ACCENT
                    + "; -fx-font-size:9.5px; -fx-font-weight:bold; -fx-padding:4 9;"
                    + " -fx-background-radius:7; -fx-cursor:hand;");
            save.setOnAction(e -> saveAttachment(attachment));

            HBox chip = new HBox(8, icon, info, save);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.setPadding(new Insets(7, 9, 7, 9));
            chip.setStyle("-fx-background-color:" + BG + "; -fx-background-radius:9;"
                    + " -fx-border-color:" + LINE + "; -fx-border-radius:9;");
            grid.getChildren().add(chip);
        }
        readerAttachments.getChildren().add(grid);
        readerAttachments.setVisible(true);
        readerAttachments.setManaged(true);
    }

    private void saveAttachment(GmailImapService.AttachmentInfo attachment) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Lưu tệp đính kèm");
        chooser.setInitialFileName(attachment.fileName == null || attachment.fileName.isBlank()
                ? "tep-dinh-kem" : attachment.fileName);
        File file = chooser.showSaveDialog(primaryStage);
        if (file == null) return;
        try {
            Files.write(file.toPath(), attachment.data);
            showToast("Đã lưu " + file.getName(), GREEN);
        } catch (Exception ex) {
            showAlert("Không lưu được tệp:\n" + ex.getMessage());
        }
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String shorten(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void deleteSelectedMail() {
        if (selectedMail == null) return;
        if ("__gmail__".equals(selectedMail.fileName)) {
            showToast("Thư Google chỉ để xem, không xóa trong app.", AMBER);
            return;
        }
        MailItem target = selectedMail;
        String content = target.subtitle();
        Thread worker = new Thread(() -> {
            String resp;
            try { resp = core.send("DELETE|" + currentUser + "|" + target.fileName); }
            catch (Exception ex) { resp = "ERROR|" + ex.getMessage(); }
            final String result = resp;
            Platform.runLater(() -> {
                if (result.startsWith("OK")) {
                    log("Đã xóa thư: " + content);
                    showToast("Đã xóa thư", GREEN);
                    appendProof("DELETE OK → " + content);
                    selectedMail = null;
                    refreshMailboxAsync(false);
                } else {
                    showAlert("Không xóa được: " + result);
                }
            });
        }, "mail-delete");
        worker.setDaemon(true);
        worker.start();
    }

    private VBox emptyState() {
        FontIcon fi = new FontIcon(FontAwesomeSolid.INBOX);
        fi.setIconSize(40);
        fi.setIconColor(Color.web("#CBD5E1"));
        Label l = new Label("Chưa có thư nào");
        l.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:12.5px; -fx-font-weight:bold;");
        Label l2 = new Label("Thư mới sẽ xuất hiện ở đây");
        l2.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:10.5px;");
        VBox v = new VBox(8, fi, l, l2);
        v.setAlignment(Pos.CENTER);
        return v;
    }

    // ══════════════════════════════════════════════════════════════════
    //  CONTACTS VIEW
    // ══════════════════════════════════════════════════════════════════
    private void showContactsView() {
        VBox pane = new VBox(16);
        pane.setPadding(new Insets(16, 18, 16, 18));

        VBox card = new VBox(14);
        card.setPadding(new Insets(20, 22, 20, 22));
        card.setStyle(cardStyle(16));

        FontIcon ic = new FontIcon(FontAwesomeSolid.USERS);
        ic.setIconSize(16);
        ic.setIconColor(Color.web(ACCENT));
        Label title = new Label("  Danh bạ & trực tuyến");
        title.setGraphic(ic);
        title.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:16px; -fx-font-weight:bold;");
        Label sub = new Label("Danh bạ tải từ database server. Giữ Ctrl để chọn nhiều người rồi bấm GỬI NHIỀU EMAIL, hoặc nhấp đúp để soạn nhanh.");
        sub.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:11px;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Label contactsStatusLabel = new Label("Đang tải…");
        contactsStatusLabel.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10px; -fx-font-weight:bold; "
                + "-fx-background-color:" + BG + "; -fx-padding:6 12; -fx-background-radius:16;");
        card.setUserData(contactsStatusLabel);

        Button composeMany = primaryButton("GỬI NHIỀU EMAIL", FontAwesomeSolid.PAPER_PLANE);
        composeMany.setOnAction(e -> {
            List<Contact> picked = new ArrayList<>(contactList.getSelectionModel().getSelectedItems());
            if (picked.isEmpty()) {
                showToast("Hãy chọn một hoặc nhiều liên hệ trong danh sách", AMBER);
                return;
            }
            List<String> addresses = new ArrayList<>();
            for (Contact c : picked) {
                String address = c.email != null && c.email.contains("@") ? c.email : c.username;
                if (!address.isBlank() && !addresses.contains(address)) addresses.add(address);
            }
            openCompose(String.join(", ", addresses), "", "");
        });
        Button refresh = iconButton(FontAwesomeSolid.SYNC_ALT, "Tải lại danh bạ", MUTED);
        refresh.setOnAction(e -> loadContacts());
        HBox header = new HBox(8, new VBox(3, title, sub), sp, contactsStatusLabel, composeMany, refresh);
        header.setAlignment(Pos.CENTER_LEFT);

        contactList = new ListView<>();
        contactList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        contactList.setStyle("-fx-background-color:transparent; -fx-border-color:transparent; "
                + "-fx-control-inner-background:" + SURFACE + ";");
        contactList.setPrefHeight(520);
        contactList.setCellFactory(lv -> new ContactCell());
        contactList.setPlaceholder(new Label("Chưa có tài khoản nào trên server"));
        VBox.setVgrow(contactList, Priority.ALWAYS);

        card.getChildren().addAll(header, contactList);
        VBox.setVgrow(card, Priority.ALWAYS);
        pane.getChildren().add(card);
        centerContent.getChildren().setAll(pane);

        contacts.clear();
        loadContacts();
    }

    private class ContactCell extends ListCell<Contact> {
        @Override protected void updateItem(Contact c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) {
                setGraphic(null); setText(null); setStyle("-fx-background-color:transparent;");
                return;
            }

            Label av = new Label(c.displayName.substring(0, 1).toUpperCase());
            av.setMinSize(42, 42);
            av.setMaxSize(42, 42);
            av.setAlignment(Pos.CENTER);
            applyAvatar(av, c.email != null && c.email.contains("@") ? c.email : c.username,
                    c.displayName, 42, c.displayName.substring(0, 1).toUpperCase());

            Label name = new Label(c.displayName);
            name.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:13px; -fx-font-weight:bold;");
            Label handle = new Label("@" + c.username);
            handle.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9.5px;");
            HBox nameRow = new HBox(7, name, handle);
            nameRow.setAlignment(Pos.CENTER_LEFT);
            Label email = new Label(c.email == null || c.email.isBlank() ? "(chưa có email)" : c.email);
            email.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:11px;");
            VBox who = new VBox(2, nameRow, email);

            Label state = new Label(c.online ? "● Đang online" : "● Offline");
            state.setStyle("-fx-text-fill:" + (c.online ? GREEN : DIM) + "; -fx-font-size:9.5px; -fx-font-weight:bold; "
                    + "-fx-background-color:" + (c.online ? GREEN_BG : BG) + "; -fx-padding:4 10; -fx-background-radius:12;");

            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);

            Button send = ghostButton(FontAwesomeSolid.PAPER_PLANE, "Soạn thư");
            send.setOnMouseClicked(e -> e.consume());
            send.setOnAction(e -> openCompose(c.email != null && c.email.contains("@") ? c.email : c.username, "", ""));

            HBox row = new HBox(12, av, who, sp, state, send);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(10, 12, 10, 12));
            row.setStyle("-fx-background-color:" + SURFACE + "; -fx-background-radius:12; "
                    + "-fx-border-color:" + LINE + "; -fx-border-radius:12;");

            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    openCompose(c.email != null && c.email.contains("@") ? c.email : c.username, "", "");
                }
            });
            setStyle("-fx-background-color:transparent; -fx-padding:5 0;");
            setGraphic(row);
        }
    }

    private void loadContacts() {
        Label status = contactList == null || contactList.getParent() == null
                ? null : findContactsStatus();
        if (status != null) status.setText("Đang tải…");
        Thread worker = new Thread(() -> {
            try {
                String resp = core.send("LIST");
                List<Contact> found = new ArrayList<>();
                for (String line : resp.split("\n")) {
                    String s = line.trim();
                    if (!s.startsWith("USER|")) continue;
                    String[] p = s.split("\\|", -1);
                    if (p.length >= 3) {
                        boolean online = p.length >= 4 && "1".equals(p[3]);
                        String display = p.length >= 5 ? p[4] : p[1];
                        found.add(new Contact(p[1], p[2], display, online));
                    }
                }
                Platform.runLater(() -> {
                    contacts.clear();
                    contacts.addAll(found);
                    if (contactList != null) contactList.getItems().setAll(found);
                    Label st = findContactsStatus();
                    long online = found.stream().filter(c -> c.online).count();
                    if (st != null) st.setText(found.size() + " tài khoản · " + online + " online");
                    log("Danh bạ: " + found.size() + " tài khoản, " + online + " đang online");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Label st = findContactsStatus();
                    if (st != null) st.setText("Không tải được danh bạ");
                    showAlert("Không tải được danh bạ: " + e.getMessage());
                });
            }
        }, "contacts-load");
        worker.setDaemon(true);
        worker.start();
    }

    private Label findContactsStatus() {
        if (contactList == null) return null;
        Node p = contactList.getParent();
        if (p instanceof VBox v && !v.getChildren().isEmpty() && v.getChildren().get(0) instanceof HBox h) {
            for (Node n : h.getChildren()) {
                if (n instanceof Label l && l.getText() != null
                        && (l.getText().startsWith("Đang tải") || l.getText().contains("tài khoản")
                            || l.getText().startsWith("Không tải"))) return l;
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    //  COMPOSE
    // ══════════════════════════════════════════════════════════════════
    private void openCompose(String to, String subject, String content) {
        if (currentUser == null) { showAlert("Hãy đăng nhập trước khi soạn thư."); return; }

        FontIcon ic = new FontIcon(FontAwesomeSolid.PEN);
        ic.setIconSize(16);
        ic.setIconColor(Color.WHITE);
        StackPane icBox = new StackPane(ic);
        icBox.setMinSize(42, 42);
        icBox.setMaxSize(42, 42);
        icBox.setStyle("-fx-background-color: linear-gradient(to bottom right, " + ACCENT + ", " + SKY
                + "); -fx-background-radius:13;");

        Label title = new Label("SOẠN THƯ MỚI");
        title.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:15px; -fx-font-weight:bold;");
        Label subTitle = new Label("Từ: " + ownSender() + "  ·  IP máy này: " + myIP);
        subTitle.setStyle("-fx-text-fill:" + ACCENT + "; -fx-font-size:10px; -fx-font-weight:bold; "
                + "-fx-background-color:" + SOFT + "; -fx-padding:3 9; -fx-background-radius:7;");
        VBox titleBox = new VBox(4, title, subTitle);
        HBox titleSection = new HBox(12, icBox, titleBox);
        titleSection.setAlignment(Pos.CENTER_LEFT);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button closeBtn = iconButton(FontAwesomeSolid.TIMES, "Đóng", MUTED);
        closeBtn.setOnAction(e -> closeModal());

        HBox header = new HBox(10, titleSection, sp, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 20, 14, 20));
        header.setStyle("-fx-border-color:transparent transparent " + LINE + " transparent; -fx-border-width:0 0 1 0;");

        boolean replying = to != null && !to.isEmpty();
        boolean[] gmailMode = { replying ? to.contains("@") : mailConfig.isGmailMode() };
        String defaultAddress = defaultRecipient();

        Button gmailBtn = new Button("Gửi thật qua Gmail");
        Button lanBtn = new Button("Gửi LAN nội bộ");
        gmailBtn.setFocusTraversable(false);
        lanBtn.setFocusTraversable(false);
        HBox modeRow = new HBox(8, gmailBtn, lanBtn);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        Label modeHint = new Label();
        modeHint.setWrapText(true);
        modeHint.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10.5px;");

        TextField toF = styledTextField("nguoi-nhan@example.com");
        toF.setText(replying ? to : (gmailMode[0] ? defaultAddress : ""));
        toF.setPrefHeight(42);

        MenuButton addressBook = new MenuButton("Danh bạ");
        addressBook.setStyle("-fx-background-color:" + SOFT + "; -fx-text-fill:" + ACCENT
                + "; -fx-font-size:10px; -fx-font-weight:bold; -fx-background-radius:8; -fx-padding:6 10; -fx-cursor:hand;");
        for (Contact c : contacts) {
            MenuItem mi = new MenuItem(c.displayName + "  ·  " + c.email);
            mi.setOnAction(e -> {
                toF.setText(c.email != null && c.email.contains("@") ? c.email : c.username);
                if (gmailMode[0] && !toF.getText().contains("@")) gmailMode[0] = false;
            });
            addressBook.getItems().add(mi);
        }
        if (addressBook.getItems().isEmpty()) addressBook.setDisable(true);
        HBox.setHgrow(toF, Priority.ALWAYS);

        Label toLbl = new Label("Đến");
        toLbl.setStyle("-fx-text-fill:" + ACCENT + "; -fx-font-size:11.5px; -fx-font-weight:bold;");
        HBox toRow = fieldRow(FontAwesomeSolid.USER_CIRCLE, toLbl, toF, addressBook);

        TextField ccF = styledTextField("cc@example.com");
        TextField bccF = styledTextField("bcc@example.com");
        ccF.setPrefHeight(42);
        bccF.setPrefHeight(42);
        Label ccLbl = new Label("Cc");
        ccLbl.setStyle("-fx-text-fill:" + TEAL + "; -fx-font-size:11.5px; -fx-font-weight:bold;");
        Label bccLbl = new Label("Bcc");
        bccLbl.setStyle("-fx-text-fill:" + TEAL + "; -fx-font-size:11.5px; -fx-font-weight:bold;");
        HBox ccRow = fieldRow(FontAwesomeSolid.USERS, ccLbl, ccF, null);
        HBox bccRow = fieldRow(FontAwesomeSolid.USER_SECRET, bccLbl, bccF, null);
        ccRow.setVisible(false); ccRow.setManaged(false);
        bccRow.setVisible(false); bccRow.setManaged(false);
        Button ccToggle = new Button("Cc / Bcc");
        ccToggle.setFocusTraversable(false);
        String ccNormal = "-fx-background-color:transparent; -fx-text-fill:" + MUTED
                + "; -fx-font-size:10px; -fx-font-weight:bold; -fx-padding:4 8; -fx-cursor:hand;";
        String ccActive = "-fx-background-color:" + SOFT + "; -fx-text-fill:" + ACCENT
                + "; -fx-font-size:10px; -fx-font-weight:bold; -fx-padding:4 8; -fx-background-radius:7; -fx-cursor:hand;";
        ccToggle.setStyle(ccNormal);
        ccToggle.setOnAction(e -> {
            boolean show = !ccRow.isVisible();
            ccRow.setVisible(show); ccRow.setManaged(show);
            bccRow.setVisible(show); bccRow.setManaged(show);
            ccToggle.setStyle(show ? ccActive : ccNormal);
            if (show) ccF.requestFocus();
        });
        HBox ccToggleRow = new HBox(ccToggle);
        ccToggleRow.setAlignment(Pos.CENTER_RIGHT);

        TextField subF = styledTextField("Tiêu đề thư…");
        subF.setText(subject);
        subF.setPrefHeight(42);
        HBox.setHgrow(subF, Priority.ALWAYS);
        Label subLbl = new Label("Tiêu đề");
        subLbl.setStyle("-fx-text-fill:" + AMBER + "; -fx-font-size:11.5px; -fx-font-weight:bold;");
        HBox subRow = fieldRow(FontAwesomeSolid.TAG, subLbl, subF, null);

        TextArea bodyA = new TextArea();
        bodyA.setPromptText("Viết nội dung thư ở đây…");
        bodyA.setText(content);
        bodyA.setWrapText(true);
        bodyA.setPrefRowCount(8);
        bodyA.setStyle("-fx-control-inner-background:" + BG + "; -fx-background-color:" + BG
                + "; -fx-text-fill:" + INK + "; -fx-prompt-text-fill:" + DIM + "; -fx-font-size:12.5px;"
                + " -fx-font-family:'Segoe UI'; -fx-background-radius:12; -fx-border-color:" + LINE
                + "; -fx-border-radius:12; -fx-padding:14;");
        VBox.setVgrow(bodyA, Priority.ALWAYS);

        Button boldBtn = formatToolbarButton("B", "In đậm", true, false);
        Button italicBtn = formatToolbarButton("I", "In nghiêng", false, true);
        Button underlineBtn = formatToolbarButton("U", "Gạch chân", false, false);
        underlineBtn.setStyle(underlineBtn.getStyle() + " -fx-underline:true;");
        boldBtn.setOnAction(e -> wrapComposeSelection(bodyA, "*", "*"));
        italicBtn.setOnAction(e -> wrapComposeSelection(bodyA, "_", "_"));
        underlineBtn.setOnAction(e -> wrapComposeSelection(bodyA, "__", "__"));
        Region tbDivider = new Region();
        tbDivider.setMinWidth(1); tbDivider.setMaxWidth(1); tbDivider.setMinHeight(16);
        tbDivider.setStyle("-fx-background-color:" + LINE + ";");
        Label formatHint = new Label("Định dạng văn bản");
        formatHint.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9.5px;");
        HBox formatBar = new HBox(4, boldBtn, italicBtn, underlineBtn, tbDivider, formatHint);
        formatBar.setAlignment(Pos.CENTER_LEFT);
        formatBar.setPadding(new Insets(2, 0, 2, 2));

        // ── Thanh công cụ đính kèm: gửi file/ảnh thật qua Gmail SMTP ──
        List<File> composeFiles = new ArrayList<>();
        VBox composeAttachments = new VBox(6);
        composeAttachments.setVisible(false);
        composeAttachments.setManaged(false);

        Button attachBtn = toolButton(FontAwesomeSolid.PAPERCLIP, "Đính kèm file", ACCENT);
        Button imageBtn = toolButton(FontAwesomeSolid.IMAGE, "Chèn ảnh", TEAL);
        attachBtn.setOnAction(e -> chooseComposeFiles(composeFiles, composeAttachments, false));
        imageBtn.setOnAction(e -> chooseComposeFiles(composeFiles, composeAttachments, true));

        Label attachHint = new Label("Tối đa 20 MB · ảnh hiển thị trực tiếp trong thư");
        attachHint.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9.5px;");
        HBox attachBar = new HBox(8, attachBtn, imageBtn, attachHint);
        attachBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(attachHint, Priority.ALWAYS);

        // Thẻ danh tính: cho thấy đúng tên sẽ hiện trên điện thoại
        Label personaAvatar = new Label(senderDisplayName().isEmpty() ? "?" : senderDisplayName().substring(0, 1).toUpperCase());
        personaAvatar.setMinSize(38, 38);
        personaAvatar.setMaxSize(38, 38);
        personaAvatar.setAlignment(Pos.CENTER);
        personaAvatar.setStyle("-fx-background-color: linear-gradient(to bottom right, " + ACCENT + ", " + SKY
                + "); -fx-text-fill:white; -fx-font-size:13px; -fx-font-weight:bold; -fx-background-radius:50;");
        Label personaName = new Label(senderDisplayName());
        personaName.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:12px; -fx-font-weight:bold;");
        Label personaAddr = new Label(senderAddress());
        personaAddr.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10px;");
        Label personaHint = new Label("Tên này sẽ hiển thị trên điện thoại người nhận");
        personaHint.setStyle("-fx-text-fill:" + GREEN + "; -fx-font-size:9.5px; -fx-font-weight:bold;");
        VBox personaText = new VBox(1, personaName, personaAddr, personaHint);
        HBox persona = new HBox(10, personaAvatar, personaText);
        persona.setAlignment(Pos.CENTER_LEFT);
        persona.setPadding(new Insets(9, 12, 9, 12));
        persona.setStyle("-fx-background-color:" + GREEN_BG + "; -fx-background-radius:11; "
                + "-fx-border-color:#A7F3D0; -fx-border-radius:11;");

        Label gmailStateLabel = new Label();
        gmailStateLabel.setStyle("-fx-font-size:10.5px; -fx-font-weight:bold;");
        Label gmailHelpLabel = new Label();
        gmailHelpLabel.setWrapText(true);
        gmailHelpLabel.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:10px;");
        VBox gmailTextBox = new VBox(2, gmailStateLabel, gmailHelpLabel);
        HBox.setHgrow(gmailTextBox, Priority.ALWAYS);

        final Runnable[] refreshScenario = new Runnable[1];
        Button gmailSetupBtn = ghostButton(FontAwesomeSolid.COG, "Thiết lập Google");
        gmailSetupBtn.setOnAction(e -> {
            openSettings();
            if (refreshScenario[0] != null) refreshScenario[0].run();
        });
        HBox gmailRow = new HBox(10, gmailTextBox, gmailSetupBtn);
        gmailRow.setAlignment(Pos.CENTER_LEFT);
        VBox gmailBox = new VBox(gmailRow);
        gmailBox.setPadding(new Insets(9, 11, 9, 11));

        Label formNote = new Label();
        formNote.setWrapText(true);
        formNote.setStyle("-fx-text-fill:" + RED + "; -fx-font-size:11px; -fx-background-color:" + RED_BG
                + "; -fx-padding:8 10; -fx-background-radius:8;");
        formNote.setVisible(false);
        formNote.setManaged(false);

        Label sendLbl = new Label("  GỬI THẬT QUA GMAIL");
        FontIcon sendIc = new FontIcon(FontAwesomeSolid.PAPER_PLANE);
        sendIc.setIconSize(13);
        sendIc.setIconColor(Color.WHITE);
        sendLbl.setGraphic(sendIc);
        sendLbl.setStyle("-fx-text-fill:white; -fx-font-weight:bold; -fx-font-size:11.5px;");

        Button sendBtn = new Button();
        sendBtn.setGraphic(sendLbl);
        sendBtn.setPrefHeight(42);
        sendBtn.setPrefWidth(225);
        String sendNormal = "-fx-background-color: linear-gradient(to right, " + ACCENT + ", " + TEAL
                + "); -fx-background-radius:21; -fx-cursor:hand;";
        String sendHover = "-fx-background-color: linear-gradient(to right, " + ACCENT_DARK + ", " + ACCENT
                + "); -fx-background-radius:21; -fx-cursor:hand; -fx-effect: dropshadow(gaussian, rgba(37,99,235,0.38), 16, 0, 0, 4);";
        sendBtn.setStyle(sendNormal);
        sendBtn.setOnMouseEntered(e -> sendBtn.setStyle(sendHover));
        sendBtn.setOnMouseExited(e -> sendBtn.setStyle(sendNormal));

        Runnable applyScenario = () -> {
            boolean gmail = gmailMode[0];
            String outgoing = senderAddress();
            styleModeChoice(gmailBtn, gmail);
            styleModeChoice(lanBtn, !gmail);
            subTitle.setText("Từ: " + senderDisplayName() + "  ·  " + outgoing);
            personaName.setText(senderDisplayName());
            personaAddr.setText(outgoing);
            if (gmail) {
                modeHint.setText("Gửi thư thật qua Google SMTP bằng chính email của tài khoản đang đăng nhập: "
                        + outgoing + ". Mỗi email cần App Password riêng của chính tài khoản đó. "
                        + "Sau khi gửi, mỗi thư được ghi 1 bản ghi vào MySQL.");
                toF.setPromptText(defaultAddress);
                sendLbl.setText("  GỬI THẬT QUA GMAIL");
            } else {
                modeHint.setText("Gửi trong LAN tới username hoặc email đã đăng ký trên server. Không cần App Password, "
                        + "thư vẫn được lưu MySQL.");
                toF.setPromptText("username hoặc email, ví dụ chamm4930");
                sendLbl.setText("  GỬI TRONG LAN");
            }

            boolean configured = mailConfig.hasGmailCredentials();
            boolean valid = mailConfig.hasValidGmailCredentials();
            if (Boolean.FALSE.equals(gmailVerified)) {
                gmailStateLabel.setText("Google từ chối App Password");
                gmailStateLabel.setStyle("-fx-text-fill:" + RED + "; -fx-font-size:10.5px; -fx-font-weight:bold;");
                gmailHelpLabel.setText("Tạo App Password mới tại myaccount.google.com/apppasswords rồi cập nhật lại.");
                gmailBox.setStyle("-fx-background-color:" + RED_BG + "; -fx-border-color:#FECACA; -fx-border-radius:10; -fx-background-radius:10;");
                gmailSetupBtn.setText("Sửa cấu hình");
            } else if (valid) {
                gmailStateLabel.setText("Google sẵn sàng: " + mailConfig.getGmailAddress());
                gmailStateLabel.setStyle("-fx-text-fill:#047857; -fx-font-size:10.5px; -fx-font-weight:bold;");
                gmailHelpLabel.setText("Đang dùng App Password riêng của chính email này, đã lưu trên máy.");
                gmailBox.setStyle("-fx-background-color:" + GREEN_BG + "; -fx-border-color:#BBF7D0; -fx-border-radius:10; -fx-background-radius:10;");
                gmailSetupBtn.setText("Đổi cấu hình");
            } else if (configured) {
                gmailStateLabel.setText("App Password chưa đúng 16 ký tự");
                gmailStateLabel.setStyle("-fx-text-fill:" + AMBER + "; -fx-font-size:10.5px; -fx-font-weight:bold;");
                gmailHelpLabel.setText("Mở cấu hình và nhập đúng App Password do Google cấp cho email này.");
                gmailBox.setStyle("-fx-background-color:" + AMBER_BG + "; -fx-border-color:#FDE68A; -fx-border-radius:10; -fx-background-radius:10;");
                gmailSetupBtn.setText("Sửa cấu hình");
            } else {
                gmailStateLabel.setText("Chưa có App Password cho " + senderAddress());
                gmailStateLabel.setStyle("-fx-text-fill:" + AMBER + "; -fx-font-size:10.5px; -fx-font-weight:bold;");
                gmailHelpLabel.setText("Mỗi email cần App Password riêng. Tạo tại myaccount.google.com/apppasswords rồi nhập vào Cấu hình.");
                gmailBox.setStyle("-fx-background-color:" + AMBER_BG + "; -fx-border-color:#FDE68A; -fx-border-radius:10; -fx-background-radius:10;");
                gmailSetupBtn.setText("Thiết lập ngay");
            }
            gmailBox.setVisible(gmail);
            gmailBox.setManaged(gmail);
        };

        gmailBtn.setOnAction(e -> {
            if (!gmailMode[0]) {
                gmailMode[0] = true;
                if (!toF.getText().trim().contains("@")) toF.setText(defaultAddress);
            }
            showFormNote(formNote, "");
            applyScenario.run();
        });
        lanBtn.setOnAction(e -> {
            if (gmailMode[0]) {
                gmailMode[0] = false;
                if (toF.getText().trim().equalsIgnoreCase(defaultAddress) || toF.getText().contains("@")) toF.clear();
            }
            showFormNote(formNote, "");
            applyScenario.run();
        });
        refreshScenario[0] = applyScenario;
        applyScenario.run();

        sendBtn.setOnAction(e -> {
            String recipient = toF.getText().trim();
            List<String> toAddresses = parseAddressList(recipient);
            String subjectText = subF.getText().trim().replace("|", "/");
            String message = bodyA.getText().trim().replace("|", "/");
            boolean useGmail = gmailMode[0];
            final List<File> sendFiles = new ArrayList<>(composeFiles);
            List<String> ccAddresses = parseAddressList(ccF.getText());
            List<String> bccAddresses = parseAddressList(bccF.getText());
            showFormNote(formNote, "");

            if (recipient.isEmpty() || message.isEmpty() || subjectText.isEmpty()) {
                showFormNote(formNote, "Vui lòng điền đầy đủ Người nhận, Tiêu đề và Nội dung.");
                return;
            }
            if (!useGmail && !sendFiles.isEmpty()) {
                showFormNote(formNote, "Gửi tệp đính kèm hiện hỗ trợ chế độ Gmail. "
                        + "Hãy chuyển sang Gmail hoặc bỏ các tệp đã chọn.");
                return;
            }
            long attachmentBytes = 0;
            for (File file : sendFiles) attachmentBytes += file.length();
            if (attachmentBytes > 20L * 1024 * 1024) {
                showFormNote(formNote, "Tổng dung lượng tệp đính kèm vượt quá 20 MB. Hãy bỏ bớt tệp.");
                return;
            }

            mailConfig.activate(currentEmail);
            String authAddress = mailConfig.getGmailAddress();
            String fromAddr = senderAddress();
            String displayName = senderDisplayName();
            String pass = mailConfig.getGmailAppPassword();

            if (useGmail) {
                for (String address : toAddresses) {
                    if (!address.matches(MailConfig.EMAIL_PATTERN)) {
                        showFormNote(formNote, "Email người nhận không hợp lệ: " + address
                                + ". Ví dụ: " + defaultAddress + ".");
                        toF.requestFocus();
                        return;
                    }
                }
                for (String address : ccAddresses) {
                    if (!address.matches(MailConfig.EMAIL_PATTERN)) {
                        showFormNote(formNote, "Email Cc không hợp lệ: " + address);
                        ccF.requestFocus();
                        return;
                    }
                }
                for (String address : bccAddresses) {
                    if (!address.matches(MailConfig.EMAIL_PATTERN)) {
                        showFormNote(formNote, "Email Bcc không hợp lệ: " + address);
                        bccF.requestFocus();
                        return;
                    }
                }
                if (!authAddress.matches(MailConfig.EMAIL_PATTERN)) {
                    showFormNote(formNote, "Email người gửi phải hợp lệ. Kiểm tra Cấu hình.");
                    return;
                }
                if (pass.isEmpty() || !MailConfig.isValidAppPassword(pass)) {
                    showFormNote(formNote, "Chưa có Google App Password hợp lệ cho " + authAddress
                            + ". Mỗi email cần App Password riêng của chính tài khoản đó.");
                    openSettings();
                    if (refreshScenario[0] != null) refreshScenario[0].run();
                    return;
                }
                gmailAccount = authAddress;
                gmailAppPassSecret = pass;
                mailConfig.setGmailAddress(authAddress);
                mailConfig.setDefaultTo(recipient);
                mailConfig.setSendMode(MailConfig.MODE_GMAIL);
            } else {
                if (toAddresses.size() > 1) {
                    showFormNote(formNote, "Chế độ LAN chỉ gửi cho một người nhận. Hãy chuyển sang Gmail để gửi nhiều email.");
                    toF.requestFocus();
                    return;
                }
                boolean username = recipient.matches("[A-Za-z0-9._-]{1,32}");
                boolean email = recipient.matches(MailConfig.EMAIL_PATTERN);
                if (!username && !email) {
                    showFormNote(formNote, "Chế độ LAN nhận username hoặc email đã đăng ký, ví dụ chamm4930.");
                    toF.requestFocus();
                    return;
                }
                mailConfig.setSendMode(MailConfig.MODE_LAN);
            }
            mailConfig.save();
            updateGmailStatus();

            final List<String> sendToList = toAddresses;
            final List<String> sendCc = ccAddresses;
            final List<String> sendBcc = bccAddresses;
            final String sendAuth = authAddress;
            final String sendFrom = fromAddr;
            final String sendName = displayName;
            final String sendTo = recipient;
            final String sendPass = pass;
            final boolean gmailPath = useGmail;
            final String previousSendText = sendLbl.getText();
            isSending = true;
            sendBtn.setDisable(true);
            sendLbl.setText(gmailPath ? "  ĐANG GỬI GMAIL…" : "  ĐANG GỬI LAN…");
            RotateTransition sendSpin = new RotateTransition(Duration.millis(900), sendIc);
            sendSpin.setByAngle(360);
            sendSpin.setCycleCount(RotateTransition.INDEFINITE);
            sendSpin.setInterpolator(Interpolator.LINEAR);
            sendSpin.play();

            new Thread(() -> {
                boolean gmailOk = false;
                String sentMessageId = "";
                String archiveNote = "";
                boolean archived = false;
                String dbId = "";
                String error = null;
                try {
                    if (gmailPath) {
                        sentMessageId = GmailSmtpService.send(sendAuth, sendFrom, sendName, sendPass,
                                sendToList, sendCc, sendBcc, subjectText, message, sendFiles);
                        gmailOk = true;
                    }
                    try {
                        String saved = core.send("SEND|" + currentUser + "|" + sendFrom + "|" + sendTo + "|"
                                + subjectText + "|" + message + "|" + (gmailPath ? "GMAIL" : "LAN")
                                + "|" + sentMessageId);
                        if (saved.startsWith("OK")) {
                            dbId = extractDbId(saved);
                            // Chỉ coi là đã lưu DB khi server thực sự trả về emails.id.
                            archived = !dbId.isEmpty();
                            archiveNote = saved.substring(3);
                        } else {
                            archiveNote = saved;
                        }
                    } catch (Exception archiveError) {
                        archiveNote = "Server lỗi: " + archiveError.getMessage();
                    }
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                }

                final boolean deliveredGmail = gmailOk;
                final boolean savedToDb = archived;
                final String archiveInfo = archiveNote;
                final String messageId = sentMessageId;
                final String finalDbId = dbId;
                final String failure = error;

                Platform.runLater(() -> {
                    sendSpin.stop();
                    if (failure != null) {
                        sendBtn.setDisable(false);
                        sendLbl.setText(previousSendText);
                        isSending = false;
                        if (gmailPath && (failure.contains("App Password") || failure.contains("535"))) {
                            gmailVerified = Boolean.FALSE;
                            updateGmailStatus();
                        }
                        showFormNote(formNote, (gmailPath ? "Gửi Google thất bại: " : "Gửi LAN thất bại: ") + failure);
                        log((gmailPath ? "Google" : "LAN") + " lỗi: " + failure);
                        appendProof((gmailPath ? "SMTP" : "LAN") + " lỗi → " + failure);
                        return;
                    }
                    if (!messageId.isEmpty()) sentMessageIds.add(messageId);
                    lastSentId = savedToDb ? finalDbId : "";
                    lastSentTo = sendTo;
                    lastSentSubject = subjectText;
                    lastSentTime = LocalTime.now().withNano(0).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                    isSending = false;
                    statusWait.setText("Đang chờ trả lời từ " + sendTo + " · gửi lúc " + lastSentTime);
                    if (deliveredGmail) {
                        gmailVerified = Boolean.TRUE;
                        updateGmailStatus();
                    }
                    updateDemoState();
                    if (deliveredGmail && savedToDb) {
                        log("Gửi thành công → " + sendTo + " | emails.id=" + finalDbId);
                        appendProof("SMTP OK → " + sendName + " <" + sendFrom + "> → " + sendTo
                                + " | emails.id=" + finalDbId);
                        showToast("Đã gửi thật và lưu MySQL #" + finalDbId, GREEN);
                    } else if (deliveredGmail) {
                        log("Đã gửi Google nhưng lưu MySQL thất bại: " + archiveInfo);
                        appendProof("SMTP OK nhưng MySQL lỗi → " + archiveInfo);
                        showToast("Đã gửi thật, nhưng lưu MySQL thất bại", AMBER);
                    } else {
                        log("Gửi LAN → " + sendTo + " | " + archiveInfo);
                        appendProof("LAN OK → " + sendFrom + " → " + sendTo
                                + (savedToDb ? " | emails.id=" + finalDbId : ""));
                        showToast(savedToDb ? "Đã gửi LAN và lưu MySQL #" + finalDbId : "Đã gửi LAN nhưng chưa lưu MySQL",
                                savedToDb ? GREEN : AMBER);
                    }
                    showSendSuccess(sendName, sendFrom, sendTo, subjectText,
                            finalDbId, deliveredGmail, archiveInfo, savedToDb);
                    refreshMailboxAsync(false);
                    loadSummaryAsync();
                    PauseTransition p1 = new PauseTransition(Duration.seconds(6));
                    p1.setOnFinished(ev -> syncGmailReplies(false));
                    p1.play();
                    PauseTransition p2 = new PauseTransition(Duration.seconds(20));
                    p2.setOnFinished(ev -> syncGmailReplies(false));
                    p2.play();
                });
            }, "mail-send").start();
        });

        HBox bottomBar = new HBox(sendBtn);
        bottomBar.setAlignment(Pos.CENTER_RIGHT);

        Label saveNote = new Label("Mỗi thư = 1 bản ghi MySQL · có file backup trong server_mail/");
        saveNote.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9.5px;");
        HBox footer = new HBox(10, saveNote, new Region(), bottomBar);
        HBox.setHgrow(footer.getChildren().get(1), Priority.ALWAYS);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox bodyBox = new VBox(9, modeRow, modeHint, persona, toRow, ccToggleRow, ccRow, bccRow,
                subRow, formatBar, bodyA, attachBar, composeAttachments, gmailBox, formNote, footer);
        bodyBox.setPadding(new Insets(16, 20, 18, 20));

        VBox fullCard = new VBox(0, header, bodyBox);
        fullCard.setMaxWidth(760);
        fullCard.setMaxHeight(820);
        fullCard.setStyle("-fx-background-color:" + SURFACE + "; -fx-background-radius:20;"
                + " -fx-effect: dropshadow(gaussian, rgba(2,6,18,0.55), 48, 0.22, 0, 16);");

        modalLayer.getChildren().setAll(fullCard);
        modalLayer.setVisible(true);
        modalLayer.setMouseTransparent(false);
        Platform.runLater(toF::requestFocus);
    }

    private void chooseComposeFiles(List<File> files, VBox container, boolean imagesOnly) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(imagesOnly ? "Chọn ảnh để gửi" : "Chọn tệp đính kèm");
        if (imagesOnly) {
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "Ảnh", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.bmp"));
        } else {
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Tất cả tệp", "*.*"));
        }
        List<File> chosen = chooser.showOpenMultipleDialog(primaryStage);
        if (chosen == null) return;

        long total = 0;
        for (File file : files) total += file.length();
        for (File file : chosen) {
            if (file == null || !file.isFile() || files.contains(file)) continue;
            if (total + file.length() > 20L * 1024 * 1024) {
                showAlert("Tổng dung lượng tệp đính kèm tối đa 20 MB.");
                break;
            }
            files.add(file);
            total += file.length();
        }
        renderComposeAttachmentChips(files, container);
    }

    private void renderComposeAttachmentChips(List<File> files, VBox container) {
        container.getChildren().clear();
        if (files == null || files.isEmpty()) {
            container.setVisible(false);
            container.setManaged(false);
            return;
        }

        long total = 0;
        for (File file : files) total += file.length();
        Label title = new Label("TỆP ĐÍNH KÈM (" + files.size() + ") · " + formatBytes(total));
        title.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:9px; -fx-font-weight:bold;");

        FlowPane grid = new FlowPane(7, 7);
        for (File file : new ArrayList<>(files)) {
            boolean image = isImageFile(file);
            FontIcon icon = new FontIcon(image ? FontAwesomeSolid.IMAGE : FontAwesomeSolid.FILE_ALT);
            icon.setIconSize(13);
            icon.setIconColor(Color.web(image ? TEAL : ACCENT));

            Label name = new Label(shorten(file.getName(), 32));
            name.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:10.5px; -fx-font-weight:bold;");
            Label size = new Label(formatBytes(file.length()));
            size.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9px;");
            VBox info = new VBox(1, name, size);

            Button remove = iconButton(FontAwesomeSolid.TIMES, "Bỏ tệp này", RED);
            remove.setOnAction(e -> {
                files.remove(file);
                renderComposeAttachmentChips(files, container);
            });

            HBox chip = new HBox(7, icon, info, remove);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.setPadding(new Insets(6, 7, 6, 9));
            chip.setStyle("-fx-background-color:" + BG + "; -fx-background-radius:9;"
                    + " -fx-border-color:" + LINE + "; -fx-border-radius:9;");
            grid.getChildren().add(chip);
        }
        container.getChildren().addAll(title, grid);
        container.setVisible(true);
        container.setManaged(true);
    }

    private boolean isImageFile(File file) {
        String name = file == null ? "" : file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp");
    }

    private void showSendSuccess(String displayName, String from, String to, String subject,
                                 String dbId, boolean gmail, String archiveInfo, boolean savedToDb) {
        String tone = savedToDb ? GREEN : AMBER;
        String toneDark = savedToDb ? "#047857" : "#B45309";
        String toneLight = savedToDb ? "#10B981" : "#F59E0B";
        String toneBg = savedToDb ? GREEN_BG : AMBER_BG;
        String channel = gmail ? "Google SMTP · thư thật" : "LAN nội bộ";

        FontIcon ic = new FontIcon(savedToDb
                ? FontAwesomeSolid.CHECK_CIRCLE : FontAwesomeSolid.EXCLAMATION_TRIANGLE);
        ic.setIconSize(18);
        ic.setIconColor(Color.web(toneDark));
        StackPane iconBox = new StackPane(ic);
        iconBox.setMinSize(40, 40);
        iconBox.setMaxSize(40, 40);
        iconBox.setStyle("-fx-background-color:rgba(255,255,255,0.96); -fx-background-radius:50;");

        Label kicker = new Label(savedToDb ? "GỬI THƯ HOÀN TẤT" : "CẦN KIỂM TRA");
        kicker.setStyle("-fx-text-fill:rgba(255,255,255,0.78); -fx-font-size:8px; -fx-font-weight:bold;");
        Label title = new Label(savedToDb ? "Đã gửi thành công" : "Đã gửi, chưa lưu MySQL");
        title.setStyle("-fx-text-fill:white; -fx-font-size:18px; -fx-font-weight:bold;");
        Label sub = new Label(savedToDb
                ? (gmail ? "Đã gửi qua Google SMTP và lưu MySQL."
                         : "Đã gửi trong LAN và lưu MySQL.")
                : "Thư đã rời máy nhưng server chưa ghi được bản ghi.");
        sub.setWrapText(true);
        sub.setStyle("-fx-text-fill:rgba(255,255,255,0.84); -fx-font-size:10px;");
        VBox titleBox = new VBox(2, kicker, title, sub);

        Label status = new Label(savedToDb ? "ĐÃ LƯU MYSQL" : "CHƯA LƯU MYSQL");
        status.setStyle("-fx-text-fill:white; -fx-background-color:rgba(255,255,255,0.18);"
                + " -fx-font-size:8px; -fx-font-weight:bold; -fx-padding:4 9; -fx-background-radius:10;");

        FontIcon xIcon = new FontIcon(FontAwesomeSolid.TIMES);
        xIcon.setIconSize(13);
        xIcon.setIconColor(Color.WHITE);
        Button headerClose = new Button();
        headerClose.setGraphic(xIcon);
        headerClose.setFocusTraversable(false);
        String closeNormal = "-fx-background-color:rgba(255,255,255,0.14); -fx-padding:6;"
                + " -fx-background-radius:8; -fx-cursor:hand;";
        String closeHover = "-fx-background-color:rgba(255,255,255,0.28); -fx-padding:6;"
                + " -fx-background-radius:8; -fx-cursor:hand;";
        headerClose.setStyle(closeNormal);
        headerClose.setOnMouseEntered(e -> headerClose.setStyle(closeHover));
        headerClose.setOnMouseExited(e -> headerClose.setStyle(closeNormal));
        headerClose.setOnAction(e -> { closeModal(); refreshMailboxAsync(false); });

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(12, iconBox, titleBox, headerSpacer, status, headerClose);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 20, 14, 20));
        header.setStyle("-fx-background-color: linear-gradient(to bottom right, " + toneDark + ", "
                + toneLight + "); -fx-background-radius:16 16 0 0;");

        FontIcon atIcon = new FontIcon(FontAwesomeSolid.AT);
        atIcon.setIconSize(13);
        atIcon.setIconColor(Color.web(tone));
        StackPane atBox = new StackPane(atIcon);
        atBox.setMinSize(30, 30);
        atBox.setMaxSize(30, 30);
        atBox.setStyle("-fx-background-color:" + toneBg + "; -fx-background-radius:9;");
        Label toCaption = new Label("NGƯỜI NHẬN");
        toCaption.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:8px; -fx-font-weight:bold;");
        Label toValue = new Label(to == null || to.isBlank() ? "—" : to);
        toValue.setWrapText(true);
        toValue.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:11.5px; -fx-font-weight:bold;");
        VBox toText = new VBox(1, toCaption, toValue);
        HBox recipientBand = new HBox(9, atBox, toText);
        recipientBand.setAlignment(Pos.CENTER_LEFT);
        recipientBand.setPadding(new Insets(8, 12, 8, 12));
        recipientBand.setStyle("-fx-background-color:" + BG + "; -fx-background-radius:10;");

        VBox info = new VBox(0,
                successRow(FontAwesomeSolid.USER_CIRCLE, "Người gửi",
                        displayName + "  <" + from + ">"),
                rowDivider(),
                successRow(FontAwesomeSolid.TAG, "Tiêu đề", subject),
                rowDivider(),
                successRow(FontAwesomeSolid.PAPER_PLANE, "Kênh gửi", channel),
                rowDivider(),
                successRow(FontAwesomeSolid.DATABASE, "Bản ghi MySQL",
                        savedToDb ? "lab8_mail.emails #" + dbId : "chưa ghi (" + archiveInfo + ")"));
        info.setStyle("-fx-background-color:" + SURFACE + "; -fx-border-color:" + LINE
                + "; -fx-border-radius:12; -fx-background-radius:12;");

        FontIcon infoIcon = new FontIcon(FontAwesomeSolid.INFO_CIRCLE);
        infoIcon.setIconSize(11);
        infoIcon.setIconColor(Color.web("#0284C7"));
        Label next = new Label("Mở Gmail trên điện thoại → Trả lời. Client tự đồng bộ mỗi 10 giây.");
        next.setWrapText(true);
        next.setStyle("-fx-text-fill:#0369A1; -fx-font-size:9.5px;");
        HBox nextBox = new HBox(7, infoIcon, next);
        nextBox.setAlignment(Pos.CENTER_LEFT);
        nextBox.setPadding(new Insets(8, 10, 8, 10));
        nextBox.setStyle("-fx-background-color:#EFF6FF; -fx-border-color:#DBEAFE;"
                + " -fx-border-radius:9; -fx-background-radius:9;");

        Button openInbox = ghostButton(FontAwesomeSolid.INBOX, "Hộp thư");
        openInbox.setOnAction(e -> {
            closeModal();
            currentView = "MAIL"; currentFolder = "INBOX";
            selectNav(navInbox); showMailView(); openFolder("INBOX");
        });
        Button openSent = ghostButton(FontAwesomeSolid.PAPER_PLANE, "Thư đã gửi");
        openSent.setOnAction(e -> {
            closeModal();
            currentView = "MAIL"; currentFolder = "SENT";
            selectNav(navSent); showMailView(); openFolder("SENT");
        });
        Button syncNow = primaryButton("ĐỒNG BỘ NGAY", FontAwesomeSolid.SYNC_ALT);
        syncNow.setOnAction(e -> { closeModal(); syncGmailReplies(true); });

        Label footerNote = new Label(savedToDb
                ? "App Password chỉ lưu trên máy này"
                : "Kiểm tra server/MySQL rồi đồng bộ lại");
        footerNote.setWrapText(true);
        footerNote.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9px;");
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox actions = new HBox(7, openInbox, openSent, syncNow);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox footer = new HBox(10, footerNote, footerSpacer, actions);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(10, recipientBand, info, nextBox, footer);
        body.setPadding(new Insets(14, 18, 16, 18));
        body.setStyle("-fx-background-color:" + SURFACE + "; -fx-background-radius:0 0 16 16;");

        VBox card = new VBox(0, header, body);
        card.setMaxWidth(540);
        card.setStyle("-fx-background-color:" + SURFACE + "; -fx-background-radius:16;"
                + " -fx-effect: dropshadow(gaussian, rgba(2,6,18,0.55), 40, 0.22, 0, 14);");

        ic.setScaleX(0.2);
        ic.setScaleY(0.2);
        ScaleTransition pop = new ScaleTransition(Duration.millis(360), ic);
        pop.setFromX(0.2); pop.setFromY(0.2);
        pop.setToX(1.12); pop.setToY(1.12);
        pop.setCycleCount(2);
        pop.setAutoReverse(true);
        pop.setInterpolator(Interpolator.EASE_OUT);
        pop.play();

        card.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(220), card);
        fade.setFromValue(0); fade.setToValue(1); fade.play();

        modalLayer.getChildren().setAll(card);
        modalLayer.setVisible(true);
        modalLayer.setMouseTransparent(false);
    }

    private Region rowDivider() {
        Region divider = new Region();
        divider.setMinHeight(1);
        divider.setMaxHeight(1);
        divider.setStyle("-fx-background-color:" + LINE + ";");
        return divider;
    }

    private HBox successRow(FontAwesomeSolid icon, String key, String value) {
        FontIcon fi = new FontIcon(icon);
        fi.setIconSize(10);
        fi.setIconColor(Color.web(ACCENT));
        StackPane iconBox = new StackPane(fi);
        iconBox.setMinSize(22, 22);
        iconBox.setMaxSize(22, 22);
        iconBox.setStyle("-fx-background-color:" + SOFT + "; -fx-background-radius:6;");
        Label k = new Label(key);
        k.setMinWidth(98);
        k.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:9.5px; -fx-font-weight:bold;");
        Label v = new Label(value == null ? "—" : value);
        v.setWrapText(true);
        v.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:11px; -fx-font-weight:bold;");
        HBox.setHgrow(v, Priority.ALWAYS);
        HBox row = new HBox(8, iconBox, k, v);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7, 11, 7, 11));
        return row;
    }

    // ══════════════════════════════════════════════════════════════════
    //  SETTINGS & PROFILE
    // ══════════════════════════════════════════════════════════════════
    private void openSettings() {
        if (currentUser == null) { showAlert("Hãy đăng nhập để mở cấu hình."); return; }
        mailConfig.activate(currentEmail);

        FontIcon ic = new FontIcon(FontAwesomeSolid.COG);
        ic.setIconSize(16);
        ic.setIconColor(Color.WHITE);
        StackPane icBox = new StackPane(ic);
        icBox.setMinSize(42, 42);
        icBox.setMaxSize(42, 42);
        icBox.setStyle("-fx-background-color: linear-gradient(to bottom right, " + ACCENT + ", " + TEAL
                + "); -fx-background-radius:13;");

        Label title = new Label("CẤU HÌNH & HỒ SƠ");
        title.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:15px; -fx-font-weight:bold;");
        Label sub = new Label("Tên hiển thị dùng cho thư gửi đi · Google App Password chỉ lưu trên máy này");
        sub.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10.5px;");
        VBox titleBox = new VBox(3, title, sub);
        HBox titleSection = new HBox(12, icBox, titleBox);
        titleSection.setAlignment(Pos.CENTER_LEFT);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button closeBtn = iconButton(FontAwesomeSolid.TIMES, "Đóng", MUTED);
        closeBtn.setOnAction(e -> closeModal());
        HBox header = new HBox(10, titleSection, sp, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(17, 20, 14, 20));
        header.setStyle("-fx-border-color:transparent transparent " + LINE + " transparent; -fx-border-width:0 0 1 0;");

        // ── Hồ sơ ──
        Label pTitle = new Label("Hồ sơ người gửi");
        pTitle.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:13px; -fx-font-weight:bold;");
        Label pSub = new Label("Đây là tên mà người nhận nhìn thấy trên điện thoại/Gmail.");
        pSub.setWrapText(true);
        pSub.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10.5px;");

        TextField profileName = lightField(FontAwesomeSolid.ADDRESS_CARD, "Tên hiển thị");
        profileName.setText(currentName == null ? currentUser : currentName);
        TextField profileEmail = lightField(FontAwesomeSolid.AT, "Email thật");
        profileEmail.setText(currentEmail == null ? "" : currentEmail);

        TextField avatarField = lightField(FontAwesomeSolid.IMAGE, "URL ảnh dự phòng (tùy chọn)");
        avatarField.setText(mailConfig.getAvatarUrl(currentEmail));
        Label avatarPreview = new Label();
        avatarPreview.setMinSize(54, 54);
        avatarPreview.setMaxSize(54, 54);
        settingsAvatarPreview = avatarPreview;
        String previewName = currentName == null || currentName.isBlank() ? currentUser : currentName;
        String previewInitial = previewName == null || previewName.isBlank()
                ? "?" : previewName.substring(0, 1).toUpperCase(Locale.ROOT);
        applyAvatar(avatarPreview, currentEmail, previewName, 54, previewInitial);

        Button chooseAvatar = ghostButton(FontAwesomeSolid.UPLOAD, "Chọn ảnh");
        chooseAvatar.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Chọn ảnh đại diện");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "Ảnh (PNG, JPG, GIF, WEBP)", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"));
            File selected = chooser.showOpenDialog(primaryStage);
            if (selected == null) return;
            try {
                File dir = new File(System.getProperty("user.dir"), "avatars");
                dir.mkdirs();
                String name = selected.getName() == null ? "avatar.png" : selected.getName();
                int dot = name.lastIndexOf('.');
                String ext = dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : ".png";
                String safeEmail = (currentEmail == null ? "account" : currentEmail)
                        .replaceAll("[^A-Za-z0-9._-]", "_");
                File target = new File(dir, safeEmail + "-" + System.currentTimeMillis() + ext);
                Files.copy(selected.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                String uri = target.toURI().toString();
                mailConfig.setAvatarUrl(currentEmail, uri);
                mailConfig.save();
                avatarField.setText(uri);
                applyAvatar(avatarPreview, currentEmail, previewName, 54, previewInitial);
                updateProfileLabels();
                showToast("Đã đặt ảnh đại diện cho tài khoản", GREEN);
            } catch (Exception ex) {
                showAlert("Không đọc được ảnh:\n" + ex.getMessage());
            }
        });

        Button clearAvatar = ghostButton(FontAwesomeSolid.TRASH_ALT, "Xóa ảnh");
        clearAvatar.setOnAction(e -> {
            mailConfig.setAvatarUrl(currentEmail, "");
            mailConfig.save();
            avatarField.clear();
            applyAvatar(avatarPreview, currentEmail, previewName, 54, previewInitial);
            updateProfileLabels();
        });

        HBox avatarActions = new HBox(8, chooseAvatar, clearAvatar);
        VBox avatarEditor = new VBox(6, avatarField, avatarActions);
        HBox.setHgrow(avatarEditor, Priority.ALWAYS);
        HBox avatarRow = new HBox(11, avatarPreview, avatarEditor);
        avatarRow.setAlignment(Pos.CENTER_LEFT);

        Label avatarNote = new Label("Ảnh dự phòng: chọn từ máy hoặc dán URL, chỉ dùng khi CHƯA kết nối Google. "
                + "Khi đã kết nối, ảnh thật của Gmail luôn được ưu tiên và tự đồng bộ.");
        avatarNote.setWrapText(true);
        avatarNote.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:9.5px;");

        boolean googleAvatarConnected = mailConfig.hasGoogleOAuth(currentEmail);
        Button googleAvatarButton = ghostButton(FontAwesomeSolid.USER_CIRCLE,
                googleAvatarConnected ? "Đăng nhập lại Google" : "Đồng bộ ảnh Gmail thật");
        googleAvatarButton.setOnAction(e -> openGoogleAvatarDialog(() -> {
            updateProfileLabels();
            openSettings();
        }));
        Button googleAvatarSync = ghostButton(FontAwesomeSolid.SYNC_ALT, "Đồng bộ lại");
        googleAvatarSync.setOnAction(e -> refreshGoogleAvatarAsync(true));
        googleAvatarSync.setVisible(googleAvatarConnected);
        googleAvatarSync.setManaged(googleAvatarConnected);
        HBox googleAvatarActions = new HBox(8, googleAvatarButton, googleAvatarSync);
        Label googleAvatarStatus = new Label(googleAvatarConnected
                ? "Ảnh Google: đang bật · tự đồng bộ khi mở app, khi quay lại app và mỗi 15 phút"
                : "Ảnh Google: chưa kết nối · đăng nhập Google một lần để lấy ảnh thật");
        googleAvatarStatus.setWrapText(true);
        googleAvatarStatus.setStyle("-fx-text-fill:" + (googleAvatarConnected ? GREEN : AMBER)
                + "; -fx-font-size:9.5px; -fx-background-color:" + (googleAvatarConnected ? GREEN_BG : AMBER_BG)
                + "; -fx-padding:7 9; -fx-background-radius:8;");
        VBox googleAvatarBox = new VBox(5, googleAvatarActions, googleAvatarStatus);

        Label profileStatus = new Label("Tài khoản: @" + currentUser);
        profileStatus.setWrapText(true);
        profileStatus.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10px; -fx-background-color:" + BG
                + "; -fx-padding:8 10; -fx-background-radius:8;");

        // Tham chiếu sang thẻ Google để cập nhật ngay khi email hồ sơ thay đổi.
        final TextField[] gmailAddressRef = new TextField[1];
        final PasswordField[] appPassRef = new PasswordField[1];
        final Label[] gmailLockedRef = new Label[1];
        final Label[] googleStatusRef = new Label[1];

        Button saveProfile = primaryButton("LƯU HỒ SƠ", FontAwesomeSolid.SAVE);
        saveProfile.setMaxWidth(Double.MAX_VALUE);
        saveProfile.setOnAction(e -> {
            String name = profileName.getText().trim();
            String email = profileEmail.getText().trim();
            if (name.isEmpty()) { setInlineStatus(profileStatus, "Tên hiển thị không được để trống.", RED, RED_BG); return; }
            if (!email.matches(MailConfig.EMAIL_PATTERN)) {
                setInlineStatus(profileStatus, "Email thật không hợp lệ (ví dụ ban@gmail.com).", RED, RED_BG);
                return;
            }
            saveProfile.setDisable(true);
            setInlineStatus(profileStatus, "Đang lưu hồ sơ…", ACCENT, SOFT);
            Thread worker = new Thread(() -> {
                String resp;
                try { resp = core.send("PROFILE|" + currentUser + "|" + currentPass + "|" + name + "|" + email); }
                catch (Exception ex) { resp = "ERROR|" + ex.getMessage(); }
                final String result = resp;
                Platform.runLater(() -> {
                    saveProfile.setDisable(false);
                    if (result.startsWith("OK")) {
                        boolean emailChanged = currentEmail == null || !currentEmail.equalsIgnoreCase(email);
                        currentName = name;
                        currentEmail = email;
                        mailConfig.activate(currentEmail);
                        mailConfig.setAvatarUrl(currentEmail, avatarField.getText().trim());
                        gmailAccount = currentEmail;
                        gmailAppPassSecret = mailConfig.getGmailAppPassword();
                        gmailVerified = null;
                        mailConfig.save();
                        if (emailChanged) {
                            if (gmailAddressRef[0] != null) gmailAddressRef[0].setText(email);
                            if (appPassRef[0] != null) appPassRef[0].setText(mailConfig.getGmailAppPassword());
                            if (gmailLockedRef[0] != null) {
                                gmailLockedRef[0].setText("  Email gửi: " + email + " · App Password riêng của tài khoản này");
                                gmailLockedRef[0].setGraphic(icon(FontAwesomeSolid.LOCK, GREEN, 10));
                                gmailLockedRef[0].setStyle("-fx-text-fill:" + GREEN + "; -fx-font-size:9.5px; -fx-font-weight:bold; "
                                        + "-fx-background-color:" + GREEN_BG + "; -fx-padding:7 10; -fx-background-radius:8;");
                            }
                            if (googleStatusRef[0] != null) {
                                boolean hasPass = MailConfig.isValidAppPassword(mailConfig.getGmailAppPassword());
                                setInlineStatus(googleStatusRef[0], hasPass
                                                ? "Đã có App Password hợp lệ cho " + email
                                                : "Chưa có App Password hợp lệ cho " + email
                                                + ". Mỗi email cần App Password riêng của chính tài khoản đó.",
                                        hasPass ? GREEN : AMBER, hasPass ? GREEN_BG : AMBER_BG);
                            }
                        }
                        updateProfileLabels();
                        String savedInitial = name == null || name.isBlank()
                                ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
                        applyAvatar(avatarPreview, currentEmail, name, 54, savedInitial);
                        updateGmailStatus();
                        setInlineStatus(profileStatus, emailChanged
                                ? "Đã lưu · hãy nhập App Password riêng cho " + email + " ở ô Google bên cạnh."
                                : "Đã lưu · tên hiển thị: " + name,
                                emailChanged ? AMBER : GREEN, emailChanged ? AMBER_BG : GREEN_BG);
                        showToast("Đã cập nhật hồ sơ người gửi", GREEN);
                        log("Hồ sơ: " + name + " <" + email + ">");
                        appendProof("PROFILE OK → " + name + " <" + email + ">");
                        updateDemoState();
                    } else {
                        setInlineStatus(profileStatus, "Lỗi: " + cleanError(result), RED, RED_BG);
                    }
                });
            }, "profile-save");
            worker.setDaemon(true);
            worker.start();
        });

        VBox profileCard = new VBox(9,
                pTitle, pSub,
                labeledInput("Tên hiển thị", profileName),
                labeledInput("Email thật", profileEmail),
                googleAvatarBox,
                avatarRow, avatarNote,
                profileStatus, saveProfile);
        profileCard.setPadding(new Insets(16));
        profileCard.setStyle(cardStyle(14));
        profileCard.setPrefWidth(390);

        // ── Google ──
        Label gTitle = new Label("Google SMTP / IMAP");
        gTitle.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:13px; -fx-font-weight:bold;");
        Label gSub = new Label("Mỗi tài khoản Gmail/Google Workspace dùng email đăng nhập và App Password riêng của chính nó.");
        gSub.setWrapText(true);
        gSub.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10.5px;");

        final String accountEmail = currentEmail == null ? "" : currentEmail.trim();
        TextField gmailAddress = lightField(FontAwesomeSolid.AT,
                accountEmail.isBlank() ? "Chưa có email — cập nhật ở Hồ sơ" : accountEmail);
        gmailAddress.setText(accountEmail);
        gmailAddress.setEditable(false);
        gmailAddress.setStyle("-fx-background-color:" + BG + "; -fx-text-fill:" + SLATE
                + "; -fx-font-size:12px; -fx-padding:10 12; -fx-background-radius:10;"
                + " -fx-border-color:" + LINE + "; -fx-border-radius:10;");
        gmailAddress.setTooltip(new Tooltip("Email lấy từ tài khoản đang đăng nhập. Đổi email ở Hồ sơ người gửi."));
        gmailAddressRef[0] = gmailAddress;

        Label gmailLocked = new Label("  " + (accountEmail.isBlank() ? "Chưa có email tài khoản" :
                "Email gửi: " + accountEmail + " · App Password riêng của tài khoản này"));
        gmailLocked.setGraphic(icon(FontAwesomeSolid.LOCK, accountEmail.isBlank() ? AMBER : GREEN, 10));
        gmailLocked.setStyle("-fx-text-fill:" + (accountEmail.isBlank() ? AMBER : GREEN)
                + "; -fx-font-size:9.5px; -fx-font-weight:bold; "
                + "-fx-background-color:" + (accountEmail.isBlank() ? AMBER_BG : GREEN_BG)
                + "; -fx-padding:7 10; -fx-background-radius:8;");
        gmailLockedRef[0] = gmailLocked;

        PasswordField appPass = new PasswordField();
        appPass.setText(mailConfig.getGmailAppPassword());
        VBox appPassWrapper = passwordFieldWrapper(appPass, "16 ký tự Google App Password");
        appPassRef[0] = appPass;

        Button openLink = ghostButton(FontAwesomeSolid.EXTERNAL_LINK_ALT, "Tạo App Password");
        openLink.setOnAction(e -> {
            try { getHostServices().showDocument("https://myaccount.google.com/apppasswords"); }
            catch (Exception ex) { showAlert("Mở thủ công: myaccount.google.com/apppasswords"); }
        });

        ComboBox<String> modeBox = new ComboBox<>();
        modeBox.getItems().addAll("GOOGLE — Gửi thư thật qua Google SMTP",
                "LAN — Chỉ Client ↔ Server (cùng máy / cùng WiFi)");
        modeBox.getSelectionModel().select(mailConfig.isGmailMode() ? 0 : 1);
        modeBox.setMaxWidth(Double.MAX_VALUE);
        modeBox.setStyle("-fx-background-color:" + BG + "; -fx-background-radius:10; -fx-border-color:" + LINE
                + "; -fx-border-radius:10; -fx-font-size:11px;");

        TextField defaultTo = lightField(FontAwesomeSolid.USER_CIRCLE, "Người nhận gợi ý — ban@example.com");
        defaultTo.setText(mailConfig.getDefaultTo());

        Label googleStatus = new Label();
        googleStatus.setWrapText(true);
        googleStatus.setMaxWidth(380);
        googleStatus.setStyle("-fx-font-size:10px; -fx-text-fill:" + MUTED + "; -fx-background-color:" + BG
                + "; -fx-padding:8 10; -fx-background-radius:8;");
        googleStatusRef[0] = googleStatus;
        if (mailConfig.hasValidGmailCredentials())
            setInlineStatus(googleStatus, "Đã có App Password hợp lệ cho " + accountEmail, GREEN, GREEN_BG);
        else
            setInlineStatus(googleStatus, accountEmail.isBlank()
                            ? "Tài khoản chưa có email thật. Cập nhật Email ở Hồ sơ trước."
                            : "Chưa có App Password hợp lệ cho " + accountEmail
                            + ". Mỗi email cần App Password riêng của chính tài khoản đó.",
                    AMBER, AMBER_BG);

        Button testGoogle = ghostButton(FontAwesomeSolid.PLUG, "Kiểm tra Google");
        Button saveGoogle = primaryButton("LƯU GOOGLE", FontAwesomeSolid.SAVE);

        Runnable saveGoogleAction = () -> {
            String address = currentEmail == null ? "" : currentEmail.trim();
            String pass = MailConfig.normalizeAppPassword(appPass.getText());
            boolean gmailMode = modeBox.getSelectionModel().getSelectedIndex() == 0;
            String error = validateGmailSetup(address, pass, gmailMode);
            if (error != null) { setInlineStatus(googleStatus, error, RED, RED_BG); return; }

            mailConfig.activate(address);
            gmailAccount = address;
            if (!pass.isEmpty()) { gmailAppPassSecret = pass; mailConfig.setGmailAppPassword(pass); }
            mailConfig.setSendMode(gmailMode ? MailConfig.MODE_GMAIL : MailConfig.MODE_LAN);
            mailConfig.setDefaultTo(defaultTo.getText().trim());
            mailConfig.save();
            gmailVerified = null;
            updateGmailStatus();
            setInlineStatus(googleStatus, gmailMode
                    ? "Đã lưu · sẵn sàng gửi thư thật bằng " + address
                    : "Đã lưu chế độ LAN nội bộ", GREEN, GREEN_BG);
            showToast(gmailMode ? "Đã lưu App Password cho " + address : "Đã lưu chế độ LAN", GREEN);
            appendProof("CONFIG OK → " + (gmailMode ? "GMAIL " + address : "LAN"));
            if (gmailMode && !pass.isEmpty()) verifyGmailAsync(false);
        };
        saveGoogle.setOnAction(e -> saveGoogleAction.run());

        testGoogle.setOnAction(e -> {
            String address = currentEmail == null ? "" : currentEmail.trim();
            String pass = MailConfig.normalizeAppPassword(appPass.getText());
            String error = validateGmailSetup(address, pass, true);
            if (error != null) { setInlineStatus(googleStatus, error, RED, RED_BG); return; }
            testGoogle.setDisable(true);
            testGoogle.setText("Đang kiểm tra…");
            setInlineStatus(googleStatus, "Đang kết nối smtp.gmail.com:465 …", ACCENT, SOFT);
            Thread worker = new Thread(() -> {
                try {
                    GmailSmtpService.verify(address, pass);
                    Platform.runLater(() -> {
                        testGoogle.setDisable(false);
                        testGoogle.setText("Kiểm tra Google");
                        setInlineStatus(googleStatus, "Google chấp nhận " + address + " và App Password riêng của tài khoản này.", GREEN, GREEN_BG);
                        gmailVerified = Boolean.TRUE;
                        updateGmailStatus();
                        appendProof("GOOGLE VERIFY OK → " + address);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        testGoogle.setDisable(false);
                        testGoogle.setText("Kiểm tra Google");
                        setInlineStatus(googleStatus, ex.getMessage(), RED, RED_BG);
                        gmailVerified = Boolean.FALSE;
                        updateGmailStatus();
                        appendProof("GOOGLE VERIFY FAIL → " + ex.getMessage());
                    });
                }
            }, "gmail-verify");
            worker.setDaemon(true);
            worker.start();
        });

        Label tip = new Label("Google không cho đăng nhập bằng mật khẩu thường. Bật xác minh 2 bước → tạo App Password 16 ký tự.\n"
                + "App Password của tài khoản khác không dùng được cho email này. Mật khẩu chỉ nằm trên máy client, không gửi lên server.\n"
                + "Chỉ Gmail/Google Workspace mới gửi được qua smtp.gmail.com; tên miền khác hãy dùng LAN hoặc SMTP riêng.");
        tip.setWrapText(true);
        tip.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9.5px; -fx-background-color:" + BG
                + "; -fx-padding:9 11; -fx-background-radius:8;");

        HBox googleButtons = new HBox(8, testGoogle, saveGoogle);
        googleButtons.setAlignment(Pos.CENTER_RIGHT);

        VBox googleCard = new VBox(8,
                gTitle, gSub, gmailLocked,
                labeledInput("Tài khoản Google dùng để gửi", gmailAddress),
                labeledInput("Google App Password", appPassWrapper),
                openLink,
                googleStatus,
                labeledInput("Chế độ gửi mặc định", modeBox),
                labeledInput("Người nhận gợi ý", defaultTo),
                tip,
                googleButtons);
        googleCard.setPadding(new Insets(16));
        googleCard.setStyle(cardStyle(14));
        googleCard.setPrefWidth(420);
        HBox.setHgrow(googleCard, Priority.ALWAYS);

        HBox body = new HBox(16, profileCard, googleCard);
        body.setPadding(new Insets(16, 20, 20, 20));
        body.setAlignment(Pos.TOP_LEFT);

        VBox card = new VBox(0, header, body);
        card.setMaxWidth(880);
        card.setMaxHeight(820);
        card.setStyle("-fx-background-color:" + SURFACE + "; -fx-background-radius:20;"
                + " -fx-effect: dropshadow(gaussian, rgba(2,6,18,0.55), 48, 0.22, 0, 16);");

        modalLayer.getChildren().setAll(card);
        modalLayer.setVisible(true);
        modalLayer.setMouseTransparent(false);
    }

    /**
     * Đăng nhập Google bằng OAuth Desktop để lấy đúng ảnh profile.
     * Chỉ xin openid/email/profile — không xin quyền đọc mail.
     */
    private void openGoogleAvatarDialog(Runnable onChanged) {
        if (currentEmail == null || currentEmail.isBlank()) {
            showAlert("Hãy cập nhật Email thật ở Hồ sơ trước khi kết nối Google.");
            return;
        }
        if (googleAvatarDialog != null && googleAvatarDialog.isShowing()) {
            googleAvatarDialog.toFront();
            googleAvatarDialog.requestFocus();
            return;
        }
        final String accountEmail = currentEmail.trim();

        Stage dialog = new Stage();
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Ảnh đại diện Google thật");
        dialog.setResizable(false);

        Label title = new Label("ẢNH ĐẠI DIỆN GOOGLE THẬT");
        title.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:15px; -fx-font-weight:bold;");
        Label sub = new Label("Đăng nhập Google một lần để MailFlow tự lấy đúng ảnh thật của "
                + accountEmail + ", tải về máy và đồng bộ lại tự động khi ảnh Gmail thay đổi.");
        sub.setWrapText(true);
        sub.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10.5px;");

        Button guide = ghostButton(FontAwesomeSolid.EXTERNAL_LINK_ALT, "TẠO OAUTH CLIENT ID");
        guide.setOnAction(e -> {
            try { getHostServices().showDocument("https://console.cloud.google.com/apis/credentials"); }
            catch (Exception ex) { showAlert("Mở thủ công: console.cloud.google.com/apis/credentials"); }
        });

        Label steps = new Label("""
                1. console.cloud.google.com → tạo project (hoặc chọn project có sẵn).
                2. OAuth consent screen → External → thêm email này vào Test users.
                3. Credentials → Create credentials → OAuth client ID → Desktop app.
                4. Dán Client ID + Client Secret bên dưới → bấm ĐĂNG NHẬP GOOGLE.""");
        steps.setWrapText(true);
        steps.setStyle("-fx-text-fill:" + SLATE + "; -fx-font-size:10px; -fx-background-color:" + BG
                + "; -fx-padding:10 12; -fx-background-radius:9;");

        TextField clientId = lightField(FontAwesomeSolid.KEY, "OAuth Client ID");
        clientId.setText(mailConfig.getOAuthClientId());
        PasswordField clientSecret = new PasswordField();
        clientSecret.setText(mailConfig.getOAuthClientSecret());
        VBox clientSecretWrapper = passwordFieldWrapper(clientSecret, "OAuth Client Secret");

        Label status = new Label();
        status.setWrapText(true);
        boolean connected = mailConfig.hasGoogleOAuth(accountEmail);
        setInlineStatus(status, connected
                        ? "Đã kết nối OAuth cho " + accountEmail + ". Ảnh sẽ tự làm mới mỗi lần mở app."
                        : "Chưa kết nối. Cần OAuth Client ID (Desktop app) để lấy ảnh thật.",
                connected ? GREEN : AMBER, connected ? GREEN_BG : AMBER_BG);

        Button save = ghostButton(FontAwesomeSolid.SAVE, "LƯU CLIENT ID");
        Button disconnect = ghostButton(FontAwesomeSolid.TIMES, "NGẮT KẾT NỐI");
        disconnect.setVisible(connected);
        disconnect.setManaged(connected);
        Button login = primaryButton("ĐĂNG NHẬP GOOGLE", FontAwesomeSolid.SIGN_IN_ALT);
        login.setMaxWidth(Double.MAX_VALUE);

        save.setOnAction(e -> {
            mailConfig.setOAuthClientId(clientId.getText());
            mailConfig.setOAuthClientSecret(clientSecret.getText());
            mailConfig.save();
            setInlineStatus(status, "Đã lưu OAuth Client ID. Bấm ĐĂNG NHẬP GOOGLE để lấy ảnh.",
                    GREEN, GREEN_BG);
        });

        disconnect.setOnAction(e -> {
            mailConfig.clearGoogleOAuth(accountEmail);
            mailConfig.save();
            updateProfileLabels();
            showToast("Đã ngắt ảnh đại diện Google", AMBER);
            dialog.close();
            if (onChanged != null) onChanged.run();
        });

        login.setOnAction(e -> {
            final String id = clientId.getText() == null ? "" : clientId.getText().trim();
            final String secret = clientSecret.getText() == null ? "" : clientSecret.getText().trim();
            if (id.isEmpty()) {
                setInlineStatus(status, "Cần OAuth Client ID. Bấm TẠO OAUTH CLIENT ID để tạo trước.",
                        RED, RED_BG);
                return;
            }
            if (secret.isEmpty()) {
                setInlineStatus(status, "Desktop app bắt buộc có Client Secret: dán cả Client ID + Client Secret "
                        + "(Credentials → OAuth client → Desktop app).", RED, RED_BG);
                return;
            }
            mailConfig.setOAuthClientId(id);
            mailConfig.setOAuthClientSecret(secret);
            mailConfig.save();

            login.setDisable(true);
            save.setDisable(true);
            disconnect.setDisable(true);
            setInlineStatus(status, "Đang mở trình duyệt… Chọn đúng tài khoản " + accountEmail
                    + " và bấm Allow.", ACCENT, SOFT);

            Thread worker = new Thread(() -> {
                try {
                    GoogleOAuthService.Result result = GoogleOAuthService.authorize(
                            id, secret, accountEmail,
                            url -> Platform.runLater(() -> {
                                try { getHostServices().showDocument(url); }
                                catch (Exception ex) {
                                    showAlert("Không mở được trình duyệt:\n" + ex.getMessage());
                                }
                            }), 180);
                    if (!sameGoogleEmail(result.email, accountEmail)) {
                        throw new IllegalStateException("Bạn đã đăng nhập " + result.email
                                + ", không khớp tài khoản " + accountEmail + ". Hãy thử lại.");
                    }
                    String localUri = "";
                    try {
                        File cached = GoogleOAuthService.cachePicture(accountEmail, result.pictureUrl);
                        if (cached != null) localUri = cached.toURI().toString();
                    } catch (Exception ignored) {
                        // Không tải về được thì vẫn dùng URL ảnh trực tiếp của Google.
                    }
                    final String cachedAvatarUri = localUri;
                    Platform.runLater(() -> {
                        AVATAR_CACHE.clear();
                        AVATAR_FAILED.clear();
                        AVATAR_WATCHED.clear();
                        mailConfig.connectGooglePhoto(accountEmail, result.pictureUrl,
                                result.refreshToken, cachedAvatarUri);
                        mailConfig.save();
                        lastGoogleAvatarSyncAt = System.currentTimeMillis();
                        updateProfileLabels();
                        setInlineStatus(status, "Đã lấy ảnh thật của " + result.email + ".",
                                GREEN, GREEN_BG);
                        showToast("Đã đồng bộ ảnh đại diện Google thật", GREEN);
                        dialog.close();
                        if (onChanged != null) onChanged.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        login.setDisable(false);
                        save.setDisable(false);
                        disconnect.setDisable(false);
                        setInlineStatus(status, "Lỗi: " + ex.getMessage(), RED, RED_BG);
                    });
                }
            }, "google-oauth");
            worker.setDaemon(true);
            worker.start();
        });

        Label note = new Label("Quyền chỉ gồm openid, email, profile — chỉ đọc tên và ảnh đại diện. "
                + "Không đọc nội dung mail, không thay App Password. Ảnh được tải về avatars/ và tự làm mới "
                + "khi mở app, khi quay lại app và mỗi 15 phút.\n"
                + "Nếu OAuth consent screen còn ở Testing, refresh token hết hạn sau 7 ngày; "
                + "hãy Publish app để dùng lâu dài.");
        note.setWrapText(true);
        note.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9px;");

        HBox secondary = new HBox(8, save, disconnect);
        secondary.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10,
                title, sub, guide, steps,
                labeledInput("OAuth Client ID", clientId),
                labeledInput("OAuth Client Secret", clientSecretWrapper),
                secondary, status, login, note);
        root.setPadding(new Insets(18, 20, 20, 20));
        root.setStyle("-fx-background-color:" + SURFACE + ";");

        Scene scene = new Scene(root, 600, 640);
        dialog.setScene(scene);
        googleAvatarDialog = dialog;
        dialog.setOnHidden(e -> {
            if (googleAvatarDialog == dialog) googleAvatarDialog = null;
        });
        dialog.show();
    }

    private static boolean sameGoogleEmail(String first, String second) {
        if (first == null || second == null) return false;
        String a = first.trim().toLowerCase(Locale.ROOT);
        String b = second.trim().toLowerCase(Locale.ROOT);
        if (a.equals(b)) return true;
        int atA = a.indexOf('@');
        int atB = b.indexOf('@');
        if (atA <= 0 || atB <= 0) return false;
        String domainA = googleMailDomain(a.substring(atA + 1));
        String domainB = googleMailDomain(b.substring(atB + 1));
        if (!domainA.equals(domainB)) return false;
        if (!domainA.equals("gmail.com")) return false;
        return a.substring(0, atA).replace(".", "")
                .equals(b.substring(0, atB).replace(".", ""));
    }

    /** gmail.com và googlemail.com là cùng một hộp thư. */
    private static String googleMailDomain(String domain) {
        return "googlemail.com".equals(domain) ? "gmail.com" : domain;
    }

    private void setInlineStatus(Label label, String text, String color, String background) {
        label.setText(text == null ? "" : text);
        label.setStyle("-fx-font-size:10px; -fx-text-fill:" + color + "; -fx-background-color:" + background
                + "; -fx-padding:8 10; -fx-background-radius:8;");
    }

    private String validateGmailSetup(String address, String pass, boolean requirePassword) {
        if (address == null || !address.matches(MailConfig.EMAIL_PATTERN))
            return "Tài khoản chưa có email hợp lệ. Cập nhật Email thật ở Hồ sơ người gửi trước.";
        if (requirePassword && pass.isEmpty())
            return "Cần Google App Password 16 ký tự của chính " + address + " để gửi thư thật.\n"
                    + "Nếu chỉ gửi LAN nội bộ, chọn chế độ LAN.";
        if (!pass.isEmpty() && !MailConfig.isValidAppPassword(pass))
            return "App Password phải gồm đúng 16 ký tự chữ và số (có thể nhập kèm dấu cách).";
        return null;
    }

    private void verifyGmailAsync(boolean loud) {
        mailConfig.activate(currentEmail);
        String address = mailConfig.getGmailAddress();
        String pass = mailConfig.getGmailAppPassword();
        if (address.isEmpty() || pass.isEmpty()) return;
        Thread worker = new Thread(() -> {
            try {
                GmailSmtpService.verify(address, pass);
                Platform.runLater(() -> {
                    gmailVerified = Boolean.TRUE;
                    updateGmailStatus();
                    log("Google sẵn sàng: " + address);
                    appendProof("GOOGLE READY → " + address);
                    if (loud) showToast("Google kết nối thành công", GREEN);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    gmailVerified = Boolean.FALSE;
                    updateGmailStatus();
                    log("Google: " + ex.getMessage());
                    appendProof("GOOGLE ERROR → " + ex.getMessage());
                    if (loud) showAlert("Không kết nối được Google:\n" + ex.getMessage());
                });
            }
        }, "gmail-verify");
        worker.setDaemon(true);
        worker.start();
    }

    // ══════════════════════════════════════════════════════════════════
    //  DEMO VIEW — chứng minh gửi thật, nhận thật, lưu MySQL
    // ══════════════════════════════════════════════════════════════════
    private void showDemoView() {
        VBox content = new VBox(14);
        content.setPadding(new Insets(16, 18, 16, 18));

        FontIcon ic = new FontIcon(FontAwesomeSolid.MOBILE_ALT);
        ic.setIconSize(18);
        ic.setIconColor(Color.web(ACCENT));
        Label title = new Label("  Test 2 máy: gửi từ client → điện thoại trả lời → quay về client");
        title.setGraphic(ic);
        title.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:18px; -fx-font-weight:bold;");
        Label sub = new Label("Máy A gửi thư thật → điện thoại/máy B thấy đúng tên người gửi → bấm Trả lời → "
                + "client A tự đồng bộ và ghi thêm 1 dòng vào MySQL.");
        sub.setWrapText(true);
        sub.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:11px;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button dbBtn = ghostButton(FontAwesomeSolid.DATABASE, "Tải lại MySQL");
        dbBtn.setOnAction(e -> { loadSummaryAsync(); loadDbInfo(); });
        Button syncBtn = primaryButton("ĐỒNG BỘ GMAIL NGAY", FontAwesomeSolid.SYNC_ALT);
        syncBtn.setOnAction(e -> syncGmailReplies(true));
        HBox head = new HBox(10, new VBox(4, title, sub), sp, dbBtn, syncBtn);
        head.setAlignment(Pos.CENTER_LEFT);

        // ── 4 bước ──
        VBox s1 = demoStepCard("1", FontAwesomeSolid.PAPER_PLANE, "Máy A gửi thư",
                "Chọn Gmail, nhập email người nhận, bấm gửi thật.");
        VBox s2 = demoStepCard("2", FontAwesomeSolid.PHONE, "Điện thoại nhận",
                "Mở Gmail trên điện thoại, kiểm tra tên người gửi.");
        VBox s3 = demoStepCard("3", FontAwesomeSolid.REPLY, "Điện thoại trả lời",
                "Bấm Trả lời và gửi, không đổi người nhận.");
        VBox s4 = demoStepCard("4", FontAwesomeSolid.DATABASE, "Client nhận + MySQL",
                "Thư trả lời vào Hộp thư và thêm 1 dòng emails.");
        step1State = (Label) s1.getUserData();
        step2State = (Label) s2.getUserData();
        step3State = (Label) s3.getUserData();
        step4State = (Label) s4.getUserData();
        HBox steps = new HBox(10, s1, s2, s3, s4);
        for (Node n : steps.getChildren()) HBox.setHgrow(n, Priority.ALWAYS);

        // ── Metrics ──
        demoStatEmails = demoMetricValue();
        demoStatInbox = demoMetricValue();
        demoStatSent = demoMetricValue();
        demoStatAccounts = demoMetricValue();
        HBox metrics = new HBox(10,
                demoMetric(FontAwesomeSolid.DATABASE, "Tổng email trong MySQL", demoStatEmails, ACCENT, SOFT),
                demoMetric(FontAwesomeSolid.INBOX, "Thư trong Hộp thư", demoStatInbox, TEAL, "#ECFDF5"),
                demoMetric(FontAwesomeSolid.PAPER_PLANE, "Thư đã gửi", demoStatSent, GREEN, GREEN_BG),
                demoMetric(FontAwesomeSolid.USERS, "Tài khoản", demoStatAccounts, AMBER, AMBER_BG));
        for (Node n : metrics.getChildren()) HBox.setHgrow(n, Priority.ALWAYS);

        // ── Card A ──
        demoServerValue = demoValue(core.getServerIP() + ":" + core.getPort());
        demoAccountValue = demoValue((currentUser == null ? "—" : "@" + currentUser)
                + (currentEmail == null ? "" : " · " + currentEmail));
        demoGoogleValue = demoValue(currentEmail == null || currentEmail.isBlank()
                ? "chưa có email tài khoản"
                : currentEmail + (mailConfig.hasPasswordFor(currentEmail) ? " · đã có App Password" : " · cần App Password"));
        demoFromValue = demoValue(senderDisplayName() + "  <" + senderAddress() + ">");
        demoLastSent = demoValue("chưa gửi trong phiên này");
        VBox cardA = demoCard(FontAwesomeSolid.DESKTOP, "Máy A · Client gửi thư",
                "Thông tin này chứng minh thư đi ra bằng tài khoản thật và có tên hiển thị.");
        cardA.getChildren().addAll(
                demoRow("Server UDP", demoServerValue),
                demoRow("Tài khoản", demoAccountValue),
                demoRow("IP máy A", demoValue(myIP)),
                demoRow("Google gửi", demoGoogleValue),
                demoRow("Tên hiển thị", demoFromValue),
                demoRow("Lần gửi gần nhất", demoLastSent));

        // ── Card B ──
        demoLastReply = demoValue("chưa nhận trả lời");
        demoSync = demoValue("Đồng bộ: " + lastSyncText);
        VBox cardB = demoCard(FontAwesomeSolid.PHONE, "Điện thoại / máy B · Trả lời",
                "Không cần cài client. Chỉ cần mở Gmail, thấy tên người gửi và bấm Trả lời.");
        Label step1 = demoStep("1", "Mở thư vừa nhận trên điện thoại, kiểm tra tên người gửi hiển thị đúng.");
        Label step2 = demoStep("2", "Bấm Trả lời (Reply) và gửi. Giữ nguyên địa chỉ người nhận.");
        Label step3 = demoStep("3", "Client tự đọc Gmail bằng IMAP mỗi 10 giây; hoặc bấm ĐỒNG BỘ GMAIL NGAY.");
        Label step4 = demoStep("4", "Thư trả lời xuất hiện trong Hộp thư và bảng emails tăng thêm 1 dòng.");
        Button sync2 = ghostButton(FontAwesomeSolid.SYNC_ALT, "Đồng bộ trả lời");
        sync2.setOnAction(e -> syncGmailReplies(true));
        cardB.getChildren().addAll(step1, step2, step3, step4, demoSync, demoLastReply, sync2);

        // ── Card DB ──
        demoDbState = demoValue("…");
        VBox cardDb = demoCard(FontAwesomeSolid.DATABASE, "MySQL · lab8_mail.emails",
                "Mỗi thư gửi/nhận là một dòng. Mở phpMyAdmin để đối chiếu cùng lúc.");
        cardDb.getChildren().addAll(
                demoRow("Kết nối", demoDbState),
                demoRow("Tổng email", demoStatEmails),
                demoRow("Hộp thư", demoStatInbox),
                demoRow("Đã gửi", demoStatSent),
                demoRow("Tài khoản", demoStatAccounts));

        // ── Card kịch bản ──
        VBox cardHow = demoCard(FontAwesomeSolid.LIST_OL, "Kịch bản demo 60 giây",
                "Làm đúng thứ tự để thầy thấy rõ cả 4 bước.");
        cardHow.getChildren().addAll(
                demoStep("1", "Máy A: Cấu hình Google → nhập App Password → Kiểm tra Google."),
                demoStep("2", "Máy A: Soạn thư → Gửi thật qua Gmail → thấy hộp thoại có emails.id."),
                demoStep("3", "Điện thoại: nhận thư, tên người gửi đúng → bấm Trả lời."),
                demoStep("4", "Client A: bấm ĐỒNG BỘ GMAIL NGAY → thư trả lời vào Hộp thư."),
                demoStep("5", "Quay lại tab này: tổng email tăng, dòng mới nhất là thư trả lời."));

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(50);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(50);
        grid.getColumnConstraints().addAll(c1, c2);
        grid.add(cardA, 0, 0);
        grid.add(cardB, 1, 0);
        grid.add(cardDb, 0, 1);
        grid.add(cardHow, 1, 1);

        // ── Bằng chứng kỹ thuật ──
        proofLog = new TextArea();
        proofLog.setEditable(false);
        proofLog.setWrapText(false);
        proofLog.setPrefRowCount(7);
        proofLog.setStyle("-fx-control-inner-background:#0B1220; -fx-background-color:#0B1220; "
                + "-fx-text-fill:#86EFAC; -fx-font-family:'Consolas'; -fx-font-size:10.5px; "
                + "-fx-background-radius:10; -fx-border-color:#1E293B; -fx-border-radius:10;");
        proofLog.setText(proofBuffer.toString());
        proofLog.setScrollTop(Double.MAX_VALUE);
        Button clearProof = ghostButton(FontAwesomeSolid.TRASH_ALT, "Xóa bằng chứng");
        clearProof.setOnAction(e -> { proofBuffer.setLength(0); if (proofLog != null) proofLog.clear(); });
        Label proofTitle = new Label("Bằng chứng kỹ thuật (protocol log)");
        proofTitle.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:12.5px; -fx-font-weight:bold;");
        Region psp = new Region();
        HBox.setHgrow(psp, Priority.ALWAYS);
        HBox proofHead = new HBox(8, proofTitle, psp, clearProof);
        proofHead.setAlignment(Pos.CENTER_LEFT);
        VBox proofCard = new VBox(8, proofHead, proofLog);
        proofCard.setPadding(new Insets(14));
        proofCard.setStyle(cardStyle(14));

        content.getChildren().addAll(head, steps, metrics, grid, proofCard);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color:transparent; -fx-background:" + BG + ";");
        centerContent.getChildren().setAll(scroll);

        updateDemoState();
        updateSyncLabels();
        loadSummaryAsync();
        loadDbInfo();
    }

    private VBox demoStepCard(String number, FontAwesomeSolid icon, String title, String desc) {
        FontIcon fi = new FontIcon(icon);
        fi.setIconSize(17);
        fi.setIconColor(Color.web(ACCENT));
        StackPane iconBox = new StackPane(fi);
        iconBox.setMinSize(40, 40);
        iconBox.setMaxSize(40, 40);
        iconBox.setStyle("-fx-background-color:" + SOFT + "; -fx-background-radius:12;");

        Label num = new Label(number);
        num.setMinSize(22, 22);
        num.setMaxSize(22, 22);
        num.setAlignment(Pos.CENTER);
        num.setStyle("-fx-background-color:" + ACCENT + "; -fx-text-fill:white; -fx-font-size:10px; "
                + "-fx-font-weight:bold; -fx-background-radius:50;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox top = new HBox(9, num, iconBox, sp);
        top.setAlignment(Pos.CENTER_LEFT);

        Label t = new Label(title);
        t.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:12.5px; -fx-font-weight:bold;");
        Label d = new Label(desc);
        d.setWrapText(true);
        d.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10px;");
        Label state = new Label("chưa bắt đầu");
        state.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:9px; -fx-font-weight:bold; "
                + "-fx-background-color:" + BG + "; -fx-padding:3 8; -fx-background-radius:8;");

        VBox card = new VBox(6, top, t, d, state);
        card.setPadding(new Insets(12));
        card.setStyle(cardStyle(14));
        card.setUserData(state);
        return card;
    }

    private Label demoMetricValue() {
        Label l = new Label("0");
        l.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:22px; -fx-font-weight:bold;");
        return l;
    }

    private VBox demoMetric(FontAwesomeSolid icon, String title, Label value, String color, String bg) {
        FontIcon fi = new FontIcon(icon);
        fi.setIconSize(15);
        fi.setIconColor(Color.web(color));
        StackPane iconBox = new StackPane(fi);
        iconBox.setMinSize(36, 36);
        iconBox.setMaxSize(36, 36);
        iconBox.setStyle("-fx-background-color:" + bg + "; -fx-background-radius:11;");
        Label t = new Label(title);
        t.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:9.5px; -fx-font-weight:bold;");
        VBox text = new VBox(0, value, t);
        HBox row = new HBox(10, iconBox, text);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(row);
        card.setPadding(new Insets(12, 14, 12, 14));
        card.setStyle(cardStyle(14));
        return card;
    }

    private VBox demoCard(FontAwesomeSolid icon, String title, String subtitle) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(14, 16, 14, 16));
        card.setStyle(cardStyle(14));
        FontIcon ic = new FontIcon(icon);
        ic.setIconSize(14);
        ic.setIconColor(Color.web(ACCENT));
        Label t = new Label("  " + title);
        t.setGraphic(ic);
        t.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:13px; -fx-font-weight:bold;");
        Label s = new Label(subtitle);
        s.setWrapText(true);
        s.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10px;");
        card.getChildren().addAll(t, s, new Separator());
        return card;
    }

    private HBox demoRow(String key, Label value) {
        Label k = new Label(key);
        k.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:10px; -fx-font-weight:bold;");
        k.setMinWidth(120);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox row = new HBox(8, k, sp, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Label demoValue(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill:" + INK + "; -fx-font-size:10.5px; -fx-background-color:" + BG
                + "; -fx-padding:5 9; -fx-background-radius:8;");
        return l;
    }

    private Label demoStep(String number, String text) {
        Label l = new Label(number + ".  " + text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill:" + SLATE + "; -fx-font-size:10.5px;");
        return l;
    }

    // ══════════════════════════════════════════════════════════════════
    //  GMAIL REPLY SYNC (điện thoại trả lời → MySQL → Hộp thư)
    // ══════════════════════════════════════════════════════════════════
    private boolean dbConnected = false;

    private void startGmailReplySync() {
        PauseTransition delay = new PauseTransition(Duration.seconds(2));
        delay.setOnFinished(e -> syncGmailReplies(false));
        delay.play();
        updateSyncLabels();
    }

    private void updateSyncLabels() {
        if (demoSync != null) demoSync.setText("Đồng bộ: " + lastSyncText);
    }

    /**
     * Đọc Gmail bằng IMAP:
     *  - INBOX: thư trả lời từ người nhận (kể cả từ điện thoại).
     *  - Sent Mail: thư do chính tài khoản này gửi từ điện thoại/Gmail.
     * Thư gửi từ điện thoại dùng cùng tài khoản không nằm trong INBOX, vì vậy phải đọc Sent Mail.
     */
    private void syncGmailReplies(boolean loud) {
        if (currentUser == null || gmailSyncing) return;
        mailConfig.activate(currentEmail);
        String account = mailConfig.getGmailAddress();
        String pass = mailConfig.getGmailAppPassword();
        if (account.isEmpty() || pass.isEmpty() || !MailConfig.isValidAppPassword(pass)) {
            if (loud) showAlert("Chưa có Google App Password hợp lệ cho "
                    + (account.isBlank() ? "tài khoản này" : account) + ".\n"
                    + "Mở Cấu hình → nhập App Password riêng của chính email đó → Kiểm tra Google.");
            return;
        }
        gmailSyncing = true;
        if (loud) lastSyncText = "đang đồng bộ…";
        updateSyncLabels();

        final String user = currentUser;
        Thread worker = new Thread(() -> {
            int replyCount = 0;
            int sentCount = 0;
            String error = null;
            String replyId = "", replyName = "", replyFrom = "", replySubject = "", replyTime = "";
            String sentId = "", sentTo = "", sentSubject = "", sentTime = "";
            try {
                long cutoff = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000;

                GmailImapService.SyncSnapshot snapshot = GmailImapService.fetchRecentBoth(account, pass, 10);

                // 1) Thư trả lời/thư mới gửi tới hộp thư của tài khoản này.
                List<GmailImapService.MessageInfo> messages = snapshot.inbox;
                for (GmailImapService.MessageInfo mail : messages) {
                    if (mail.messageId != null && !mail.messageId.isEmpty() && sentMessageIds.contains(mail.messageId)) continue;
                    if (mail.sentDate != null && mail.sentDate.getTime() < cutoff) continue;
                    if (!isOurMailbox(mail)) continue;
                    if (mail.fromAddress.equalsIgnoreCase(account)) continue;

                    // Nhận MỌI thư mới gửi tới hộp thư này, không chỉ thư có In-Reply-To.
                    // Điện thoại có thể soạn một thư mới hoàn toàn hoặc trả lời bằng app khác;
                    // cả hai trường hợp đều phải tự hiện trong client.
                    String target = null;
                    for (String address : mail.toAddresses) {
                        target = resolveAccount(address);
                        if (target != null) break;
                    }
                    if (target == null) target = user;
                    if (target == null) continue;

                    String resp = core.send("RECEIVE|" + target + "|" + mail.fromAddress + "|"
                            + (mail.to.isBlank() ? account : mail.to) + "|"
                            + cleanField(mail.subject, 400) + "|" + cleanField(mail.body, 30000) + "|"
                            + (mail.messageId == null ? "" : mail.messageId) + "|"
                            + cleanField(mail.fromName, 64));
                    if (resp.startsWith("OK|Received") && !resp.contains("file backup")) {
                        replyCount++;
                        replyId = extractDbId(resp);
                        replyName = mail.fromName;
                        replyFrom = mail.fromAddress;
                        replySubject = mail.subject;
                        replyTime = LocalTime.now().withNano(0).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                    }
                }

                // 2) Thư do chính tài khoản này gửi từ điện thoại/Gmail.
                //    SEND_SYNC liên kết bản ghi INBOX sẵn có thành SENT để không tạo trùng.
                List<GmailImapService.MessageInfo> sentMessages = snapshot.sent;
                for (GmailImapService.MessageInfo mail : sentMessages) {
                    if (mail.messageId == null || mail.messageId.isBlank()) continue;
                    if (mail.sentDate != null && mail.sentDate.getTime() < cutoff) continue;
                    if (sentMessageIds.contains(mail.messageId)) continue;

                    String to = firstRecipient(mail);
                    if (to.isBlank()) continue;
                    String from = mail.fromAddress == null || mail.fromAddress.isBlank()
                            ? account : mail.fromAddress;
                    String resp = core.send("SEND_SYNC|" + user + "|" + from + "|" + to + "|"
                            + cleanField(mail.subject, 400) + "|" + cleanField(mail.body, 30000) + "|"
                            + mail.messageId);
                    if (resp.startsWith("OK|") && !resp.contains("Already synchronized")) {
                        sentCount++;
                        sentId = extractDbId(resp);
                        sentTo = to;
                        sentSubject = mail.subject;
                        sentTime = LocalTime.now().withNano(0).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                    }
                }
            } catch (Exception ex) {
                error = ex.getMessage();
            }

            final int replies = replyCount, sent = sentCount;
            final String err = error;
            final String rId = replyId, rName = replyName, rFrom = replyFrom, rSubj = replySubject, rTime = replyTime;
            final String sId = sentId, sTo = sentTo, sSubj = sentSubject, sTime = sentTime;
            Platform.runLater(() -> {
                lastSyncText = err == null
                        ? "lúc " + LocalTime.now().withNano(0).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        : "lỗi: " + err;
                updateSyncLabels();

                if (replies > 0) {
                    lastReplyId = rId;
                    lastReplyName = rName;
                    lastReplyFrom = rFrom;
                    lastReplySubject = rSubj;
                    lastReplyTime = rTime;
                    statusWait.setText("Đã nhận trả lời từ " + (rName.isBlank() ? rFrom : rName + " <" + rFrom + ">"));
                    log("Nhận " + replies + " thư trả lời từ Gmail/điện thoại"
                            + (rId.isBlank() ? "" : " | emails.id=" + rId));
                    appendProof("IMAP → RECEIVE OK → " + rName + " <" + rFrom + ">"
                            + (rId.isBlank() ? "" : " | emails.id=" + rId));
                    showToast("Đã nhận " + replies + " thư trả lời từ điện thoại"
                            + (rId.isBlank() ? "" : " · MySQL #" + rId), GREEN);
                    if ("MAIL".equals(currentView) && "INBOX".equals(currentFolder)) refreshMailboxAsync(false);
                    if ("DEMO".equals(currentView)) { loadSummaryAsync(); updateDemoState(); }
                }

                if (sent > 0) {
                    lastSentId = sId;
                    lastSentTo = sTo;
                    lastSentSubject = sSubj;
                    lastSentTime = sTime;
                    statusWait.setText("Đã đồng bộ thư gửi từ điện thoại → " + sTo);
                    log("Đồng bộ " + sent + " thư đã gửi từ Gmail/điện thoại"
                            + (sId.isBlank() ? "" : " | emails.id=" + sId));
                    appendProof("IMAP SENT → SEND_SYNC OK → " + sTo
                            + (sId.isBlank() ? "" : " | emails.id=" + sId));
                    showToast("Đã đồng bộ " + sent + " thư gửi từ điện thoại → Thư đã gửi"
                            + (sId.isBlank() ? "" : " · MySQL #" + sId), GREEN);
                    if ("MAIL".equals(currentView)) refreshMailboxAsync(false);
                    if ("DEMO".equals(currentView)) { loadSummaryAsync(); updateDemoState(); }
                }

                if (replies > 0 || sent > 0) {
                    if ("MAIL".equals(currentView)) loadSummaryAsync();
                } else if (loud) {
                    showToast(err == null ? "Không có thư mới từ Gmail" : "Đồng bộ Gmail lỗi",
                            err == null ? ACCENT : AMBER);
                    if (err != null) showAlert("Đồng bộ Gmail lỗi:\n" + err);
                }
                if (err != null) appendProof("IMAP ERROR → " + err);
                gmailSyncing = false;
            });
        }, "gmail-imap-sync");
        worker.setDaemon(true);
        worker.start();
    }

    private String firstRecipient(GmailImapService.MessageInfo mail) {
        if (mail == null) return "";
        if (mail.toAddresses != null) {
            for (String address : mail.toAddresses) {
                if (address != null && address.contains("@")) return address.trim();
            }
        }
        if (mail.to == null) return "";
        for (String part : mail.to.split(",")) {
            if (part.contains("@")) return part.trim();
        }
        return "";
    }

    private String resolveAccount(String email) {
        if (email == null || email.isBlank()) return null;
        try {
            String resp = core.send("FIND|" + email);
            if (resp.startsWith("OK|")) {
                String[] parts = resp.split("\\|", -1);
                if (parts.length >= 2 && !parts[1].isBlank()) return parts[1];
            }
        } catch (Exception ignored) { }
        return null;
    }

    private boolean isOurMailbox(GmailImapService.MessageInfo mail) {
        String account = mailConfig.getGmailAddress().toLowerCase(Locale.ROOT);
        int at = account.indexOf('@');
        if (at <= 0) return false;
        String local = account.substring(0, at);
        int plus = local.indexOf('+');
        String base = plus > 0 ? local.substring(0, plus) : local;
        String domain = account.substring(at);
        for (String address : mail.toAddresses) {
            String value = address.toLowerCase(Locale.ROOT);
            if (value.equals(account)) return true;
            if (value.startsWith(base + "+") && value.endsWith(domain)) return true;
            if (sameMailboxIgnoringDots(value, account)) return true;
        }
        return false;
    }

    /**
     * Gmail/Google Workspace bỏ qua dấu chấm ở phần tên hộp thư. Điện thoại có thể gửi tới
     * tritv24it@... trong khi tài khoản cấu hình là tritv.24it@..., nên phải coi là cùng hộp thư.
     */
    private boolean sameMailboxIgnoringDots(String left, String right) {
        int leftAt = left.indexOf('@');
        int rightAt = right.indexOf('@');
        if (leftAt <= 0 || rightAt <= 0) return false;
        String leftDomain = left.substring(leftAt);
        String rightDomain = right.substring(rightAt);
        if (!leftDomain.equals(rightDomain)) return false;
        String leftLocal = left.substring(0, leftAt);
        String rightLocal = right.substring(0, rightAt);
        int leftPlus = leftLocal.indexOf('+');
        int rightPlus = rightLocal.indexOf('+');
        if (leftPlus >= 0) leftLocal = leftLocal.substring(0, leftPlus);
        if (rightPlus >= 0) rightLocal = rightLocal.substring(0, rightPlus);
        return leftLocal.replace(".", "").equals(rightLocal.replace(".", ""));
    }

    private void checkGmailInbox() {
        mailConfig.activate(currentEmail);
        gmailAccount = mailConfig.getGmailAddress();
        gmailAppPassSecret = mailConfig.getGmailAppPassword();
        if (gmailAccount.isEmpty() || gmailAppPassSecret.isEmpty()) {
            showAlert("Chưa có Google App Password cho "
                    + (gmailAccount.isBlank() ? "tài khoản này" : gmailAccount) + ".\n"
                    + "Mỗi email cần App Password riêng. Nhập App Password trong Cấu hình rồi thử lại.");
            openSettings();
            return;
        }
        final String inboxAccount = gmailAccount;
        final String inboxPassword = gmailAppPassSecret;
        Thread worker = new Thread(() -> {
            try {
                // Dùng IMAP (giống chế độ đồng bộ) để không phụ thuộc vào việc bật POP trong Gmail.
                // fetchRecentRich lấy luôn HTML + ảnh/file đính kèm để reader hiển thị đầy đủ.
                List<GmailImapService.MessageInfo> messages =
                        GmailImapService.fetchRecentRich(inboxAccount, inboxPassword, 1);
                if (messages.isEmpty()) {
                    Platform.runLater(() -> showToast("Hộp thư Gmail đang trống", AMBER));
                    return;
                }
                GmailImapService.MessageInfo mail = messages.get(0);
                String from = mail.fromAddress == null ? "" : mail.fromAddress;
                String fromName = mail.fromName == null ? "" : mail.fromName;
                String subject = mail.subject == null ? "" : mail.subject;
                String body = mail.body == null ? "" : mail.body;
                String time = mail.sentDate == null
                        ? LocalTime.now().withNano(0).format(DateTimeFormatter.ofPattern("HH:mm"))
                        : new java.text.SimpleDateFormat("HH:mm").format(mail.sentDate);
                Platform.runLater(() -> {
                    if (mailList != null) {
                        allMail.removeIf(m -> "__gmail__".equals(m.fileName));
                        MailItem latest = new MailItem("GMAIL", "__gmail__",
                                from.isEmpty() ? inboxAccount : from, currentEmail,
                                subject.isEmpty() ? "Thư Google mới nhất" : subject,
                                time, body, false, fromName, mail.messageId);
                        latest.htmlBody = mail.htmlBody == null ? "" : mail.htmlBody;
                        latest.attachments = mail.attachments == null
                                ? new ArrayList<>() : mail.attachments;
                        allMail.add(0, latest);
                        applyFilter();
                        mailList.getSelectionModel().select(latest);
                    }
                    showToast("Đã tải thư Google mới nhất qua IMAP", GREEN);
                    appendProof("IMAP OK → " + (from.isEmpty() ? inboxAccount : from));
                });
            } catch (Exception e) {
                Platform.runLater(() -> showAlert("Hộp thư Google: " + e.getMessage()
                        + "\n\nỨng dụng dùng IMAP, không cần bật POP. Kiểm tra App Password và bật IMAP tại:\n"
                        + "Gmail → Cài đặt → Chuyển tiếp và POP/IMAP → Bật IMAP."));
            }
        }, "gmail-imap-latest");
        worker.setDaemon(true);
        worker.start();
    }

    // ══════════════════════════════════════════════════════════════════
    //  SUMMARY / DEMO STATE
    // ══════════════════════════════════════════════════════════════════
    private void loadSummaryAsync() {
        if (currentUser == null || summaryLoading) return;
        summaryLoading = true;
        lastSummaryAt = System.currentTimeMillis();
        final String user = currentUser;
        Thread worker = new Thread(() -> {
            String resp;
            try { resp = core.send("SUMMARY|" + user); }
            catch (Exception ex) { resp = "ERROR|" + ex.getMessage(); }
            final String result = resp;
            Platform.runLater(() -> {
                summaryLoading = false;
                if (!result.startsWith("OK|SUMMARY|")) {
                    updateDbStatus(false, cleanError(result));
                    if (demoDbState != null) setInlineStatus(demoDbState, "DB offline: " + cleanError(result), AMBER, AMBER_BG);
                    return;
                }
                String[] p = result.split("\\|", -1);
                try {
                    int inboxCount = Integer.parseInt(p[2]);
                    int sentCount = Integer.parseInt(p[3]);
                    int totalEmails = Integer.parseInt(p[4]);
                    int totalAccounts = Integer.parseInt(p[5]);
                    String liId = p.length > 6 ? p[6] : "";
                    String liName = p.length > 7 ? p[7] : "";
                    String liFrom = p.length > 8 ? p[8] : "";
                    String liSubject = p.length > 9 ? p[9] : "";
                    String liTime = p.length > 10 ? p[10] : "";
                    String lsId = p.length > 11 ? p[11] : "";
                    String lsTo = p.length > 12 ? p[12] : "";
                    String lsSubject = p.length > 13 ? p[13] : "";
                    String lsTime = p.length > 14 ? p[14] : "";

                    dbConnected = true;
                    updateDbStatus(true, "lab8_mail.emails · " + totalEmails + " email · " + totalAccounts + " tài khoản");
                    lastTotalEmails = totalEmails;
                    if (demoStatEmails != null) demoStatEmails.setText(String.valueOf(totalEmails));
                    if (demoStatInbox != null) demoStatInbox.setText(String.valueOf(inboxCount));
                    if (demoStatSent != null) demoStatSent.setText(String.valueOf(sentCount));
                    if (demoStatAccounts != null) demoStatAccounts.setText(String.valueOf(totalAccounts));
                    if (demoDbState != null) setInlineStatus(demoDbState,
                            "lab8_mail · CONNECTED", GREEN, GREEN_BG);
                    setBadge(inboxBadge, inboxCount);
                    setBadge(sentBadge, sentCount);

                    if (!lsId.isBlank()) {
                        boolean isNew = !lsId.equals(lastSentId);
                        lastSentId = lsId;
                        lastSentTo = lsTo;
                        lastSentSubject = lsSubject;
                        lastSentTime = lsTime;
                        if (isNew) appendProof("DB SENT #" + lsId + " → " + lsTo + " | " + lsSubject);
                    }
                    if (demoLastSent != null) {
                        demoLastSent.setText(lastSentId.isBlank() ? "chưa có thư gửi"
                                : "#" + lastSentId + " → " + lastSentTo + "\n" + shortTime(lastSentTime)
                                  + " · " + lastSentSubject);
                    }

                    if (!liId.isBlank()) {
                        boolean newReply = !lastReplyId.isBlank() && !liId.equals(lastReplyId);
                        lastReplyId = liId;
                        lastReplyName = liName;
                        lastReplyFrom = liFrom;
                        lastReplySubject = liSubject;
                        lastReplyTime = liTime;
                        if (newReply) {
                            appendProof("DB INBOX #" + liId + " ← " + (liName.isBlank() ? liFrom : liName)
                                    + " <" + liFrom + "> | " + liSubject);
                            showToast("Thư trả lời mới trong Hộp thư · MySQL #" + liId, GREEN);
                            if ("MAIL".equals(currentView) && "INBOX".equals(currentFolder)) refreshMailboxAsync(false);
                        }
                    }
                    if (demoLastReply != null) {
                        demoLastReply.setText(lastReplyId.isBlank() ? "chưa nhận trả lời"
                                : "#" + lastReplyId + " ← " + (lastReplyName.isBlank() ? lastReplyFrom : lastReplyName)
                                  + " <" + lastReplyFrom + ">\n" + shortTime(lastReplyTime) + " · " + lastReplySubject);
                    }
                    updateDemoState();
                } catch (Exception parseError) {
                    if (demoDbState != null) setInlineStatus(demoDbState, "Lỗi đọc số liệu: " + parseError.getMessage(), RED, RED_BG);
                }
            });
        }, "summary-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void loadDbInfo() {
        if (currentUser == null) return;
        Thread worker = new Thread(() -> {
            String resp;
            try { resp = core.send("DBINFO"); }
            catch (Exception ex) { resp = "ERROR|" + ex.getMessage(); }
            final String result = resp;
            Platform.runLater(() -> {
                if (!result.startsWith("OK|DB|")) {
                    dbConnected = false;
                    updateDbStatus(false, cleanError(result));
                    if (demoDbState != null) setInlineStatus(demoDbState, "DB offline", AMBER, AMBER_BG);
                    return;
                }
                dbConnected = true;
                String[] p = result.split("\\|", -1);
                if (demoDbState != null) {
                    setInlineStatus(demoDbState, p[2] + " email · " + p[3] + " tài khoản · MySQL lab8_mail.emails",
                            GREEN, GREEN_BG);
                }
                updateDbStatus(true, "lab8_mail.emails · " + p[2] + " email · " + p[3] + " tài khoản");
            });
        }, "db-info");
        worker.setDaemon(true);
        worker.start();
    }

    private void updateDemoState() {
        if (step1State == null) return;
        if (!lastSentId.isBlank()) setStep(step1State, "MySQL #" + lastSentId, "done");
        else if (!lastSentTo.isBlank()) setStep(step1State, "đã gửi (chưa có DB id)", "warn");
        else setStep(step1State, "chưa bắt đầu", "idle");

        if (!lastSentTo.isBlank()) setStep(step2State, "đã gửi tới " + shortEmail(lastSentTo), "done");
        else setStep(step2State, "chờ gửi thư", "idle");

        if (!lastReplyId.isBlank()) setStep(step3State, "đã nhận · MySQL #" + lastReplyId, "done");
        else if (!lastSentTo.isBlank()) setStep(step3State, "đang chờ điện thoại trả lời", "warn");
        else setStep(step3State, "chưa có thư để trả lời", "idle");

        if (dbConnected && lastTotalEmails > 0) setStep(step4State, lastTotalEmails + " bản ghi trong emails", "done");
        else if (dbConnected) setStep(step4State, "DB đã kết nối, chưa có email", "warn");
        else setStep(step4State, "DB offline", "error");

        if (demoProfile != null) demoProfile.setText(currentName + " <" + senderAddress() + ">");
        updateSyncLabels();
    }

    private void setStep(Label state, String text, String kind) {
        if (state == null) return;
        state.setText(text);
        String fg, bg;
        switch (kind) {
            case "done":  fg = GREEN; bg = GREEN_BG; break;
            case "warn":  fg = AMBER; bg = AMBER_BG; break;
            case "error": fg = RED; bg = RED_BG; break;
            default:      fg = MUTED; bg = BG; break;
        }
        state.setStyle("-fx-text-fill:" + fg + "; -fx-font-size:9px; -fx-font-weight:bold; "
                + "-fx-background-color:" + bg + "; -fx-padding:3 8; -fx-background-radius:8;");
    }

    private void appendProof(String line) {
        if (line == null || line.isBlank()) return;
        proofBuffer.append("[").append(LocalTime.now().withNano(0).format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                .append("] ").append(line).append("\n");
        if (proofBuffer.length() > 12000) proofBuffer.delete(0, proofBuffer.length() - 12000);
        if (proofLog != null) {
            final String text = proofBuffer.toString();
            Platform.runLater(() -> {
                proofLog.setText(text);
                proofLog.setScrollTop(Double.MAX_VALUE);
            });
        }
    }

    private String extractDbId(String response) {
        if (response == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("emails\\.id=(\\d+)").matcher(response);
        return m.find() ? m.group(1) : "";
    }

    private String cleanError(String response) {
        if (response == null) return "";
        if (response.startsWith("ERROR|")) return response.substring(6);
        return response;
    }

    // ══════════════════════════════════════════════════════════════════
    //  AUTH FLOW — đăng nhập / đăng ký / đăng xuất
    // ══════════════════════════════════════════════════════════════════
    private void doLogin() {
        if (core == null) {
            setFeedback(loginFeedback, "Chưa kết nối được server. Hãy chạy run-server.bat trước rồi thử lại.", true);
            return;
        }
        final String user = loginUserField.getText() == null ? "" : loginUserField.getText().trim();
        final String pass = loginPassField.getText() == null ? "" : loginPassField.getText();
        if (user.isEmpty() || pass.isEmpty()) {
            setFeedback(loginFeedback, "Nhập tên đăng nhập và mật khẩu để tiếp tục.", true);
            return;
        }

        setFeedback(loginFeedback, "Đang xác thực tài khoản trên server…", false);
        setLoginBusy(true);
        log("Đang đăng nhập @" + user + " …");
        Thread worker = new Thread(() -> {
            String resp;
            try { resp = core.send("LOGIN|" + user + "|" + pass); }
            catch (Exception ex) { resp = "ERROR|" + ex.getMessage(); }
            final String result = resp;
            Platform.runLater(() -> {
                setLoginBusy(false);
                if (!result.startsWith("OK|")) {
                    String msg = cleanError(result);
                    setFeedback(loginFeedback, "Đăng nhập thất bại: " + msg, true);
                    log("Đăng nhập lỗi: " + msg);
                    return;
                }
                setFeedback(loginFeedback, "", true);
                currentUser = user;
                currentPass = pass;
                currentEmail = extractLine(result, "EMAIL");
                currentName = extractLine(result, "NAME");
                if (currentEmail.isBlank()) currentEmail = user + "@gmail.com";
                if (currentName.isBlank()) currentName = user;
                dbConnected = result.contains("DB|CONNECTED");
                updateDbStatus(dbConnected, "MySQL lab8_mail");

                loginPassField.clear();
                mailSessionStart = System.currentTimeMillis();
                lastSentId = lastSentTo = lastSentSubject = lastSentTime = "";
                lastReplyId = lastReplyFrom = lastReplyName = lastReplySubject = lastReplyTime = "";
                proofBuffer.setLength(0);

                // Mỗi tài khoản dùng chính email của nó + App Password riêng.
                mailConfig.activate(currentEmail);
                gmailAccount = currentEmail;
                gmailAppPassSecret = mailConfig.getGmailAppPassword();
                gmailVerified = null;
                mailConfig.save();

                updateProfileLabels();
                if (primaryStage != null) primaryStage.setTitle(windowTitle(currentUser));
                rootStack.getChildren().setAll(mainPage);
                FadeTransition fade = new FadeTransition(Duration.millis(240), mainPage);
                fade.setFromValue(0.2);
                fade.setToValue(1);
                fade.play();
                currentView = "MAIL";
                currentFolder = "INBOX";
                selectedMail = null;
                selectNav(navInbox);
                showMailView();
                parseMailbox(result, "INBOX");
                updateGmailStatus();
                setBadge(inboxBadge, 0);
                setBadge(sentBadge, 0);

                log("Đăng nhập thành công: " + currentName + " <" + currentEmail + ">");
                appendProof("LOGIN OK → " + currentName + " <" + currentEmail + ">");

                if (currentEmail.matches(MailConfig.EMAIL_PATTERN)
                        && mailConfig.isGmailMode() && !mailConfig.hasValidGmailCredentials()) {
                    showToast("Xin chào " + currentName + " · Gmail cần App Password riêng cho " + currentEmail, AMBER);
                } else {
                    showToast("Xin chào " + currentName, ACCENT);
                }
                loadSummaryAsync();
                startGmailReplySync();
                lastGoogleAvatarSyncAt = 0L;
                googleAvatarPromptShown = false;
                refreshGoogleAvatarAsync(false);
                offerGoogleAvatarSync();
            });
        }, "login");
        worker.setDaemon(true);
        worker.start();
    }

    private void setLoginBusy(boolean busy) {
        if (loginButton != null) {
            loginButton.setDisable(busy);
            loginButton.setText(busy ? "ĐANG ĐĂNG NHẬP…" : "ĐĂNG NHẬP");
        }
        if (loginUserField != null) loginUserField.setDisable(busy);
        if (loginPassField != null) loginPassField.setDisable(busy);
    }

    private void doRegister() {
        if (core == null) {
            setFeedback(registerFeedback, "Chưa kết nối được server. Hãy chạy run-server.bat trước rồi thử lại.", true);
            return;
        }
        String user = regUserField.getText() == null ? "" : regUserField.getText().trim();
        String name = regNameField.getText() == null ? "" : regNameField.getText().trim();
        String email = regEmailField.getText() == null ? "" : regEmailField.getText().trim();
        String pass = regPassField.getText() == null ? "" : regPassField.getText();
        String confirm = regPassConfirmField.getText() == null ? "" : regPassConfirmField.getText();

        if (!user.matches("[A-Za-z0-9._-]{1,32}")) {
            setFeedback(registerFeedback, "Tên đăng nhập gồm 1-32 chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.", true);
            regUserField.requestFocus();
            return;
        }
        if (name.isEmpty()) {
            setFeedback(registerFeedback, "Nhập tên hiển thị — đây là tên người nhận thấy trên điện thoại.", true);
            regNameField.requestFocus();
            return;
        }
        if (!email.matches(MailConfig.EMAIL_PATTERN)) {
            setFeedback(registerFeedback, "Email thật không hợp lệ. Ví dụ: ban@gmail.com, ban@vku.udn.vn.", true);
            regEmailField.requestFocus();
            return;
        }
        if (pass.length() < 6) {
            setFeedback(registerFeedback, "Mật khẩu phải có ít nhất 6 ký tự.", true);
            regPassField.requestFocus();
            return;
        }
        if (!pass.equals(confirm)) {
            setFeedback(registerFeedback, "Xác nhận mật khẩu chưa khớp.", true);
            regPassConfirmField.clear();
            regPassConfirmField.requestFocus();
            return;
        }

        setFeedback(registerFeedback, "Đang tạo tài khoản trên server…", false);
        registerButton.setDisable(true);
        registerButton.setText("ĐANG TẠO…");
        log("Đang tạo tài khoản @" + user + " …");
        Thread worker = new Thread(() -> {
            String resp;
            try { resp = core.send("REGISTER|" + user + "|" + pass + "|" + email + "|" + name); }
            catch (Exception ex) { resp = "ERROR|" + ex.getMessage(); }
            final String result = resp;
            Platform.runLater(() -> {
                registerButton.setDisable(false);
                registerButton.setText("TẠO TÀI KHOẢN");
                if (!result.startsWith("OK|")) {
                    String msg = cleanError(result);
                    setFeedback(registerFeedback, "Tạo tài khoản thất bại: " + msg, true);
                    log("Đăng ký lỗi: " + msg);
                    return;
                }
                setFeedback(registerFeedback, "", true);
                showToast("Đã tạo tài khoản @" + user, GREEN);
                appendProof("REGISTER OK → @" + user + " | " + email + " | " + name);
                log("Đã tạo tài khoản @" + user + " (" + email + ")");
                regPassField.clear();
                regPassConfirmField.clear();
                loginUserField.setText(user);
                loginPassField.setText(pass);
                switchAuthTab(true);
                doLogin();
            });
        }, "register");
        worker.setDaemon(true);
        worker.start();
    }

    private void logout() {
        currentUser = null;
        currentEmail = null;
        currentName = null;
        currentPass = null;
        mailConfig.activate("");
        gmailAccount = "";
        gmailAppPassSecret = "";
        gmailVerified = null;
        updateGmailStatus();
        selectedMail = null;
        allMail.clear();
        if (mailList != null) {
            suppressReader = true;
            mailList.getItems().clear();
            suppressReader = false;
        }
        if (readerDetail != null) showReaderEmpty();
        setBadge(inboxBadge, 0);
        setBadge(sentBadge, 0);
        if (loginUserField != null) loginUserField.setText(DEFAULT_LOGIN_USER);
        if (loginPassField != null) loginPassField.clear();
        if (primaryStage != null) primaryStage.setTitle(windowTitle(null));
        if (rootStack != null && authPage != null) rootStack.getChildren().setAll(authPage);
        if (tabLogin != null) switchAuthTab(true);
    }

    // ══════════════════════════════════════════════════════════════════
    //  MAILBOX LOAD / FILTER / PARSE
    // ══════════════════════════════════════════════════════════════════
    private void refreshMailboxAsync(boolean loud) {
        if (currentUser == null || currentPass == null || refreshing || isSending) return;
        final String user = currentUser;
        final String pass = currentPass;
        final String folder = currentFolder;
        refreshing = true;

        Thread worker = new Thread(() -> {
            String resp;
            try {
                resp = "SENT".equalsIgnoreCase(folder)
                        ? core.send("MAILBOX|" + user + "|" + pass + "|SENT")
                        : core.send("MAILBOX|" + user + "|" + pass);
            } catch (Exception ex) {
                resp = "ERROR|" + ex.getMessage();
            }
            final String result = resp;
            Platform.runLater(() -> {
                refreshing = false;
                if (!user.equals(currentUser) || !folder.equalsIgnoreCase(currentFolder)) return;
                if (!result.startsWith("OK|")) {
                    if (loud) showAlert("Không tải được hộp thư:\n" + cleanError(result));
                    log("Hộp thư lỗi: " + cleanError(result));
                    return;
                }
                if (!"SENT".equalsIgnoreCase(folder)) {
                    String email = extractLine(result, "EMAIL");
                    String name = extractLine(result, "NAME");
                    if (!email.isBlank()) currentEmail = email;
                    if (!name.isBlank()) currentName = name;
                    updateProfileLabels();
                }
                parseMailbox(result, folder);
                if (loud) showToast("Đã cập nhật hộp thư", ACCENT);
            });
        }, "mail-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    private void openFolder(String folder) {
        if (folder == null || folder.isBlank()) folder = "INBOX";
        currentFolder = folder;
        selectedMail = null;
        allMail.clear();
        if (mailList != null) {
            suppressReader = true;
            mailList.getItems().clear();
            suppressReader = false;
        }
        showReaderEmpty();
        if (listCountLabel != null) listCountLabel.setText("0");
        refreshMailboxAsync(false);
    }

    private void applyFilter() {
        if (mailList == null) return;
        String query = searchField == null || searchField.getText() == null
                ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<MailItem> visible = new ArrayList<>();
        for (MailItem m : allMail) {
            if (query.isEmpty()) {
                visible.add(m);
                continue;
            }
            String haystack = ((m.from == null ? "" : m.from) + " "
                    + (m.fromName == null ? "" : m.fromName) + " "
                    + (m.to == null ? "" : m.to) + " "
                    + (m.subject == null ? "" : m.subject) + " "
                    + (m.preview == null ? "" : m.preview)).toLowerCase(Locale.ROOT);
            if (haystack.contains(query)) visible.add(m);
        }

        MailItem keep = selectedMail;
        boolean unchanged = sameMailItems(visible, mailList.getItems());
        if (!unchanged) {
            suppressReader = true;
            mailList.getItems().setAll(visible);
            if (keep != null) {
                for (MailItem item : visible) {
                    if (sameMailItem(item, keep)) {
                        mailList.getSelectionModel().select(item);
                        break;
                    }
                }
            }
            suppressReader = false;
        }
        if (listCountLabel != null) listCountLabel.setText(String.valueOf(visible.size()));

        boolean hasSelection = mailList.getSelectionModel().getSelectedItem() != null;
        if (!hasSelection) {
            if (!visible.isEmpty()) {
                // Tự mở thư mới nhất để người dùng thấy ngay thư vừa gửi/nhận.
                mailList.getSelectionModel().selectFirst();
            } else {
                showReaderEmpty();
            }
        }
    }

    /** So sánh danh sách thư để tránh setAll lại mỗi 3 giây (nguyên nhân gây giật). */
    private boolean sameMailItems(List<MailItem> a, List<MailItem> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!sameMailItem(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private boolean sameMailItem(MailItem a, MailItem b) {
        if (a == null || b == null) return a == b;
        return eq(a.folder, b.folder) && eq(a.fileName, b.fileName)
                && eq(a.subject, b.subject) && eq(a.time, b.time)
                && eq(a.messageId, b.messageId) && a.starred == b.starred;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void parseMailbox(String response, String folder) {
        if (response == null) return;
        List<MailItem> parsed = new ArrayList<>();
        for (String line : response.split("\n")) {
            if (!line.startsWith("MAIL|")) continue;
            String[] p = line.split("\\|", -1);
            if (p.length < 8) continue;
            String section = p[1];
            String file = p[2];
            String from = p[3];
            String to = p[4];
            String subject = p[5];
            String time = p[6];
            String preview = p[7];
            boolean starred = p.length > 8 && "1".equals(p[8]);
            String fromName = p.length > 9 ? p[9] : "";
            String messageId = p.length > 10 ? p[10] : "";
            parsed.add(new MailItem(section, file, from, to, subject, time, preview, starred,
                    fromName, messageId));
        }

        MailItem previous = selectedMail;
        allMail.clear();
        allMail.addAll(parsed);
        lastParsedFolder = folder;

        if (previous != null) {
            MailItem match = null;
            for (MailItem m : parsed) {
                if (m.folder != null && m.folder.equalsIgnoreCase(previous.folder)
                        && m.fileName != null && m.fileName.equals(previous.fileName)) {
                    match = m;
                    break;
                }
            }
            selectedMail = match;
        }

        if (listTitleLabel != null) {
            boolean sent = "SENT".equalsIgnoreCase(folder);
            listTitleLabel.setText("  " + (sent ? "Thư đã gửi" : "Hộp thư đến"));
        }
        applyFilter();
    }

    // ══════════════════════════════════════════════════════════════════
    //  IDENTITY HELPERS
    // ══════════════════════════════════════════════════════════════════
    private String senderDisplayName() {
        if (currentName != null && !currentName.isBlank()) return currentName;
        if (currentUser != null && !currentUser.isBlank()) return currentUser;
        return "MailFlow User";
    }

    private String senderAddress() {
        if (currentEmail != null && !currentEmail.isBlank()) return currentEmail;
        String configured = mailConfig.getGmailAddress();
        if (configured != null && !configured.isBlank()) return configured;
        return currentUser == null ? "" : currentUser + "@gmail.com";
    }

    private String ownSender() {
        return senderDisplayName() + " <" + senderAddress() + ">";
    }

    private String defaultRecipient() {
        String configured = mailConfig.getDefaultTo();
        if (configured != null && !configured.isBlank()) return configured;
        String gmail = mailConfig.getGmailAddress();
        return gmail == null ? "" : gmail;
    }

    private void updateProfileLabels() {
        String name = senderDisplayName();
        String email = currentEmail == null || currentEmail.isBlank() ? "chưa đăng nhập" : currentEmail;
        String initial = name == null || name.isBlank() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);

        if (headerName != null) headerName.setText(name);
        if (headerEmail != null) headerEmail.setText(email);
        if (sidebarName != null) sidebarName.setText(name);
        if (sidebarEmail != null) sidebarEmail.setText(email);
        applyAvatar(headerAvatar, currentEmail, name, 32, initial);
        applyAvatar(sidebarAvatar, currentEmail, name, 40, initial);
        // Cập nhật luôn ảnh xem trước nếu hộp Cấu hình đang mở (nút Đồng bộ lại).
        if (settingsAvatarPreview != null) {
            applyAvatar(settingsAvatarPreview, currentEmail, name, 54, initial);
        }

        if (demoAccountValue != null) {
            demoAccountValue.setText((currentUser == null ? "—" : "@" + currentUser)
                    + (currentEmail == null || currentEmail.isBlank() ? "" : " · " + currentEmail));
        }
        if (demoFromValue != null) {
            demoFromValue.setText(senderDisplayName() + "  <" + senderAddress() + ">");
        }
    }

    private void applyAvatar(Label label, String email, String name, double size, String fallbackInitial) {
        if (label == null) return;
        String initial = fallbackInitial == null || fallbackInitial.isBlank() ? "?" : fallbackInitial;
        String url = avatarUrlFor(email);
        if (url.isBlank() || AVATAR_FAILED.contains(url)) {
            setAvatarInitial(label, initial, size);
            return;
        }
        if (url.equals(label.getUserData())) return; // đã hiển thị đúng ảnh này rồi
        Image image = AVATAR_CACHE.get(url);
        if (image != null) {
            if (image.isError()) {
                AVATAR_FAILED.add(url);
                setAvatarInitial(label, initial, size);
                return;
            }
            if (image.getWidth() > 0) {
                label.setUserData(url);
                setAvatarImage(label, image, size);
            } else {
                // Ảnh còn đang tải: hiện chữ cái đầu và chờ ảnh xong để cập nhật đúng label này.
                setAvatarInitial(label, initial, size);
                watchAvatar(label, url, image, email, name, size, initial);
            }
            return;
        }

        setAvatarInitial(label, initial, size);
        try {
            Image loading = new Image(url, size * 3, size * 3, true, true, true);
            AVATAR_CACHE.put(url, loading);
            watchAvatar(label, url, loading, email, name, size, initial);
        } catch (Exception ex) {
            AVATAR_FAILED.add(url);
            setAvatarInitial(label, initial, size);
        }
    }

    /** Mỗi label chỉ gắn một listener cho một URL ảnh, tránh đăng ký lặp. */
    private void watchAvatar(Label label, String url, Image image, String email,
                             String name, double size, String initial) {
        if (url.equals(AVATAR_WATCHED.get(label))) return;
        AVATAR_WATCHED.put(label, url);
        image.progressProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue.doubleValue() >= 1.0 || image.isError()) {
                if (image.isError()) AVATAR_FAILED.add(url);
                Platform.runLater(() -> {
                    if (!url.equals(AVATAR_WATCHED.get(label))) return;
                    applyAvatar(label, email, name, size, initial);
                });
            }
        });
    }

    private void setAvatarInitial(Label label, String initial, double size) {
        if (label == null) return;
        String key = "init|" + initial + "|" + size;
        if (key.equals(label.getUserData())) return;
        label.setUserData(key);
        label.setGraphic(null);
        label.setContentDisplay(ContentDisplay.TEXT_ONLY);
        label.setText(initial);
        label.setAlignment(Pos.CENTER);
        label.setStyle("-fx-background-color: linear-gradient(to bottom right, " + ACCENT + ", " + SKY
                + "); -fx-text-fill:white; -fx-font-size:" + Math.max(10, size * 0.36)
                + "px; -fx-font-weight:bold; -fx-background-radius:50; -fx-padding:0;");
    }

    private void setAvatarImage(Label label, Image image, double size) {
        if (label == null || image == null || image.isError()) return;
        ImageView view = new ImageView(image);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        Rectangle clip = new Rectangle(size, size);
        clip.setArcWidth(size);
        clip.setArcHeight(size);
        view.setClip(clip);
        label.setText("");
        label.setGraphic(view);
        label.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        label.setStyle("-fx-background-color:transparent; -fx-padding:0;");
    }

    /**
     * Đồng bộ ảnh Gmail thật: dùng refresh token để lấy ảnh hiện tại, tải ảnh về
     * máy rồi cập nhật avatar ngay. Chạy ở thread nền, không chặn UI.
     */
    private void refreshGoogleAvatarAsync(boolean loud) {
        final String email = currentEmail == null ? "" : currentEmail.trim();
        final String clientId = mailConfig.getOAuthClientId();
        final String clientSecret = mailConfig.getOAuthClientSecret();
        final String refreshToken = mailConfig.getOAuthRefreshToken(email);
        if (email.isBlank() || clientId.isBlank() || refreshToken.isBlank()) return;

        // Tránh gọi trùng khi cửa sổ vừa lấy lại focus / timer vừa chạy.
        long now = System.currentTimeMillis();
        if (!loud && now - lastGoogleAvatarSyncAt < 45_000L) return;
        lastGoogleAvatarSyncAt = now;

        Thread worker = new Thread(() -> {
            try {
                GoogleOAuthService.UserInfo info = GoogleOAuthService.fetchUserInfoWithRefresh(
                        clientId, clientSecret, refreshToken);
                if (!sameGoogleEmail(info.email, email)) return;

                // Ảnh đã bị xóa trên tài khoản Google → bỏ ảnh cũ, quay về chữ cái đầu.
                if (info.pictureUrl.isBlank()) {
                    Platform.runLater(() -> {
                        if (currentEmail == null || !sameGoogleEmail(currentEmail, email)) return;
                        AVATAR_CACHE.clear();
                        AVATAR_FAILED.clear();
                        AVATAR_WATCHED.clear();
                        mailConfig.clearGooglePhoto(email);
                        mailConfig.save();
                        updateProfileLabels();
                        if (loud) showToast("Tài khoản Google hiện không có ảnh đại diện", AMBER);
                    });
                    return;
                }

                // Tải ảnh thật về avatars/ để hiện ngay và không phụ thuộc URL tạm của Google.
                String localUri = "";
                try {
                    java.io.File cached = GoogleOAuthService.cachePicture(email, info.pictureUrl);
                    if (cached != null) localUri = cached.toURI().toString();
                } catch (Exception ignored) {
                    // Vẫn dùng URL trực tiếp nếu không tải về được.
                }
                final String avatarUri = localUri;

                Platform.runLater(() -> {
                    if (currentEmail == null || !sameGoogleEmail(currentEmail, email)) return;
                    AVATAR_CACHE.clear();
                    AVATAR_FAILED.clear();
                    AVATAR_WATCHED.clear();
                    mailConfig.applyGooglePhoto(email, info.pictureUrl, avatarUri);
                    mailConfig.save();
                    updateProfileLabels();
                    if (loud) showToast(avatarUri.isBlank()
                            ? "Đã đồng bộ ảnh Google" : "Đã đồng bộ ảnh Gmail thật", GREEN);
                });
            } catch (Exception ex) {
                final String message = ex.getMessage() == null ? "" : ex.getMessage();
                final boolean expired = message.toLowerCase(Locale.ROOT).contains("invalid_grant")
                        || message.toLowerCase(Locale.ROOT).contains("hết hiệu lực");
                Platform.runLater(() -> {
                    if (expired) {
                        showToast("Phiên Google đã hết hạn. Bấm ảnh đại diện để đăng nhập lại.", AMBER);
                        promptGoogleAvatarRelogin();
                    } else if (loud) {
                        showToast("Chưa làm mới được ảnh Google: " + message, AMBER);
                    }
                });
            }
        }, "google-avatar-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    /** Nhắc một lần ngay sau đăng nhập nếu tài khoản Google chưa có ảnh thật. */
    private void offerGoogleAvatarSync() {
        if (currentEmail == null || currentEmail.isBlank()) return;
        if (!MailConfig.isGoogleMailbox(currentEmail)) return;
        if (mailConfig.hasGoogleOAuth(currentEmail)) return;
        if (googleAvatarPromptShown) return;
        googleAvatarPromptShown = true;

        showToast("Ảnh Gmail thật: bấm vào ảnh đại diện góc phải để đồng bộ một lần", ACCENT);

        // Đã cấu hình OAuth Client ID từ trước nhưng tài khoản này chưa đăng nhập Google.
        if (!mailConfig.getOAuthClientId().isBlank()) {
            Platform.runLater(() -> openGoogleAvatarDialog(this::updateProfileLabels));
        }
    }

    /** Refresh token hết hạn (app Google còn ở chế độ Testing) → mời đăng nhập lại. */
    private void promptGoogleAvatarRelogin() {
        if (googleAvatarPromptShown) return;
        if (currentEmail == null || !MailConfig.isGoogleMailbox(currentEmail)) return;
        if (mailConfig.getOAuthClientId().isBlank()) return;
        googleAvatarPromptShown = true;
        Platform.runLater(() -> openGoogleAvatarDialog(this::updateProfileLabels));
    }

    private String avatarUrlFor(String email) {
        if (email == null || !email.trim().matches(MailConfig.EMAIL_PATTERN)) return "";

        // Đã kết nối Google → ảnh thật của tài khoản luôn được ưu tiên.
        if (mailConfig.hasGoogleOAuth(email)) {
            String googleAvatar = mailConfig.getAvatarUrl(email);
            if (googleAvatar != null && !googleAvatar.isBlank()) return googleAvatar.trim();
            String googlePhoto = mailConfig.getGooglePhotoUrl(email);
            if (googlePhoto != null && !googlePhoto.isBlank()) return googlePhoto.trim();
        }

        String custom = mailConfig.getAvatarUrl(email);
        if (custom != null && !custom.isBlank()) return custom.trim();
        // Ảnh công khai theo địa chỉ email (Google/Gravatar/…), trả 404 nếu không có.
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("@")) return "https://unavatar.io/" + normalized + "?fallback=false";
        String hash = md5Hex(normalized);
        return hash.isBlank() ? "" : "https://www.gravatar.com/avatar/" + hash + "?s=200&d=404";
    }

    private static String md5Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private void updateGmailStatus() {
        if (gmailStatusLabel == null) return;
        String address = currentEmail == null ? "" : currentEmail.trim();
        String pass = mailConfig.getAppPassword(address);
        String text;
        String fg;
        String bg;
        if (address.isBlank()) {
            text = "● Google: chưa có email tài khoản";
            fg = MUTED; bg = "#F8FAFC";
        } else if (Boolean.FALSE.equals(gmailVerified)) {
            text = "● Google: lỗi App Password";
            fg = RED; bg = RED_BG;
        } else if (MailConfig.isValidAppPassword(pass)) {
            text = "● Google: " + shortEmail(address);
            fg = GREEN; bg = GREEN_BG;
        } else {
            text = "● Google: cần App Password riêng";
            fg = AMBER; bg = AMBER_BG;
        }
        gmailStatusLabel.setText(text);
        gmailStatusLabel.setStyle("-fx-text-fill:" + fg + "; -fx-background-color:" + bg
                + "; -fx-font-size:9.5px; -fx-font-weight:bold; -fx-padding:5 11; -fx-background-radius:14;");
    }

    private void updateDbStatus(boolean connected, String detail) {
        dbConnected = connected;
        if (dbStatusLabel == null) return;
        dbStatusLabel.setText(connected ? "● DB: MySQL" : "● DB: offline");
        dbStatusLabel.setStyle("-fx-text-fill:" + (connected ? GREEN : AMBER)
                + "; -fx-background-color:" + (connected ? GREEN_BG : AMBER_BG)
                + "; -fx-font-size:9.5px; -fx-font-weight:bold; -fx-padding:5 11; -fx-background-radius:14;");
        if (detail != null && !detail.isBlank()) dbStatusLabel.setTooltip(new Tooltip(detail));
    }

    // ══════════════════════════════════════════════════════════════════
    //  SMALL UI HELPERS
    // ══════════════════════════════════════════════════════════════════
    private String cardStyle(int radius) {
        return "-fx-background-color:" + SURFACE + "; -fx-background-radius:" + radius
                + "; -fx-border-color:" + LINE + "; -fx-border-radius:" + radius + ";"
                + " -fx-effect: dropshadow(gaussian, rgba(15,23,42,0.055), 10, 0, 0, 2);";
    }

    private Label statusPill(String text, String color, String background) {
        Label pill = new Label(text);
        pill.setStyle("-fx-text-fill:" + color + "; -fx-background-color:" + background
                + "; -fx-font-size:9.5px; -fx-font-weight:bold; -fx-padding:5 11; -fx-background-radius:14;");
        return pill;
    }

    private Label sep() {
        Label dot = new Label("·");
        dot.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:10px;");
        return dot;
    }

    private Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill:rgba(255,255,255,0.32); -fx-font-size:8.5px; -fx-font-weight:bold;"
                + " -fx-padding:8 0 2 10;");
        return label;
    }

    private Button iconButton(FontAwesomeSolid icon, String tooltip, String color) {
        FontIcon fi = new FontIcon(icon);
        fi.setIconSize(13);
        fi.setIconColor(Color.web(color));
        Button button = new Button();
        button.setGraphic(fi);
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);
        String normal = "-fx-background-color:transparent; -fx-padding:8; -fx-background-radius:9; -fx-cursor:hand;";
        String hover = "-fx-background-color:" + BG + "; -fx-padding:8; -fx-background-radius:9; -fx-cursor:hand;";
        button.setStyle(normal);
        button.setOnMouseEntered(e -> button.setStyle(hover));
        button.setOnMouseExited(e -> button.setStyle(normal));
        return button;
    }

    private Button primaryButton(String text, FontAwesomeSolid icon) {
        FontIcon fi = new FontIcon(icon);
        fi.setIconSize(11);
        fi.setIconColor(Color.WHITE);
        Button button = new Button(text);
        button.setGraphic(fi);
        button.setGraphicTextGap(7);
        button.setFocusTraversable(false);
        String normal = "-fx-background-color: linear-gradient(to right, " + ACCENT + ", " + ACCENT_DARK
                + "); -fx-text-fill:white; -fx-font-size:10.5px; -fx-font-weight:bold; -fx-padding:9 15;"
                + " -fx-background-radius:10; -fx-cursor:hand;";
        String hover = "-fx-background-color: linear-gradient(to right, " + ACCENT_DARK + ", " + SKY
                + "); -fx-text-fill:white; -fx-font-size:10.5px; -fx-font-weight:bold; -fx-padding:9 15;"
                + " -fx-background-radius:10; -fx-cursor:hand;"
                + " -fx-effect: dropshadow(gaussian, rgba(37,99,235,0.35), 14, 0, 0, 3);";
        button.setStyle(normal);
        button.setOnMouseEntered(e -> button.setStyle(hover));
        button.setOnMouseExited(e -> button.setStyle(normal));
        return button;
    }

    private Button ghostButton(FontAwesomeSolid icon, String text) {
        FontIcon fi = new FontIcon(icon);
        fi.setIconSize(11);
        fi.setIconColor(Color.web(ACCENT));
        Button button = new Button(text);
        button.setGraphic(fi);
        button.setGraphicTextGap(6);
        button.setFocusTraversable(false);
        String normal = "-fx-background-color:" + SURFACE + "; -fx-text-fill:" + SLATE
                + "; -fx-font-size:10.5px; -fx-font-weight:bold; -fx-padding:8 12; -fx-background-radius:9;"
                + " -fx-border-color:" + LINE + "; -fx-border-radius:9; -fx-cursor:hand;";
        String hover = "-fx-background-color:" + SOFT + "; -fx-text-fill:" + ACCENT
                + "; -fx-font-size:10.5px; -fx-font-weight:bold; -fx-padding:8 12; -fx-background-radius:9;"
                + " -fx-border-color:#BFDBFE; -fx-border-radius:9; -fx-cursor:hand;";
        button.setStyle(normal);
        button.setOnMouseEntered(e -> button.setStyle(hover));
        button.setOnMouseExited(e -> button.setStyle(normal));
        return button;
    }

    private Button toolButton(FontAwesomeSolid icon, String text, String color) {
        FontIcon fi = new FontIcon(icon);
        fi.setIconSize(11);
        fi.setIconColor(Color.web(color));
        Button button = new Button(text);
        button.setGraphic(fi);
        button.setGraphicTextGap(6);
        button.setFocusTraversable(false);
        String normal = "-fx-background-color:" + SURFACE + "; -fx-text-fill:" + color
                + "; -fx-font-size:10.5px; -fx-font-weight:bold; -fx-padding:8 13; -fx-background-radius:9;"
                + " -fx-border-color:" + LINE + "; -fx-border-radius:9; -fx-cursor:hand;";
        String hover = "-fx-background-color:" + BG + "; -fx-text-fill:" + color
                + "; -fx-font-size:10.5px; -fx-font-weight:bold; -fx-padding:8 13; -fx-background-radius:9;"
                + " -fx-border-color:" + color + "; -fx-border-radius:9; -fx-cursor:hand;";
        button.setStyle(normal);
        button.setOnMouseEntered(e -> button.setStyle(hover));
        button.setOnMouseExited(e -> button.setStyle(normal));
        return button;
    }

    private Button formatToolbarButton(String text, String tip, boolean bold, boolean italic) {
        Button button = new Button(text);
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tip));
        String style = "-fx-background-color:transparent; -fx-text-fill:" + SLATE
                + "; -fx-font-size:12.5px; -fx-font-weight:" + (bold ? "bold" : "normal")
                + (italic ? "; -fx-font-style:italic;" : ";")
                + " -fx-padding:3 9; -fx-background-radius:6; -fx-cursor:hand;";
        String hover = "-fx-background-color:" + SOFT + "; -fx-text-fill:" + ACCENT
                + "; -fx-font-size:12.5px; -fx-font-weight:" + (bold ? "bold" : "normal")
                + (italic ? "; -fx-font-style:italic;" : ";")
                + " -fx-padding:3 9; -fx-background-radius:6; -fx-cursor:hand;";
        button.setStyle(style);
        button.setOnMouseEntered(e -> button.setStyle(hover));
        button.setOnMouseExited(e -> button.setStyle(style));
        return button;
    }

    private static String firstAddress(String raw) {
        if (raw == null) return "";
        String[] parts = raw.split("[,;\\s]+");
        return parts.length == 0 ? "" : parts[0].trim();
    }

    private List<String> parseAddressList(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) return result;
        for (String part : raw.split("[,;\\s]+")) {
            String address = part.trim();
            if (!address.isEmpty() && !result.contains(address)) result.add(address);
        }
        return result;
    }

    private void wrapComposeSelection(TextArea area, String prefix, String suffix) {
        IndexRange range = area.getSelection();
        if (range.getLength() == 0) {
            area.insertText(area.getCaretPosition(), prefix + suffix);
            area.positionCaret(area.getCaretPosition() - suffix.length());
        } else {
            String selected = area.getText().substring(range.getStart(), range.getEnd());
            area.replaceText(range.getStart(), range.getEnd(), prefix + selected + suffix);
            area.selectRange(range.getStart() + prefix.length(), range.getEnd() + prefix.length());
        }
        area.requestFocus();
    }

    private HBox fieldRow(FontAwesomeSolid icon, Label label, TextField field, Node extra) {
        FontIcon fi = new FontIcon(icon);
        fi.setIconSize(12);
        fi.setIconColor(Color.web(ACCENT));
        StackPane iconBox = new StackPane(fi);
        iconBox.setMinWidth(36);

        label.setMinWidth(66);
        field.setStyle("-fx-background-color:transparent; -fx-text-fill:" + INK
                + "; -fx-prompt-text-fill:" + DIM + "; -fx-font-size:12px; -fx-padding:10 2;");
        HBox.setHgrow(field, Priority.ALWAYS);

        HBox row = new HBox(8, iconBox, label, field);
        if (extra != null) row.getChildren().add(extra);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinHeight(46);
        row.setPadding(new Insets(2, 8, 2, 8));
        row.setStyle("-fx-background-color:" + BG + "; -fx-background-radius:11;"
                + " -fx-border-color:" + LINE + "; -fx-border-radius:11;");
        return row;
    }

    private VBox labeledInput(String text, Node field) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:9.5px; -fx-font-weight:bold;");
        VBox box = new VBox(5, label, field);
        return box;
    }

    private VBox labeledInput(FontAwesomeSolid glyph, String text, Node field) {
        FontIcon fi = new FontIcon(glyph);
        fi.setIconSize(10);
        fi.setIconColor(Color.web(MUTED));
        Label label = new Label(text);
        label.setStyle("-fx-text-fill:" + MUTED + "; -fx-font-size:9.5px; -fx-font-weight:bold;");
        HBox labelRow = new HBox(6, fi, label);
        labelRow.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(5, labelRow, field);
        return box;
    }

    private static FontIcon icon(FontAwesomeSolid glyph, String color, int size) {
        FontIcon fi = new FontIcon(glyph);
        fi.setIconSize(size);
        fi.setIconColor(Color.web(color));
        return fi;
    }

    private void setFeedback(Label feedback, String text, boolean error) {
        if (feedback == null) return;
        boolean show = text != null && !text.isBlank();
        feedback.setText(show ? text : "");
        feedback.setVisible(show);
        feedback.setManaged(show);
        if (show) {
            feedback.setStyle("-fx-text-fill:" + (error ? RED : GREEN) + "; -fx-font-size:10.5px; "
                    + "-fx-background-color:" + (error ? RED_BG : GREEN_BG) + "; -fx-padding:8 11; "
                    + "-fx-background-radius:8;");
        }
    }

    private Label noteLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:9.5px; -fx-line-spacing:1;");
        return label;
    }

    private TextField styledTextField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.setStyle("-fx-background-color:" + SURFACE + "; -fx-text-fill:" + INK
                + "; -fx-prompt-text-fill:" + DIM + "; -fx-font-size:12px; -fx-padding:10 12;"
                + " -fx-background-radius:10; -fx-border-color:" + LINE + "; -fx-border-radius:10;");
        return field;
    }

    private TextField lightField(FontAwesomeSolid icon, String prompt) {
        TextField field = styledTextField(prompt);
        if (icon != null) field.setTooltip(new Tooltip(prompt));
        return field;
    }

    private VBox passwordFieldWrapper(PasswordField field, String prompt) {
        String style = "-fx-background-color:" + SURFACE + "; -fx-text-fill:" + INK
                + "; -fx-prompt-text-fill:" + DIM + "; -fx-font-size:12px; -fx-padding:10 12;"
                + " -fx-background-radius:10; -fx-border-color:" + LINE + "; -fx-border-radius:10;";
        field.setPromptText(prompt);
        field.setStyle(style);

        TextField plain = new TextField();
        plain.setPromptText(prompt);
        plain.setStyle(style);
        plain.textProperty().bindBidirectional(field.textProperty());
        plain.setVisible(false);

        StackPane stack = new StackPane(field, plain);
        HBox.setHgrow(stack, Priority.ALWAYS);

        FontIcon eye = new FontIcon(FontAwesomeSolid.EYE);
        eye.setIconSize(13);
        eye.setIconColor(Color.web(MUTED));
        Button toggle = new Button();
        toggle.setGraphic(eye);
        toggle.setFocusTraversable(false);
        String normal = "-fx-background-color:transparent; -fx-padding:8; -fx-background-radius:9; -fx-cursor:hand;";
        String hover = "-fx-background-color:" + BG + "; -fx-padding:8; -fx-background-radius:9; -fx-cursor:hand;";
        toggle.setStyle(normal);
        toggle.setOnMouseEntered(e -> toggle.setStyle(hover));
        toggle.setOnMouseExited(e -> toggle.setStyle(normal));
        toggle.setOnAction(e -> {
            boolean show = !plain.isVisible();
            plain.setVisible(show);
            field.setVisible(!show);
            eye.setIconCode(show ? FontAwesomeSolid.EYE_SLASH : FontAwesomeSolid.EYE);
            if (show) plain.requestFocus(); else field.requestFocus();
        });

        HBox row = new HBox(5, stack, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(row);
    }

    private HBox navItem(FontAwesomeSolid icon, String label, Runnable action) {
        FontIcon fi = new FontIcon(icon);
        fi.setIconSize(13);
        fi.setIconColor(Color.web("#94A3B8"));

        Label text = new Label(label);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        HBox badgeHolder = new HBox();
        badgeHolder.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(10, fi, text, grow, badgeHolder);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(9, 10, 9, 12));
        row.setUserData(Boolean.FALSE);
        applyNavPaint(row, false);

        row.setOnMouseClicked(e -> action.run());
        row.setOnMouseEntered(e -> {
            if (!Boolean.TRUE.equals(row.getUserData())) applyNavPaint(row, true);
        });
        row.setOnMouseExited(e -> {
            if (!Boolean.TRUE.equals(row.getUserData())) applyNavPaint(row, false);
        });
        return row;
    }

    private void applyNavPaint(HBox row, boolean hover) {
        if (row == null) return;
        boolean active = Boolean.TRUE.equals(row.getUserData());
        if (active) {
            row.setStyle("-fx-background-color: linear-gradient(to right, rgba(37,99,235,0.95), rgba(14,165,233,0.82));"
                    + " -fx-background-radius:10; -fx-cursor:hand;");
        } else if (hover) {
            row.setStyle("-fx-background-color: rgba(255,255,255,0.07); -fx-background-radius:10; -fx-cursor:hand;");
        } else {
            row.setStyle("-fx-background-color: transparent; -fx-background-radius:10; -fx-cursor:hand;");
        }
        if (row.getChildren().size() >= 2) {
            if (row.getChildren().get(0) instanceof FontIcon fi) {
                fi.setIconColor(Color.web(active ? "#FFFFFF" : (hover ? "#E2E8F0" : "#94A3B8")));
            }
            if (row.getChildren().get(1) instanceof Label label) {
                String textColor = active ? "#FFFFFF"
                        : (hover ? "rgba(255,255,255,0.92)" : "rgba(255,255,255,0.76)");
                label.setStyle("-fx-text-fill:" + textColor + "; -fx-font-size:11.5px; -fx-font-weight:"
                        + (active ? "bold" : "normal") + ";");
            }
        }
    }

    private void selectNav(HBox selected) {
        HBox[] items = { navInbox, navSent, navContacts, navDemo, navSettings };
        for (HBox item : items) {
            if (item == null) continue;
            item.setUserData(item == selected);
            applyNavPaint(item, false);
        }
    }

    private Label navBadge() {
        Label badge = new Label("0");
        badge.setStyle("-fx-text-fill:white; -fx-font-size:8.5px; -fx-font-weight:bold;"
                + " -fx-background-color:" + SKY + "; -fx-padding:1 6; -fx-background-radius:9;");
        badge.setVisible(false);
        badge.setManaged(false);
        return badge;
    }

    private void setBadge(Label badge, int count) {
        if (badge == null) return;
        badge.setText(count > 99 ? "99+" : String.valueOf(Math.max(0, count)));
        badge.setVisible(count > 0);
        badge.setManaged(count > 0);
    }

    private void styleModeChoice(Button button, boolean active) {
        if (button == null) return;
        if (active) {
            button.setStyle("-fx-background-color:" + ACCENT + "; -fx-text-fill:white; -fx-font-size:10.5px;"
                    + " -fx-font-weight:bold; -fx-background-radius:9; -fx-padding:8 13; -fx-cursor:hand;");
        } else {
            button.setStyle("-fx-background-color:" + BG + "; -fx-text-fill:" + MUTED
                    + "; -fx-border-color:" + LINE + "; -fx-border-radius:9; -fx-background-radius:9;"
                    + " -fx-font-size:10.5px; -fx-font-weight:bold; -fx-padding:8 13; -fx-cursor:hand;");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  TEXT / COLOR HELPERS
    // ══════════════════════════════════════════════════════════════════
    private static String safeText(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String colorFor(String value) {
        String[] palette = { ACCENT, SKY, TEAL, "#0F766E", "#1D4ED8", "#0369A1", "#047857", "#B45309" };
        if (value == null || value.isEmpty()) return ACCENT;
        return palette[Math.abs(value.hashCode()) % palette.length];
    }

    private String shortEmail(String value) {
        if (value == null || value.isBlank()) return "—";
        String v = value.trim();
        int at = v.indexOf('@');
        if (at <= 0) return v.length() > 24 ? v.substring(0, 22) + "…" : v;
        if (v.length() <= 28) return v;
        String local = v.substring(0, at);
        String domain = v.substring(at);
        if (local.length() > 14) local = local.substring(0, 13) + "…";
        return local + domain;
    }

    private String shortTime(String value) {
        if (value == null || value.isBlank()) return "—";
        String v = value.trim();
        try {
            if (v.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}(:\\d{2})?")) {
                String date = v.substring(0, 10);
                String time = v.substring(11, 16);
                String today = java.time.LocalDate.now().toString();
                if (date.equals(today)) return time;
                return v.substring(8, 10) + "/" + v.substring(5, 7) + " " + time;
            }
        } catch (Exception ignored) { }
        return v.length() > 20 ? v.substring(0, 20) : v;
    }

    private String extractLine(String content, String key) {
        if (content == null || key == null) return "";
        for (String line : content.split("\\r?\\n")) {
            int idx = line.indexOf(':');
            if (idx > 0 && line.substring(0, idx).trim().equalsIgnoreCase(key)) {
                return line.substring(idx + 1).trim();
            }
            int bar = line.indexOf('|');
            if (bar > 0 && line.substring(0, bar).trim().equalsIgnoreCase(key)) {
                return line.substring(bar + 1).trim();
            }
        }
        return "";
    }

    private String extractBody(String content) {
        if (content == null) return "";
        int idx = content.indexOf("\r\n\r\n");
        int skip = 4;
        if (idx < 0) {
            idx = content.indexOf("\n\n");
            skip = 2;
        }
        if (idx < 0) return "";
        return content.substring(idx + skip).trim();
    }

    private String cleanField(String value, int max) {
        if (value == null) return "";
        String cleaned = value.replace('|', '/').replace("\r\n", "\n").replace('\r', '\n').trim();
        if (max > 0 && cleaned.length() > max) cleaned = cleaned.substring(0, max);
        return cleaned;
    }

    // ══════════════════════════════════════════════════════════════════
    //  LOG / TOAST / ALERT / MODAL
    // ══════════════════════════════════════════════════════════════════
    private void log(String message) {
        if (message == null || activityLog == null) return;
        String line = "[" + LocalTime.now().withNano(0).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + "] " + message + "\n";
        if (Platform.isFxApplicationThread()) {
            activityLog.appendText(line);
            activityLog.setScrollTop(Double.MAX_VALUE);
        } else {
            Platform.runLater(() -> {
                activityLog.appendText(line);
                activityLog.setScrollTop(Double.MAX_VALUE);
            });
        }
    }

    private void showToast(String message, String color) {
        if (message == null || message.isBlank() || toastContainer == null) return;
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showToast(message, color));
            return;
        }
        FontAwesomeSolid icon = (AMBER.equals(color) || RED.equals(color))
                ? FontAwesomeSolid.EXCLAMATION_TRIANGLE : FontAwesomeSolid.CHECK_CIRCLE;
        FontIcon fi = new FontIcon(icon);
        fi.setIconSize(13);
        fi.setIconColor(Color.WHITE);

        Label label = new Label(message);
        label.setWrapText(true);
        label.setMaxWidth(380);
        label.setStyle("-fx-text-fill:white; -fx-font-size:11px; -fx-font-weight:bold;");

        HBox toast = new HBox(9, fi, label);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.setPadding(new Insets(10, 14, 10, 13));
        toast.setMaxWidth(440);
        toast.setStyle("-fx-background-color:" + color + "; -fx-background-radius:11;"
                + " -fx-effect: dropshadow(gaussian, rgba(2,6,18,0.35), 18, 0.15, 0, 6);");
        toast.setOpacity(0);
        toastContainer.getChildren().add(toast);

        FadeTransition in = new FadeTransition(Duration.millis(180), toast);
        in.setFromValue(0);
        in.setToValue(1);
        in.play();

        PauseTransition hold = new PauseTransition(Duration.seconds(4));
        hold.setOnFinished(e -> {
            FadeTransition out = new FadeTransition(Duration.millis(260), toast);
            out.setFromValue(1);
            out.setToValue(0);
            out.setOnFinished(done -> toastContainer.getChildren().remove(toast));
            out.play();
        });
        hold.play();
    }

    private void showAlert(String message) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showAlert(message));
            return;
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("MailFlow");
        alert.setHeaderText(null);
        alert.setContentText(message == null ? "" : message);
        alert.getDialogPane().setPrefWidth(540);
        alert.showAndWait();
    }

    private void showFormNote(Label note, String text) {
        if (note == null) return;
        boolean show = text != null && !text.isBlank();
        note.setText(show ? text : "");
        note.setVisible(show);
        note.setManaged(show);
    }

    private void closeModal() {
        if (modalLayer == null) return;
        settingsAvatarPreview = null;
        modalLayer.getChildren().clear();
        modalLayer.setVisible(false);
        modalLayer.setMouseTransparent(true);
    }
}
