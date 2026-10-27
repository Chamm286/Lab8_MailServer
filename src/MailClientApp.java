import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.stage.Popup;
import javafx.animation.PauseTransition;
import javafx.animation.FadeTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Interpolator;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

public class MailClientApp extends Application {
    private TextField fromField, toField, subjectField, ccField, bccField;
    private HBox ccRow, bccRow;
    private Button formatBold, formatItalic, formatUnderline;
    private PasswordField passwordField;
    private TextArea bodyArea;
    private VBox messageList;
    private Label endpointLabel, connectionStatus, chatTitle;
    private Button sendButton, refreshButton;
private Stage primaryStage;
    private volatile String serverHost = "127.0.0.1";
    private volatile int serverPort = 2525;

    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) {
        Label brand = new Label("MAILFLOW"); brand.setFont(Font.font("Segoe UI", FontWeight.BOLD, 21)); brand.setTextFill(Color.web("#172b4d"));
        Label brandSub = new Label("SECURE MAIL WORKSPACE"); brandSub.getStyleClass().add("eyebrow");
        VBox logo = new VBox(3, brand, brandSub);
        Button compose = new Button("Compose message", icon(FontAwesomeSolid.PEN, "#ffffff", 14)); compose.getStyleClass().add("compose-button"); compose.setMaxWidth(Double.MAX_VALUE); compose.setOnAction(e -> { toField.requestFocus(); bodyArea.clear(); });
        Label folders = new Label("WORKSPACE"); folders.getStyleClass().add("eyebrow");
        Button inbox = navButton("Inbox", FontAwesomeSolid.INBOX, true); inbox.setOnAction(e -> checkInbox());
        Button sent = navButton("Sent", FontAwesomeSolid.PAPER_PLANE, false); sent.setOnAction(e -> appendSystem("Sent messages are shown in this conversation after sending."));
        Button drafts = navButton("Drafts", FontAwesomeSolid.FILE_ALT, false); drafts.setOnAction(e -> appendSystem("Drafts stay in the composer until you send them."));
        Button trash = navButton("Trash", FontAwesomeSolid.TRASH_ALT, false); trash.setOnAction(e -> appendSystem("Deleted messages are kept in Gmail Trash."));

        Label accountTitle = new Label("MAIL ACCOUNT"); accountTitle.getStyleClass().add("eyebrow");
        fromField = new TextField(); fromField.setPromptText("you@gmail.com");
        passwordField = new PasswordField(); passwordField.setPromptText("Google App Password");
        Label accountHelp = new Label("Use a Google App Password. It stays in this window and is sent only to your mail server."); accountHelp.getStyleClass().add("muted-small"); accountHelp.setWrapText(true);
        VBox accountCard = new VBox(9, accountTitle, fromField, passwordField, accountHelp); accountCard.getStyleClass().add("account-card");
        Label autoTitle = new Label("SERVER CONNECTION"); autoTitle.getStyleClass().add("eyebrow");
        endpointLabel = new Label("Searching for MailFlow server…"); endpointLabel.getStyleClass().add("endpoint"); endpointLabel.setWrapText(true);
        Button discover = new Button("Auto detect", icon(FontAwesomeSolid.SYNC_ALT, "#52647a", 12)); discover.getStyleClass().add("small-button"); discover.setOnAction(e -> discoverServer());
        HBox discoveryRow = new HBox(7, icon(FontAwesomeSolid.WIFI, "#078b91", 12), endpointLabel); discoveryRow.setAlignment(Pos.TOP_LEFT);
        VBox connectionCard = new VBox(10, autoTitle, discoveryRow, discover); connectionCard.getStyleClass().add("connection-card");
        VBox sidebar = new VBox(24, logo, compose, folders, inbox, sent, drafts, trash, spacer(), accountCard, connectionCard); sidebar.setPadding(new Insets(24, 17, 20, 17)); sidebar.setPrefWidth(260); sidebar.setMinWidth(240); sidebar.getStyleClass().add("sidebar");

        connectionStatus = new Label("CONNECTING"); connectionStatus.getStyleClass().add("status-pill");
        Button check = new Button("Check inbox", icon(FontAwesomeSolid.SYNC_ALT, "#087f83", 12)); check.getStyleClass().add("small-button"); check.setOnAction(e -> checkInbox()); refreshButton = check;
        HBox top = new HBox(12, new Label("Mail"), spacer(), connectionStatus, check); top.setAlignment(Pos.CENTER_RIGHT); top.setPadding(new Insets(15, 22, 15, 22)); top.getStyleClass().add("topbar");

        Label avatar = new Label("✉"); avatar.getStyleClass().add("chat-avatar");
        chatTitle = new Label("Your mail conversation"); chatTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16)); chatTitle.setTextFill(Color.web("#172b4d"));
        Label chatHint = new Label("Send a real email or check your Gmail inbox"); chatHint.getStyleClass().add("muted-small");
        VBox chatInfo = new VBox(3, chatTitle, chatHint);
        HBox conversationHeader = new HBox(12, avatar, chatInfo, spacer(), icon(FontAwesomeSolid.ELLIPSIS_H, "#718096", 16)); conversationHeader.setAlignment(Pos.CENTER_LEFT); conversationHeader.setPadding(new Insets(17, 22, 17, 22)); conversationHeader.getStyleClass().add("conversation-header");

        messageList = new VBox(14); messageList.setPadding(new Insets(22, 5, 22, 5)); messageList.setFillWidth(true);
        appendSystem("Welcome to MailFlow. Connect to the server, enter your Gmail App Password, and start a conversation.");
        ScrollPane messages = new ScrollPane(messageList); messages.setFitToWidth(true); messages.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); messages.getStyleClass().add("messages-scroll"); VBox.setVgrow(messages, Priority.ALWAYS);

        Label composeTitle = new Label("New message"); composeTitle.getStyleClass().add("compose-title");
        ToggleButton ccToggle = new ToggleButton("Cc/Bcc"); ccToggle.getStyleClass().add("cc-toggle");
        HBox composeHeader = new HBox(8, composeTitle, spacer(), ccToggle); composeHeader.setAlignment(Pos.CENTER_LEFT);

        toField = new TextField(); toField.setPromptText("recipient@example.com"); toField.getStyleClass().add("recipient-field"); HBox.setHgrow(toField, Priority.ALWAYS);
        ccField = new TextField(); ccField.setPromptText("cc@example.com"); ccField.getStyleClass().add("recipient-field"); HBox.setHgrow(ccField, Priority.ALWAYS);
        bccField = new TextField(); bccField.setPromptText("bcc@example.com"); bccField.getStyleClass().add("recipient-field"); HBox.setHgrow(bccField, Priority.ALWAYS);
        subjectField = new TextField(); subjectField.setPromptText("Subject"); subjectField.getStyleClass().add("subject-field");

        HBox toRow = composeRow("To", toField);
        ccRow = composeRow("Cc", ccField); ccRow.setVisible(false); ccRow.setManaged(false);
        bccRow = composeRow("Bcc", bccField); bccRow.setVisible(false); bccRow.setManaged(false);
        HBox subjectRow = composeRow("Subject", subjectField);
        ccToggle.selectedProperty().addListener((o, was, now) -> { ccRow.setVisible(now); ccRow.setManaged(now); bccRow.setVisible(now); bccRow.setManaged(now); });

        formatBold = formatButton("B", "Bold", FontWeight.BOLD);
        formatItalic = formatButton("I", "Italic", FontWeight.NORMAL);
        formatUnderline = formatButton("U", "Underline", FontWeight.NORMAL);
        formatItalic.setStyle("-fx-font-style: italic;");
        formatUnderline.setStyle("-fx-underline: true;");
        formatBold.setOnAction(e -> wrapSelection("*", "*"));
        formatItalic.setOnAction(e -> wrapSelection("_", "_"));
        formatUnderline.setOnAction(e -> wrapSelection("__", "__"));
        Button attach = new Button("", icon(FontAwesomeSolid.PAPERCLIP, "#718096", 14)); attach.getStyleClass().add("toolbar-button"); attach.setTooltip(new Tooltip("Attach a file"));
        attach.setOnAction(e -> appendSystem("Attachments are not available in this SMTP implementation yet."));
        Region tbSep = new Region(); tbSep.getStyleClass().add("toolbar-separator");
        HBox toolbar = new HBox(4, formatBold, formatItalic, formatUnderline, tbSep, attach); toolbar.setAlignment(Pos.CENTER_LEFT); toolbar.getStyleClass().add("format-toolbar");

        bodyArea = new TextArea(); bodyArea.setPromptText("Write your message…"); bodyArea.setWrapText(true); bodyArea.setPrefRowCount(6); bodyArea.getStyleClass().add("message-editor");
        VBox.setVgrow(bodyArea, Priority.ALWAYS);

        Button attachHint = new Button("", icon(FontAwesomeSolid.PAPERCLIP, "#718096", 15)); attachHint.setVisible(false); attachHint.setManaged(false);
        sendButton = new Button("Send", icon(FontAwesomeSolid.PAPER_PLANE, "#ffffff", 13)); sendButton.getStyleClass().add("send-button"); sendButton.setOnAction(e -> sendMail());
        Label composeHint = new Label("Enter a subject and message to send"); composeHint.getStyleClass().add("muted-small");
        HBox composerActions = new HBox(10, composeHint, spacer(), sendButton); composerActions.setAlignment(Pos.CENTER_LEFT);

        VBox composer = new VBox(2, composeHeader, toRow, ccRow, bccRow, subjectRow, toolbar, bodyArea, composerActions);
        composer.setPadding(new Insets(14, 20, 18, 20)); composer.getStyleClass().add("composer");
        VBox conversation = new VBox(conversationHeader, messages, composer); conversation.getStyleClass().add("conversation"); VBox.setVgrow(conversation, Priority.ALWAYS);
        BorderPane main = new BorderPane(conversation, top, null, null, null); main.getStyleClass().add("main-pane");
        BorderPane root = new BorderPane(main, null, null, null, sidebar); root.getStyleClass().add("app-root");
        Scene scene = new Scene(root, 1280, 850); scene.getStylesheets().add(getClass().getResource("mailflow.css").toExternalForm());
        stage.setTitle("MailFlow — Conversations"); stage.setMinWidth(960); stage.setMinHeight(700); stage.setScene(scene); this.primaryStage = stage; stage.show();
        discoverServer();
    }

    private void sendMail() {
        String from = fromField.getText().trim(), pass = passwordField.getText().trim(), to = toField.getText().trim(), subject = subjectField.getText().trim(), body = bodyArea.getText().trim();
        if (from.isEmpty() || pass.isEmpty() || to.isEmpty() || subject.isEmpty() || body.isEmpty()) { appendSystem("Complete your Gmail address, App Password, recipient, subject, and message first."); return; }
        if (!to.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) { appendSystem("The recipient address does not look valid."); return; }
        setBusy(true); chatTitle.setText(to); appendBubble(body, "You  •  " + subject, true);
        Task<String> task = new Task<String>() { @Override protected String call() throws Exception { return exchange("SEND|" + enc(from) + "|" + enc(pass) + "|" + enc(to) + "|" + enc(subject) + "|" + enc(body), true); } };
        task.setOnSucceeded(e -> { setBusy(false); if ("OK".equals(task.getValue())) { appendSuccessNotification("Email sent successfully", "Your message was delivered to Gmail SMTP and saved to Sent."); showToast(stage, true, "Gửi thành công"); } else { appendErrorNotification("Send failed", "Gmail rejected the message. Check the App Password, SMTP access, and server activity log."); showToast(stage, false, "Gửi thất bại"); } });
        task.setOnFailed(e -> { setBusy(false); appendSystem("Could not connect to " + serverHost + ":" + serverPort + " — " + task.getException().getMessage()); }); new Thread(task, "mailflow-send").start();
    }

    private void checkInbox() {
        String email = fromField.getText().trim(), pass = passwordField.getText().trim();
        if (email.isEmpty() || pass.isEmpty()) { appendSystem("Enter your Gmail address and App Password in the left panel first."); return; }
        setBusy(true); appendSystem("Checking the Gmail inbox…");
        Task<String> task = new Task<String>() { @Override protected String call() throws Exception { return exchange("RECV|" + enc(email) + "|" + enc(pass), false); } };
        task.setOnSucceeded(e -> { setBusy(false); String result = task.getValue(); if (result == null || result.trim().isEmpty()) appendSystem("No message was returned. Check that POP is enabled in Gmail."); else appendBubble(formatMail(result), "Gmail inbox", false); });
        task.setOnFailed(e -> { setBusy(false); appendSystem("Inbox check failed: " + task.getException().getMessage()); }); new Thread(task, "mailflow-inbox").start();
    }

    private void discoverServer() {
        connectionStatus.setText("SEARCHING…"); endpointLabel.setText("Auto-detecting server and port…");
        Task<Boolean> task = new Task<Boolean>() { @Override protected Boolean call() { return findServer(); } };
        task.setOnSucceeded(e -> { boolean found = task.getValue(); if (found) { connectionStatus.setText("CONNECTED"); endpointLabel.setText(serverHost + ":" + serverPort + "  •  auto detected"); } else { connectionStatus.setText("OFFLINE"); endpointLabel.setText("No server found. Start MailFlow Server and retry."); } });
        task.setOnFailed(e -> { connectionStatus.setText("OFFLINE"); endpointLabel.setText("Discovery failed. Start the server, then retry."); }); new Thread(task, "mailflow-discovery-client").start();
    }

    private boolean findServer() {
        try {
            Properties p = new Properties();
            if (Files.exists(Paths.get("mailflow-server.properties"))) { try (InputStream in = Files.newInputStream(Paths.get("mailflow-server.properties"))) { p.load(in); } String host = p.getProperty("host", "127.0.0.1"); int port = Integer.parseInt(p.getProperty("port", "2525")); if (canConnect(host, port)) { serverHost = host; serverPort = port; return true; } }
        } catch (Exception ignored) { }
        try (DatagramSocket udp = new DatagramSocket()) {
            udp.setBroadcast(true); udp.setSoTimeout(1300); byte[] query = "MAILFLOW_DISCOVER".getBytes(StandardCharsets.UTF_8);
            udp.send(new DatagramPacket(query, query.length, InetAddress.getByName("255.255.255.255"), 25250));
            byte[] bytes = new byte[128]; DatagramPacket response = new DatagramPacket(bytes, bytes.length); udp.receive(response);
            String[] parts = new String(response.getData(), response.getOffset(), response.getLength(), StandardCharsets.UTF_8).split("\\|");
            if (parts.length == 2 && parts[0].equals("MAILFLOW")) { serverHost = response.getAddress().getHostAddress(); serverPort = Integer.parseInt(parts[1]); return canConnect(serverHost, serverPort); }
        } catch (Exception ignored) { }
        for (int p = 2525; p < 2545; p++) if (canConnect("127.0.0.1", p)) { serverHost = "127.0.0.1"; serverPort = p; return true; }
        return false;
    }

    private boolean canConnect(String host, int port) { try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(host, port), 450); return true; } catch (IOException e) { return false; } }

    private String exchange(String command, boolean oneLine) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(serverHost, serverPort), 5000); socket.setSoTimeout(90000);
            PrintWriter w = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)); w.println(command);
            if (oneLine) return r.readLine(); StringBuilder answer = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) { if (answer.length() > 40000) { answer.append("\n[message truncated]"); break; } answer.append(line).append('\n'); }
            return answer.toString().trim();
        }
    }

    private static String enc(String text) { return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private void setBusy(boolean busy) { sendButton.setDisable(busy); refreshButton.setDisable(busy); connectionStatus.setText(busy ? "WORKING…" : "CONNECTED"); }
    private void appendSystem(String text) { Label l = new Label(text); l.getStyleClass().add("system-message"); l.setWrapText(true); l.setMaxWidth(560); HBox row = new HBox(l); row.setAlignment(Pos.CENTER); messageList.getChildren().add(row); }

private void appendSuccessNotification(String title, String detail) {
    Label icon = new Label("✓"); icon.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22)); icon.setTextFill(Color.web("#2bb740"));
    Label titleLabel = new Label(title); titleLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14)); titleLabel.setTextFill(Color.web("#172b4d"));
    Label detailLabel = new Label(detail); detailLabel.getStyleClass().add("muted-small"); detailLabel.setWrapText(true); detailLabel.setMaxWidth(420); detailLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
    VBox card = new VBox(6, icon, titleLabel, detailLabel); card.setAlignment(Pos.CENTER); card.setPadding(new Insets(16, 24, 16, 24)); card.setMaxWidth(460);
    card.setStyle("-fx-background-color: rgba(50,215,75,0.10); -fx-background-radius: 14; -fx-border-color: rgba(50,215,75,0.35); -fx-border-radius: 14; -fx-border-width: 1;");
    HBox row = new HBox(card); row.setAlignment(Pos.CENTER); row.setPadding(new Insets(6, 0, 6, 0)); messageList.getChildren().add(row);
}

private void appendErrorNotification(String title, String detail) {
    Label icon = new Label("✕"); icon.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22)); icon.setTextFill(Color.web("#ff3b30"));
    Label titleLabel = new Label(title); titleLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14)); titleLabel.setTextFill(Color.web("#172b4d"));
    Label detailLabel = new Label(detail); detailLabel.getStyleClass().add("muted-small"); detailLabel.setWrapText(true); detailLabel.setMaxWidth(420); detailLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
    VBox card = new VBox(6, icon, titleLabel, detailLabel); card.setAlignment(Pos.CENTER); card.setPadding(new Insets(16, 24, 16, 24)); card.setMaxWidth(460);
    card.setStyle("-fx-background-color: rgba(255,59,48,0.10); -fx-background-radius: 14; -fx-border-color: rgba(255,59,48,0.35); -fx-border-radius: 14; -fx-border-width: 1;");
    HBox row = new HBox(card); row.setAlignment(Pos.CENTER); row.setPadding(new Insets(6, 0, 6, 0)); messageList.getChildren().add(row);
}
    private void appendBubble(String text, String title, boolean outgoing) {
        Label heading = new Label(title); heading.getStyleClass().add("bubble-heading");
        Label body = new Label(text); body.getStyleClass().add("bubble-text"); body.setWrapText(true); body.setMaxWidth(570);
        Label time = new Label(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))); time.getStyleClass().add("bubble-time");
        VBox bubble = new VBox(6, heading, body, time); bubble.getStyleClass().add(outgoing ? "bubble-outgoing" : "bubble-incoming"); bubble.setMaxWidth(620);
        HBox row = new HBox(bubble); row.setAlignment(outgoing ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT); row.setPadding(new Insets(0, 12, 0, 12)); messageList.getChildren().add(row);
    }
    private HBox composeRow(String name, TextField field) {
    Label label = new Label(name); label.getStyleClass().add("compose-label"); label.setMinWidth(58); label.setPrefWidth(58);
    HBox row = new HBox(6, label, field); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("compose-row"); return row;
}

private Button formatButton(String text, String tooltip, FontWeight weight) {
    Button b = new Button(text); b.getStyleClass().add("toolbar-button"); b.setTooltip(new Tooltip(tooltip));
    b.setFont(Font.font("Segoe UI", weight, 13)); return b;
}

private void wrapSelection(String prefix, String suffix) {
    IndexRange range = bodyArea.getSelection();
    String text = bodyArea.getText();
    if (range.getLength() == 0) { bodyArea.insertText(bodyArea.getCaretPosition(), prefix + suffix); bodyArea.positionCaret(bodyArea.getCaretPosition() - suffix.length()); return; }
    String selected = text.substring(range.getStart(), range.getEnd());
    bodyArea.replaceText(range.getStart(), range.getEnd(), prefix + selected + suffix);
    bodyArea.selectRange(range.getStart() + prefix.length(), range.getEnd() + prefix.length());
    bodyArea.requestFocus();
}

private void showToast(Stage owner, boolean success, String text) {
    Popup popup = new Popup();
    Label icon = new Label(success ? "⟳" : "✕"); icon.setFont(Font.font("Segoe UI", FontWeight.BOLD, 20)); icon.setTextFill(Color.web(success ? "#2bb740" : "#ff3b30"));
    Label label = new Label(text); label.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13)); label.setTextFill(Color.web("#172b4d"));
    HBox box = new HBox(10, icon, label); box.setAlignment(Pos.CENTER_LEFT); box.setPadding(new Insets(12, 18, 12, 18));
    box.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.18), 16, 0, 0, 4);");
    popup.getContent().add(box); popup.setAutoHide(true);
    popup.show(owner);
    popup.setX(owner.getX() + owner.getWidth() - 260); popup.setY(owner.getY() + 20);
    if (success) {
        RotateTransition spin = new RotateTransition(Duration.millis(600), icon);
        spin.setByAngle(360); spin.setCycleCount(1); spin.setInterpolator(Interpolator.LINEAR); spin.play();
        PauseTransition pt = new PauseTransition(Duration.millis(600));
        pt.setOnFinished(e -> {
            icon.setText("✔");
            ScaleTransition pop = new ScaleTransition(Duration.millis(260), icon);
            pop.setFromX(0.4); pop.setFromY(0.4); pop.setToX(1.15); pop.setToY(1.15); pop.setCycleCount(2);
            pop.setAutoReverse(true); pop.play();
        });
        pt.play();
    }
    FadeTransition ft = new FadeTransition(Duration.millis(300), box); ft.setFromValue(0); ft.setToValue(1); ft.play();
    PauseTransition hide = new PauseTransition(Duration.seconds(2.6)); hide.setOnFinished(e -> { FadeTransition f = new FadeTransition(Duration.millis(300), box); f.setFromValue(1); f.setToValue(0); f.setOnFinished(ev -> popup.hide()); f.play(); }); hide.play();
}

private String formatMail(String raw) {
        String from = header(raw, "From"), subject = header(raw, "Subject"); int end = raw.indexOf("\n\n"); String body = end >= 0 ? raw.substring(end + 2) : raw;
        body = body.replaceAll("(?s)Content-Type:.*?\\n\\n", "").replaceAll("(?m)^--[-A-Za-z0-9_=]+.*$", "").replaceAll("(?m)^\\.$", "").trim();
        if (body.length() > 6000) body = body.substring(0, 6000) + "…";
        return (from.isEmpty() ? "" : "From: " + from + "\n") + (subject.isEmpty() ? "" : "Subject: " + subject + "\n\n") + body;
    }
    private String header(String raw, String name) { for (String line : raw.split("\\r?\\n")) if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) return line.substring(name.length() + 1).trim(); return ""; }
    private static Button navButton(String text, FontAwesomeSolid glyph, boolean active) { Button b = new Button(text, icon(glyph, active ? "#078b91" : "#718096", 14)); b.setMaxWidth(Double.MAX_VALUE); b.setAlignment(Pos.CENTER_LEFT); b.getStyleClass().add(active ? "nav-active" : "nav-button"); return b; }
    private static FontIcon icon(FontAwesomeSolid glyph, String color, int size) { FontIcon i = new FontIcon(glyph); i.setIconColor(Color.web(color)); i.setIconSize(size); return i; }
    private static Region spacer() { Region r = new Region(); VBox.setVgrow(r, Priority.ALWAYS); HBox.setHgrow(r, Priority.ALWAYS); return r; }
}
