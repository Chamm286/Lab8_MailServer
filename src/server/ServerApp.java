package server;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.*;

public class ServerApp extends Application {

    // ═══════════ PROFESSIONAL THEME ═══════════
    private static final String BG_MAIN     = "#F7F9FC";
    private static final String BG_WHITE    = "#FFFFFF";
    private static final String BG_HEADER   = "#0D47A1";
    private static final String BG_HEADER_2 = "#1565C0";
    private static final String BORDER      = "#E2E8F0";
    private static final String TEXT_DARK   = "#0F172A";
    private static final String TEXT_GRAY   = "#475569";
    private static final String TEXT_DIM    = "#94A3B8";
    private static final String GREEN       = "#10B981";
    private static final String GREEN_BG    = "#D1FAE5";
    private static final String RED         = "#EF4444";
    private static final String RED_BG      = "#FEE2E2";
    private static final String BLUE        = "#1976D2";
    private static final String BLUE_BG     = "#DBEAFE";
    private static final String TEAL        = "#0D9488";
    private static final String TEAL_BG     = "#CCFBF1";
    private static final String ORANGE      = "#F59E0B";
    private static final String ORANGE_BG   = "#FEF3C7";
    private static final String CYAN        = "#06B6D4";

    private ServerCore core;

    private int  totalRequests  = 0;
    private long startTime      = 0;

    private Label lbAccounts, lbClients, lbMails, lbUptimeCard;
    private Label lbServerStatus, lbServerIp, lbServerPort, lbUptime, lbDbStatus;
    private Button startBtn, stopBtn;
    private TextField portField;
    private Circle statusDot;

    private ObservableList<AccountRow> accountsData = FXCollections.observableArrayList();
    private ObservableList<LogRow>     logData      = FXCollections.observableArrayList();
    private ObservableList<MailDbRow>  mailDbData   = FXCollections.observableArrayList();
    private TableView<AccountRow> accountsTable;
    private TableView<LogRow>     logTable;
    private TableView<MailDbRow>  mailDbTable;
    private Label lbLogCount, lbMailDbCount;

    private Timeline refresher;

    /** Mốc thời gian gần nhất của từng loại sự kiện (chống ghi log trùng lặp). */
    private final Map<String, Long> lastEventAt = new HashMap<>();

    public static class AccountRow {
        private final int stt;
        private final String username, ip, status, lastLogin;
        private final boolean online;

        public AccountRow(int s, String u, String i, String st, String l, boolean on) {
            stt = s; username = u; ip = i; status = st; lastLogin = l; online = on;
        }
        public int getStt()          { return stt; }
        public String getUsername()  { return username; }
        public String getIp()        { return ip; }
        public String getStatus()    { return status; }
        public String getLastLogin() { return lastLogin; }
        public boolean isOnline()    { return online; }
    }

    public static class LogRow {
        private final String time, event, info;
        private final String eventColor;
        private final FontAwesomeSolid icon;

        public LogRow(String t, String e, String i, String color, FontAwesomeSolid ic) {
            time = t; event = e; info = i; eventColor = color; icon = ic;
        }
        public String getTime()          { return time; }
        public String getEvent()         { return event; }
        public String getInfo()          { return info; }
        public String getEventColor()    { return eventColor; }
        public FontAwesomeSolid getIcon(){ return icon; }
    }

    /** Một dòng = 1 bản ghi trong bảng emails (MySQL). */
    public static class MailDbRow {
        private final long id;
        private final String fromName, fromEmail, toEmail, subject, body, fromIp, sentAt, gmail;

        public MailDbRow(long id, String fromName, String from, String to, String subj, String body, String ip, String sent, String gmail) {
            this.id = id; this.fromName = fromName; this.fromEmail = from; this.toEmail = to; this.subject = subj;
            this.body = body; this.fromIp = ip; this.sentAt = sent; this.gmail = gmail;
        }
        public long getId() { return id; }
        public String getFromName() { return fromName; }
        public String getFromEmail() { return fromEmail; }
        public String getToEmail() { return toEmail; }
        public String getSubject() { return subject; }
        public String getBody() { return body; }
        public String getFromIp() { return fromIp; }
        public String getSentAt() { return sentAt; }
        public String getGmail() { return gmail; }
    }

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:" + BG_MAIN + ";");
        root.setTop(buildHeader());
        root.setCenter(buildMainContent());

        Scene scene = new Scene(root, 1400, 850);
        stage.setTitle("UDP MAIL SERVER");
        stage.setScene(scene);
        stage.show();
        doStart();

        refresher = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
            updateUptime();
            refreshAccounts();
            refreshMailDatabase();
        }));
        refresher.setCycleCount(Timeline.INDEFINITE);
        refresher.play();
    }

    // ==================================================
    //              HEADER
    // ==================================================
    private VBox buildHeader() {
        FontIcon logoIc = new FontIcon(FontAwesomeSolid.SERVER);
        logoIc.setIconSize(24);
        logoIc.setIconColor(Color.WHITE);
        StackPane logoBox = new StackPane(logoIc);
        logoBox.setStyle("-fx-background-color:rgba(255,255,255,0.18); -fx-background-radius:12; -fx-padding:12;");

        Label title = new Label("UDP MAIL SERVER");
        title.setStyle("-fx-text-fill:white; -fx-font-size:20px; -fx-font-weight:bold;");
        Label sub = new Label("Client ↔ Server · Gmail thật · MySQL localhost");
        sub.setStyle("-fx-text-fill:rgba(255,255,255,0.85); -fx-font-size:11px;");
        VBox titleBox = new VBox(2, title, sub);

        HBox titleSection = new HBox(14, logoBox, titleBox);
        titleSection.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusDot = new Circle(6);
        statusDot.setFill(Color.web("#FBBF24"));

        lbServerStatus = new Label("Chưa khởi động");
        lbServerStatus.setStyle("-fx-text-fill:white; -fx-font-size:13px; -fx-font-weight:bold;");

        HBox statusBox = new HBox(8, statusDot, lbServerStatus);
        statusBox.setAlignment(Pos.CENTER);
        statusBox.setPadding(new Insets(8, 18, 8, 18));
        statusBox.setStyle("-fx-background-color:rgba(255,255,255,0.18); -fx-background-radius:20;");

        lbDbStatus = new Label("● DB: …");
        lbDbStatus.setStyle("-fx-text-fill:white; -fx-font-size:11px; -fx-font-weight:bold; -fx-background-color:rgba(255,255,255,0.18); -fx-padding:8 14; -fx-background-radius:20;");

        HBox headerRow1 = new HBox(14, titleSection, spacer, lbDbStatus, statusBox);
        headerRow1.setAlignment(Pos.CENTER_LEFT);
        headerRow1.setPadding(new Insets(18, 28, 12, 28));

        Label ipLbl = new Label("IP Server:");
        ipLbl.setStyle("-fx-text-fill:rgba(255,255,255,0.75); -fx-font-size:12px;");
        lbServerIp = new Label("localhost");
        lbServerIp.setStyle("-fx-text-fill:white; -fx-font-size:13px; -fx-font-weight:bold;");

        Label portLbl = new Label("Cổng:");
        portLbl.setStyle("-fx-text-fill:rgba(255,255,255,0.75); -fx-font-size:12px;");
        lbServerPort = new Label("2023");
        lbServerPort.setStyle("-fx-text-fill:white; -fx-font-size:13px; -fx-font-weight:bold;");

        Label upLbl = new Label("Uptime:");
        upLbl.setStyle("-fx-text-fill:rgba(255,255,255,0.75); -fx-font-size:12px;");
        lbUptime = new Label("00:00:00");
        lbUptime.setStyle("-fx-text-fill:white; -fx-font-size:13px; -fx-font-weight:bold;");

        Region sp2 = new Region();
        HBox.setHgrow(sp2, Priority.ALWAYS);

        portField = new TextField("2023");
        portField.setPrefWidth(75);
        portField.setPrefHeight(34);
        portField.setStyle("-fx-background-color:rgba(255,255,255,0.18); -fx-text-fill:white; -fx-background-radius:8; -fx-border-color:rgba(255,255,255,0.3); -fx-border-radius:8; -fx-font-size:12px; -fx-padding:0 10;");

        startBtn = new Button();
        FontIcon playIc = new FontIcon(FontAwesomeSolid.PLAY);
        playIc.setIconSize(11);
        playIc.setIconColor(Color.web(GREEN));
        Label playLbl = new Label("  Khởi động");
        playLbl.setGraphic(playIc);
        playLbl.setStyle("-fx-text-fill:" + TEXT_DARK + "; -fx-font-weight:bold; -fx-font-size:12px;");
        startBtn.setGraphic(playLbl);
        startBtn.setPrefHeight(34);
        startBtn.setStyle("-fx-background-color:white; -fx-background-radius:8; -fx-cursor:hand; -fx-padding:0 16;");
        startBtn.setOnAction(e -> doStart());

        stopBtn = new Button();
        FontIcon stopIc = new FontIcon(FontAwesomeSolid.STOP_CIRCLE);
        stopIc.setIconSize(11);
        stopIc.setIconColor(Color.WHITE);
        Label stopLbl = new Label("  Dừng Server");
        stopLbl.setGraphic(stopIc);
        stopLbl.setStyle("-fx-text-fill:white; -fx-font-weight:bold; -fx-font-size:12px;");
        stopBtn.setGraphic(stopLbl);
        stopBtn.setPrefHeight(34);
        stopBtn.setDisable(true);
        stopBtn.setStyle("-fx-background-color:" + RED + "; -fx-background-radius:8; -fx-cursor:hand; -fx-padding:0 16;");
        stopBtn.setOnAction(e -> doStop());

        Label port2 = new Label("Cổng:");
        port2.setStyle("-fx-text-fill:rgba(255,255,255,0.75); -fx-font-size:12px;");

        HBox headerRow2 = new HBox(12,
                ipLbl, lbServerIp, divider(),
                portLbl, lbServerPort, divider(),
                upLbl, lbUptime,
                sp2,
                port2, portField, startBtn, stopBtn);
        headerRow2.setAlignment(Pos.CENTER_LEFT);
        headerRow2.setPadding(new Insets(0, 28, 18, 28));

        VBox header = new VBox(0, headerRow1, headerRow2);
        header.setStyle("-fx-background-color: linear-gradient(to right, " + BG_HEADER + ", " + BG_HEADER_2 + ");");
        return header;
    }

    private Region divider() {
        Region r = new Region();
        r.setPrefWidth(1);
        r.setPrefHeight(16);
        r.setStyle("-fx-background-color:rgba(255,255,255,0.3);");
        return r;
    }

    // ==================================================
    //              MAIN CONTENT
    // ==================================================
    private VBox buildMainContent() {
        HBox cards = new HBox(16,
                metricCard(FontAwesomeSolid.USERS, "Tài khoản", "Tổng số tài khoản", BLUE, BLUE_BG, "lbAccounts"),
                metricCard(FontAwesomeSolid.DESKTOP, "Client online", "Đang kết nối", GREEN, GREEN_BG, "lbClients"),
                metricCard(FontAwesomeSolid.ENVELOPE, "Email (DB)", "Bản ghi bảng emails", TEAL, TEAL_BG, "lbMails"),
                metricCard(FontAwesomeSolid.CLOCK, "Uptime", "Thời gian hoạt động", ORANGE, ORANGE_BG, "lbUptime2")
        );
        cards.setPadding(new Insets(0, 0, 16, 0));

        VBox usersSection = buildUsersSection();
        VBox mailDbSection = buildMailDbSection();
        VBox logSection = buildLogSection();

        VBox main = new VBox(16, cards, usersSection, mailDbSection, logSection);
        main.setPadding(new Insets(20, 28, 20, 28));
        main.setStyle("-fx-background-color:" + BG_MAIN + ";");
        VBox.setVgrow(usersSection, Priority.ALWAYS);
        VBox.setVgrow(mailDbSection, Priority.ALWAYS);
        VBox.setVgrow(logSection, Priority.ALWAYS);
        return main;
    }

    private VBox metricCard(FontAwesomeSolid ic, String title, String subtitle, String color, String bgColor, String refName) {
        FontIcon fi = new FontIcon(ic);
        fi.setIconSize(20);
        fi.setIconColor(Color.web(color));
        StackPane iconBox = new StackPane(fi);
        iconBox.setStyle("-fx-background-color:" + bgColor + "; -fx-background-radius:10; -fx-padding:12;");

        Label num = new Label("0");
        num.setStyle("-fx-text-fill:" + TEXT_DARK + "; -fx-font-size:26px; -fx-font-weight:bold;");

        switch (refName) {
            case "lbAccounts": lbAccounts = num; break;
            case "lbClients":  lbClients  = num; break;
            case "lbMails":    lbMails    = num; break;
            case "lbUptime2":  lbUptimeCard = num; break;
        }

        Label ttl = new Label(title);
        ttl.setStyle("-fx-text-fill:" + TEXT_DARK + "; -fx-font-size:13px; -fx-font-weight:bold;");
        Label sub = new Label(subtitle);
        sub.setStyle("-fx-text-fill:" + TEXT_DIM + "; -fx-font-size:11px;");

        VBox textBox = new VBox(2, num, ttl, sub);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        HBox row = new HBox(14, iconBox, textBox);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(row);
        card.setPadding(new Insets(18));
        card.setStyle("-fx-background-color:" + BG_WHITE + "; -fx-background-radius:12; -fx-border-color:" + BORDER + "; -fx-border-radius:12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.03), 8, 0, 0, 2);");
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    // ==================================================
    //              USERS TABLE
    // ==================================================
    private VBox buildUsersSection() {
        FontIcon ic = new FontIcon(FontAwesomeSolid.USERS);
        ic.setIconSize(16);
        ic.setIconColor(Color.web(BLUE));
        Label title = new Label("  Danh sách người dùng");
        title.setGraphic(ic);
        title.setStyle("-fx-text-fill:" + TEXT_DARK + "; -fx-font-size:15px; -fx-font-weight:bold;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button refresh = new Button();
        FontIcon refIc = new FontIcon(FontAwesomeSolid.SYNC_ALT);
        refIc.setIconSize(12);
        refIc.setIconColor(Color.web(TEXT_GRAY));
        Label refLbl = new Label("  Làm mới");
        refLbl.setGraphic(refIc);
        refLbl.setStyle("-fx-text-fill:" + TEXT_GRAY + "; -fx-font-size:12px;");
        refresh.setGraphic(refLbl);
        refresh.setStyle("-fx-background-color:transparent; -fx-cursor:hand; -fx-border-color:" + BORDER + "; -fx-border-radius:8; -fx-background-radius:8; -fx-padding:6 12;");
        refresh.setOnAction(e -> refreshAccounts());

        HBox header = new HBox(title, sp, refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 12, 0));

        accountsTable = new TableView<>();
        accountsTable.setStyle("-fx-background-color:" + BG_WHITE + "; -fx-font-size:13px; -fx-border-color:" + BORDER + "; -fx-border-radius:12; -fx-background-radius:12;");
        accountsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        accountsTable.setPrefHeight(190);
        accountsTable.setMinHeight(120);
        accountsTable.setPlaceholder(new Label("Chưa có người dùng nào"));
        accountsTable.setFixedCellSize(44);

        TableColumn<AccountRow, Integer> cSTT = new TableColumn<>("STT");
        cSTT.setCellValueFactory(new PropertyValueFactory<>("stt"));
        cSTT.setPrefWidth(70);
        cSTT.setStyle("-fx-alignment:CENTER;");

        TableColumn<AccountRow, String> cUser = new TableColumn<>("Tên đăng nhập");
        cUser.setCellValueFactory(new PropertyValueFactory<>("username"));
        cUser.setPrefWidth(200);

        TableColumn<AccountRow, String> cIp = new TableColumn<>("Địa chỉ IP");
        cIp.setCellValueFactory(new PropertyValueFactory<>("ip"));
        cIp.setPrefWidth(180);

        TableColumn<AccountRow, String> cStatus = new TableColumn<>("Trạng thái");
        cStatus.setCellValueFactory(new PropertyValueFactory<>("status"));
        cStatus.setPrefWidth(160);

        TableColumn<AccountRow, String> cTime = new TableColumn<>("Thời gian đăng nhập");
        cTime.setCellValueFactory(new PropertyValueFactory<>("lastLogin"));
        cTime.setPrefWidth(240);

        accountsTable.getColumns().addAll(cSTT, cUser, cIp, cStatus, cTime);
        accountsTable.setItems(accountsData);

        cStatus.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); setGraphic(null); }
                else {
                    boolean online = s.equals("Online");
                    Label dot = new Label("●");
                    dot.setStyle("-fx-text-fill:" + (online ? GREEN : TEXT_DIM) + "; -fx-font-size:13px;");
                    Label lbl = new Label(s);
                    lbl.setStyle("-fx-text-fill:" + (online ? GREEN : TEXT_GRAY) + "; -fx-font-size:12px; -fx-font-weight:bold;");
                    HBox h = new HBox(6, dot, lbl);
                    h.setAlignment(Pos.CENTER_LEFT);
                    h.setPadding(new Insets(4, 12, 4, 12));
                    h.setStyle(online
                        ? "-fx-background-color:" + GREEN_BG + "; -fx-background-radius:20;"
                        : "-fx-background-color:#F1F5F9; -fx-background-radius:20;");
                    setGraphic(h);
                    setText(null);
                }
            }
        });

        cUser.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); setGraphic(null); }
                else {
                    FontIcon fi = new FontIcon(FontAwesomeSolid.USER);
                    fi.setIconSize(12);
                    fi.setIconColor(Color.web(BLUE));
                    Label l = new Label(s);
                    l.setStyle("-fx-text-fill:" + TEXT_DARK + "; -fx-font-size:13px; -fx-font-weight:bold;");
                    HBox h = new HBox(8, fi, l);
                    h.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(h);
                    setText(null);
                }
            }
        });

        VBox card = new VBox(accountsTable);
        card.setStyle("-fx-background-color:" + BG_WHITE + "; -fx-background-radius:12; -fx-border-color:" + BORDER + "; -fx-border-radius:12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.03), 8, 0, 0, 2);");
        VBox.setVgrow(accountsTable, Priority.ALWAYS);
        VBox.setVgrow(card, Priority.ALWAYS);

        return new VBox(0, header, card);
    }

    // ==================================================
    //              DATABASE EMAILS (1 thư = 1 bản ghi)
    // ==================================================
    private VBox buildMailDbSection() {
        FontIcon ic = new FontIcon(FontAwesomeSolid.DATABASE);
        ic.setIconSize(16);
        ic.setIconColor(Color.web(TEAL));
        Label title = new Label("  Database emails (MySQL · lab8_mail.emails)");
        title.setGraphic(ic);
        title.setStyle("-fx-text-fill:" + TEXT_DARK + "; -fx-font-size:15px; -fx-font-weight:bold;");

        lbMailDbCount = new Label("0 bản ghi");
        lbMailDbCount.setStyle("-fx-text-fill:" + TEXT_DIM + "; -fx-font-size:11px; -fx-background-color:#F1F5F9; -fx-padding:4 10; -fx-background-radius:10;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button refresh = new Button();
        FontIcon refIc = new FontIcon(FontAwesomeSolid.SYNC_ALT);
        refIc.setIconSize(12);
        refIc.setIconColor(Color.web(TEXT_GRAY));
        Label refLbl = new Label("  Làm mới DB");
        refLbl.setGraphic(refIc);
        refLbl.setStyle("-fx-text-fill:" + TEXT_GRAY + "; -fx-font-size:12px;");
        refresh.setGraphic(refLbl);
        refresh.setStyle("-fx-background-color:transparent; -fx-cursor:hand; -fx-border-color:" + BORDER + "; -fx-border-radius:8; -fx-background-radius:8; -fx-padding:6 12;");
        refresh.setOnAction(e -> refreshMailDatabase());

        HBox header = new HBox(10, title, lbMailDbCount, sp, refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 12, 0));

        mailDbTable = new TableView<>();
        mailDbTable.setItems(mailDbData);
        mailDbTable.setPrefHeight(190);
        mailDbTable.setMinHeight(140);
        mailDbTable.setPlaceholder(new Label("Chưa có bản ghi — gửi thư từ Client sẽ thêm 1 dòng vào đây"));
        mailDbTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        mailDbTable.setStyle("-fx-background-color:" + BG_WHITE + "; -fx-font-size:12.5px; -fx-border-color:" + BORDER + "; -fx-border-radius:12; -fx-background-radius:12;");

        TableColumn<MailDbRow, Long> cId = new TableColumn<>("ID");
        cId.setCellValueFactory(new PropertyValueFactory<>("id"));
        cId.setPrefWidth(60);

        TableColumn<MailDbRow, String> cFrom = new TableColumn<>("Tên gửi");
        cFrom.setCellValueFactory(new PropertyValueFactory<>("fromName"));
        cFrom.setPrefWidth(130);

        TableColumn<MailDbRow, String> cFromMail = new TableColumn<>("From");
        cFromMail.setCellValueFactory(new PropertyValueFactory<>("fromEmail"));
        cFromMail.setPrefWidth(170);

        TableColumn<MailDbRow, String> cTo = new TableColumn<>("To");
        cTo.setCellValueFactory(new PropertyValueFactory<>("toEmail"));
        cTo.setPrefWidth(170);

        TableColumn<MailDbRow, String> cSubj = new TableColumn<>("Subject");
        cSubj.setCellValueFactory(new PropertyValueFactory<>("subject"));
        cSubj.setPrefWidth(170);

        TableColumn<MailDbRow, String> cBody = new TableColumn<>("Nội dung");
        cBody.setCellValueFactory(new PropertyValueFactory<>("body"));
        cBody.setPrefWidth(240);

        TableColumn<MailDbRow, String> cIp = new TableColumn<>("FromIP");
        cIp.setCellValueFactory(new PropertyValueFactory<>("fromIp"));
        cIp.setPrefWidth(100);

        TableColumn<MailDbRow, String> cTime = new TableColumn<>("Sent at");
        cTime.setCellValueFactory(new PropertyValueFactory<>("sentAt"));
        cTime.setPrefWidth(135);

        TableColumn<MailDbRow, String> cGmail = new TableColumn<>("Gmail");
        cGmail.setCellValueFactory(new PropertyValueFactory<>("gmail"));
        cGmail.setPrefWidth(65);

        mailDbTable.getColumns().addAll(cId, cFrom, cFromMail, cTo, cSubj, cBody, cIp, cTime, cGmail);

        VBox card = new VBox(mailDbTable);
        card.setStyle("-fx-background-color:" + BG_WHITE + "; -fx-background-radius:12; -fx-border-color:" + BORDER + "; -fx-border-radius:12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.03), 8, 0, 0, 2);");
        VBox.setVgrow(mailDbTable, Priority.ALWAYS);
        VBox.setVgrow(card, Priority.ALWAYS);
        return new VBox(0, header, card);
    }

    // ==================================================
    //              LOG TABLE
    // ==================================================
    private VBox buildLogSection() {
        FontIcon ic = new FontIcon(FontAwesomeSolid.HISTORY);
        ic.setIconSize(16);
        ic.setIconColor(Color.web(BLUE));
        Label title = new Label("  Nhật ký hoạt động");
        title.setGraphic(ic);
        title.setStyle("-fx-text-fill:" + TEXT_DARK + "; -fx-font-size:15px; -fx-font-weight:bold;");

        lbLogCount = new Label("0 sự kiện");
        lbLogCount.setStyle("-fx-text-fill:" + TEXT_DIM + "; -fx-font-size:11px; -fx-background-color:#F1F5F9; -fx-padding:4 10; -fx-background-radius:10;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button clearBtn = new Button();
        FontIcon clearIc = new FontIcon(FontAwesomeSolid.TRASH_ALT);
        clearIc.setIconSize(11);
        clearIc.setIconColor(Color.web(RED));
        Label clearLbl = new Label("  Xóa log");
        clearLbl.setGraphic(clearIc);
        clearLbl.setStyle("-fx-text-fill:" + RED + "; -fx-font-size:12px;");
        clearBtn.setGraphic(clearLbl);
        clearBtn.setStyle("-fx-background-color:transparent; -fx-cursor:hand; -fx-border-color:" + BORDER + "; -fx-border-radius:8; -fx-background-radius:8; -fx-padding:6 12;");
        clearBtn.setOnAction(e -> { logData.clear(); updateLogCount(); });

        HBox header = new HBox(10, title, lbLogCount, sp, clearBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 12, 0));

        logTable = new TableView<>();
        logTable.setStyle("-fx-background-color:" + BG_WHITE + "; -fx-font-size:12.5px; -fx-border-color:" + BORDER + "; -fx-border-radius:12; -fx-background-radius:12;");
        logTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        logTable.setPlaceholder(new Label("Chưa có hoạt động nào"));
        logTable.setFixedCellSize(38);
        logTable.setMinHeight(130);
        VBox.setVgrow(logTable, Priority.ALWAYS);

        TableColumn<LogRow, String> cTime = new TableColumn<>("Thời gian");
        cTime.setCellValueFactory(new PropertyValueFactory<>("time"));
        cTime.setPrefWidth(120);

        TableColumn<LogRow, String> cEvent = new TableColumn<>("Sự kiện");
        cEvent.setCellValueFactory(new PropertyValueFactory<>("event"));
        cEvent.setPrefWidth(160);

        TableColumn<LogRow, String> cInfo = new TableColumn<>("Thông tin");
        cInfo.setCellValueFactory(new PropertyValueFactory<>("info"));
        cInfo.setPrefWidth(700);

        logTable.getColumns().addAll(cTime, cEvent, cInfo);
        logTable.setItems(logData);

        cTime.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); setGraphic(null); }
                else {
                    Label l = new Label(s);
                    l.setStyle("-fx-text-fill:" + TEXT_GRAY + "; -fx-font-family:'Consolas'; -fx-font-size:12px;");
                    setGraphic(l);
                    setText(null);
                }
            }
        });

        cEvent.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); setGraphic(null); }
                else {
                    LogRow row = getTableView().getItems().get(getIndex());
                    String color = row.getEventColor();
                    FontAwesomeSolid ico = row.getIcon();

                    FontIcon fi = new FontIcon(ico != null ? ico : FontAwesomeSolid.INFO_CIRCLE);
                    fi.setIconSize(10);
                    fi.setIconColor(Color.web(color));

                    Label lbl = new Label(s);
                    lbl.setStyle("-fx-text-fill:" + color + "; -fx-font-size:11px; -fx-font-weight:bold;");

                    HBox h = new HBox(6, fi, lbl);
                    h.setAlignment(Pos.CENTER_LEFT);
                    h.setPadding(new Insets(3, 10, 3, 10));
                    h.setStyle("-fx-background-color:" + color + "1A; -fx-background-radius:20;");
                    setGraphic(h);
                    setText(null);
                }
            }
        });

        cInfo.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); setGraphic(null); }
                else {
                    Label l = new Label(s);
                    l.setStyle("-fx-text-fill:" + TEXT_DARK + "; -fx-font-size:12.5px;");
                    setGraphic(l);
                    setText(null);
                }
            }
        });

        VBox card = new VBox(logTable);
        card.setStyle("-fx-background-color:" + BG_WHITE + "; -fx-background-radius:12; -fx-border-color:" + BORDER + "; -fx-border-radius:12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.03), 8, 0, 0, 2);");
        VBox.setVgrow(card, Priority.ALWAYS);

        VBox section = new VBox(0, header, card);
        VBox.setVgrow(section, Priority.ALWAYS);
        return section;
    }

    private void updateLogCount() {
        if (lbLogCount != null) {
            lbLogCount.setText(logData.size() + " sự kiện");
        }
    }

    // ==================================================
    //              ACTIONS
    // ==================================================
    private void doStart() {
        int port;
        try { port = Integer.parseInt(portField.getText().trim()); }
        catch (Exception ex) { addLog("ERROR", "Cổng không hợp lệ", RED, FontAwesomeSolid.EXCLAMATION_TRIANGLE); return; }

        core = new ServerCore(this::log);
        core.start(port);
        ServerCore startingCore = core;
        new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) { return; }
                int activePort = startingCore.getBoundPort();
                if (activePort > 0) { Platform.runLater(() -> lbServerPort.setText(String.valueOf(activePort))); return; }
            }
        }, "server-port-status").start();
        startTime = System.currentTimeMillis();

        startBtn.setDisable(true);
        stopBtn.setDisable(false);
        portField.setDisable(true);

        statusDot.setFill(Color.web(GREEN));
        lbServerStatus.setText("Đang hoạt động");
        lbServerIp.setText(getLocalIP());
        lbServerPort.setText(String.valueOf(port));

        addLog("SERVER", "Server bắt đầu lắng nghe trên cổng " + port, GREEN, FontAwesomeSolid.PLAY);
        addLog("SERVER", "Địa chỉ IP: " + getLocalIP(), BLUE, FontAwesomeSolid.GLOBE);

        // Refresh ngay
        refreshAccounts();
    }

    private void doStop() {
        if (core != null) core.stop();
        startBtn.setDisable(false);
        stopBtn.setDisable(true);
        portField.setDisable(false);

        statusDot.setFill(Color.web("#FBBF24"));
        lbServerStatus.setText("Chưa khởi động");

        addLog("SERVER", "Server đã dừng", RED, FontAwesomeSolid.STOP_CIRCLE);
    }

    private String getLocalIP() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;

                String name = ni.getName().toLowerCase();
                String display = ni.getDisplayName() != null ? ni.getDisplayName().toLowerCase() : "";

                if (name.contains("vbox") || name.contains("vmnet")
                    || name.contains("bluetooth") || name.contains("virtual")
                    || name.contains("docker") || name.contains("hyper-v")
                    || display.contains("virtualbox") || display.contains("vmware")
                    || display.contains("bluetooth") || display.contains("hyper-v")
                    || display.contains("virtual")) continue;

                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip.contains(".") && !ip.startsWith("169.254") && !ip.startsWith("192.168.56.") && !ip.startsWith("127.")) {
                        return ip;
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  REFRESH ACCOUNTS — SỬA: đọc từ new_email.txt thay vì user.txt
    // ═══════════════════════════════════════════════════════════
    private void refreshAccounts() {
        Platform.runLater(() -> {
            accountsData.clear();
            File dir = new File(ServerCore.SERVER_DIR);
            File[] subs = dir.listFiles();
            if (subs == null) return;

            Map<String, ServerCore.ClientInfo> online = core != null ? core.getOnlineClients() : new HashMap<>();
            List<File> sorted = new ArrayList<>(Arrays.asList(subs));
            sorted.sort(Comparator.comparing(File::getName));

            int stt = 1;
            int totalMails = 0;
            for (File f : sorted) {
                if (!f.isDirectory()) continue;

                // ⭐ FIX: Chỉ check file new_email.txt
                if (!new File(f, "new_email.txt").exists()) continue;

                String username = f.getName();
                ServerCore.ClientInfo ci = online.get(username);
                String ip = ci != null ? ci.ip : "—";
                String status = ci != null ? "Online" : "Offline";
                String lastLogin = "—";

                // ⭐ FIX: Đọc Created từ new_email.txt
                try {
                    File info = new File(f, "new_email.txt");
                    if (info.exists()) {
                        String content = new String(java.nio.file.Files.readAllBytes(info.toPath()), "UTF-8");
                        for (String line : content.split("\n")) {
                            if (line.startsWith("Created")) {
                                int idx = line.indexOf(':');
                                if (idx > 0) lastLogin = line.substring(idx + 1).trim();
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}

                if (ci != null) {
                    SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
                    lastLogin = fmt.format(new Date(ci.lastSeen));
                }

                accountsData.add(new AccountRow(stt++, username, ip, status, lastLogin, ci != null));

                File inbox = new File(f, "inbox");
                File[] mails = inbox.listFiles((d, n) -> n.startsWith("email_"));
                if (mails != null) totalMails += mails.length;
            }

            int dbMails = totalMails;
            int dbAccounts = accountsData.size();
            if (core != null && core.getDatabase() != null && core.getDatabase().isReady()) {
                try {
                    dbMails = core.getDatabase().countEmails();
                    dbAccounts = core.getDatabase().countAccounts();
                    if (lbDbStatus != null) {
                        lbDbStatus.setText("● DB: lab8_mail · CONNECTED");
                        lbDbStatus.setStyle("-fx-text-fill:white; -fx-font-size:11px; -fx-font-weight:bold; -fx-background-color:rgba(16,185,129,0.55); -fx-padding:8 14; -fx-background-radius:20;");
                    }
                } catch (Exception ex) {
                    if (lbDbStatus != null) {
                        lbDbStatus.setText("● DB: ERROR");
                        lbDbStatus.setStyle("-fx-text-fill:white; -fx-font-size:11px; -fx-font-weight:bold; -fx-background-color:rgba(239,68,68,0.65); -fx-padding:8 14; -fx-background-radius:20;");
                    }
                }
            } else if (lbDbStatus != null) {
                lbDbStatus.setText("● DB: OFFLINE");
                lbDbStatus.setStyle("-fx-text-fill:white; -fx-font-size:11px; -fx-font-weight:bold; -fx-background-color:rgba(245,158,11,0.65); -fx-padding:8 14; -fx-background-radius:20;");
            }

            lbAccounts.setText(String.valueOf(Math.max(accountsData.size(), dbAccounts)));
            lbClients.setText(String.valueOf(online.size()));
            lbMails.setText(String.valueOf(dbMails));
        });
    }

    private void refreshMailDatabase() {
        Platform.runLater(() -> {
            mailDbData.clear();
            if (core == null || core.getDatabase() == null || !core.getDatabase().isReady()) {
                if (lbMailDbCount != null) lbMailDbCount.setText("DB offline");
                return;
            }
            try {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (MailDatabase.EmailRow row : core.getDatabase().listAllEmails(200)) {
                    String preview = row.body == null ? "" : row.body.replace("\n", " ");
                    if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
                    mailDbData.add(new MailDbRow(
                            row.id,
                            row.fromName == null || row.fromName.trim().isEmpty() ? "—" : row.fromName,
                            row.fromEmail,
                            row.toEmail,
                            row.subject,
                            preview,
                            row.fromIp == null ? "—" : row.fromIp,
                            row.sentAt != null ? fmt.format(row.sentAt) : "—",
                            row.gmailDelivered ? "Yes" : "No"));
                }
                if (lbMailDbCount != null) lbMailDbCount.setText(mailDbData.size() + " bản ghi (mỗi thư = 1 dòng)");
            } catch (Exception e) {
                if (lbMailDbCount != null) lbMailDbCount.setText("Lỗi: " + e.getMessage());
            }
        });
    }

    private void updateUptime() {
        if (startTime == 0) return;
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        long h = elapsed / 3600, m = (elapsed % 3600) / 60, s = elapsed % 60;
        String t = String.format("%02d:%02d:%02d", h, m, s);
        Platform.runLater(() -> {
            lbUptime.setText(t);
            if (lbUptimeCard != null) lbUptimeCard.setText(t);
        });
    }

    private void addLog(String event, String info, String color, FontAwesomeSolid icon) {
        Platform.runLater(() -> {
            String t = java.time.LocalTime.now().withNano(0).toString();
            if (t.length() > 8) t = t.substring(0, 8);
            logData.add(new LogRow(t, event, info, color, icon));
            if (logData.size() > 100) logData.remove(0);
            updateLogCount();
            if (logTable != null && !logData.isEmpty()) {
                logTable.scrollTo(logData.size() - 1);
            }
        });
    }

    private void log(String s) {
        if (s.contains("[RECV]") && s.contains("PING")) return;
        if (s.contains("[RECV]") && s.contains("MAILBOX|")) return; // làm mới hộp thư tự động: không ghi log
        if (s.startsWith("[DIR]")) return;

        String event = "SERVER";
        String info = s;
        String color = BLUE;
        FontAwesomeSolid icon = FontAwesomeSolid.INFO_CIRCLE;

        if (s.contains("[RECV]")) {
            totalRequests++;
            String reqPart = s.substring(s.indexOf("|") + 1).trim();
            info = reqPart;

            // Chống nhiễu log: client tự làm mới hộp thư / đọc lại mail đang mở mỗi 3 giây
            if ((reqPart.startsWith("LOGIN|") || reqPart.startsWith("SENT|") || reqPart.startsWith("GET|"))
                    && throttle("REQ|" + reqPart, 30_000)) return;

            if (reqPart.startsWith("LOGIN|")) {
                event = "LOGIN";
                String[] p = reqPart.split("\\|");
                if (p.length >= 2) info = p[1] + " đăng nhập";
                color = GREEN;
                icon = FontAwesomeSolid.SIGN_IN_ALT;
            } else if (reqPart.startsWith("REGISTER|")) {
                event = "REGISTER";
                String[] p = reqPart.split("\\|");
                if (p.length >= 2) info = "Tài khoản mới: " + p[1];
                color = TEAL;
                icon = FontAwesomeSolid.USER_PLUS;
                // ⭐ Refresh ngay khi có đăng ký mới
                Platform.runLater(this::refreshAccounts);
            } else if (reqPart.startsWith("SEND|")) {
                event = "SEND";
                String[] p = reqPart.split("\\|");
                if (p.length >= 4) info = p[1] + " → " + p[3] + " (+1 bản ghi DB)";
                color = ORANGE;
                icon = FontAwesomeSolid.PAPER_PLANE;
                Platform.runLater(this::refreshMailDatabase);
            } else if (reqPart.startsWith("SENT|")) {
                event = "SENT";
                info = "Xem hộp thư đã gửi";
                color = BLUE;
                icon = FontAwesomeSolid.PAPER_PLANE;
            } else if (reqPart.startsWith("LIST")) {
                event = "LIST";
                info = "Lấy danh sách người dùng";
                color = CYAN;
                icon = FontAwesomeSolid.LIST;
            } else if (reqPart.startsWith("DELETE|")) {
                event = "DELETE";
                info = "Xóa mail";
                color = RED;
                icon = FontAwesomeSolid.TRASH_ALT;
            } else if (reqPart.startsWith("STAR|")) {
                event = "STAR";
                info = "Đánh dấu mail";
                color = ORANGE;
                icon = FontAwesomeSolid.STAR;
            } else if (reqPart.startsWith("GET|")) {
                event = "READ";
                info = "Đọc nội dung mail";
                color = BLUE;
                icon = FontAwesomeSolid.ENVELOPE_OPEN;
            } else if (reqPart.startsWith("TRASH|")) {
                event = "TRASH";
                info = "Xem thùng rác";
                color = RED;
                icon = FontAwesomeSolid.TRASH;
            } else if (reqPart.startsWith("STARRED|")) {
                event = "STARRED";
                info = "Xem mail đánh dấu";
                color = ORANGE;
                icon = FontAwesomeSolid.STAR;
            }
        } else if (s.startsWith("[OK]")) {
            event = "SERVER";
            info = s.substring(5).trim();
            color = GREEN;
            icon = FontAwesomeSolid.CHECK_CIRCLE;
        } else if (s.startsWith("[STOP]")) {
            event = "SERVER";
            info = s.substring(6).trim();
            color = RED;
            icon = FontAwesomeSolid.STOP_CIRCLE;
        } else if (s.startsWith("[ERR]")) {
            event = "ERROR";
            info = s.substring(5).trim();
            color = RED;
            icon = FontAwesomeSolid.EXCLAMATION_TRIANGLE;
        } else if (s.startsWith("[SEND]")) {
            event = "DELIVERED";
            info = s.substring(6).trim();
            color = GREEN;
            icon = FontAwesomeSolid.CHECK;
        } else if (s.startsWith("[DB]")) {
            event = "DATABASE";
            info = s.substring(4).trim();
            color = TEAL;
            icon = FontAwesomeSolid.DATABASE;
            Platform.runLater(this::refreshMailDatabase);
        } else {
            return;
        }

        // Chống nhiễu: client cũ tự làm mới hộp thư bằng LOGIN mỗi 3 giây
        if ("LOGIN".equals(event) && throttle("LOGIN|" + info, 30_000)) return;

        addLog(event, info, color, icon);
    }

    private boolean throttle(String key, long windowMs) {
        long now = System.currentTimeMillis();
        Long last = lastEventAt.get(key);
        if (last != null && now - last < windowMs) return true;
        lastEventAt.put(key, now);
        return false;
    }

    public static void main(String[] args) { launch(args); }
}
