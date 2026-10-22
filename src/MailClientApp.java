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
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

public class MailClientApp extends Application {
    private TextField fromField, toField, subjectField;
    private PasswordField passwordField;
    private TextArea bodyArea;
    private VBox messageList;
    private Label endpointLabel, connectionStatus, chatTitle;
    private Button sendButton, refreshButton;
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
        VBox sidebar = new VBox(24, logo, compose, folders, inbox, sent, drafts, spacer(), accountCard, connectionCard); sidebar.setPadding(new Insets(24, 17, 20, 17)); sidebar.setPrefWidth(260); sidebar.setMinWidth(240); sidebar.getStyleClass().add("sidebar");

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

        toField = new TextField(); toField.setPromptText("To: recipient@example.com"); toField.getStyleClass().add("recipient-field");
        subjectField = new TextField(); subjectField.setPromptText("Subject"); subjectField.getStyleClass().add("subject-field");
        bodyArea = new TextArea(); bodyArea.setPromptText("Write a message…"); bodyArea.setWrapText(true); bodyArea.setPrefRowCount(3); bodyArea.getStyleClass().add("message-editor");
        Button attach = new Button("", icon(FontAwesomeSolid.PAPERCLIP, "#718096", 15)); attach.getStyleClass().add("icon-button"); attach.setTooltip(new Tooltip("Attachments are not available in this SMTP implementation yet"));
        sendButton = new Button("Send", icon(FontAwesomeSolid.PAPER_PLANE, "#ffffff", 13)); sendButton.getStyleClass().add("send-button"); sendButton.setOnAction(e -> sendMail());
        HBox composerActions = new HBox(10, attach, spacer(), new Label("Enter a subject and message to send"), sendButton); composerActions.setAlignment(Pos.CENTER_LEFT); composerActions.getChildren().get(2).getStyleClass().add("muted-small");
        VBox composer = new VBox(8, toField, subjectField, bodyArea, composerActions); composer.setPadding(new Insets(14, 20, 18, 20)); composer.getStyleClass().add("composer");
        VBox conversation = new VBox(conversationHeader, messages, composer); conversation.getStyleClass().add("conversation"); VBox.setVgrow(conversation, Priority.ALWAYS);
        BorderPane main = new BorderPane(conversation, top, null, null, null); main.getStyleClass().add("main-pane");
        BorderPane root = new BorderPane(main, null, null, null, sidebar); root.getStyleClass().add("app-root");
        Scene scene = new Scene(root, 1280, 850); scene.getStylesheets().add(getClass().getResource("mailflow.css").toExternalForm());
        stage.setTitle("MailFlow — Conversations"); stage.setMinWidth(960); stage.setMinHeight(700); stage.setScene(scene); stage.show();
        discoverServer();
    }

    private void sendMail() {
        String from = fromField.getText().trim(), pass = passwordField.getText().trim(), to = toField.getText().trim(), subject = subjectField.getText().trim(), body = bodyArea.getText().trim();
        if (from.isEmpty() || pass.isEmpty() || to.isEmpty() || subject.isEmpty() || body.isEmpty()) { appendSystem("Complete your Gmail address, App Password, recipient, subject, and message first."); return; }
        if (!to.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) { appendSystem("The recipient address does not look valid."); return; }
        setBusy(true); chatTitle.setText(to); appendBubble(body, "You  •  " + subject, true);
        Task<String> task = new Task<String>() { @Override protected String call() throws Exception { return exchange("SEND|" + enc(from) + "|" + enc(pass) + "|" + enc(to) + "|" + enc(subject) + "|" + enc(body), true); } };
        task.setOnSucceeded(e -> { setBusy(false); if ("OK".equals(task.getValue())) appendSystem("Email delivered to Gmail SMTP successfully."); else appendSystem("Gmail rejected the message. Check the App Password, SMTP access, and server activity log."); });
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
    private void appendBubble(String text, String title, boolean outgoing) {
        Label heading = new Label(title); heading.getStyleClass().add("bubble-heading");
        Label body = new Label(text); body.getStyleClass().add("bubble-text"); body.setWrapText(true); body.setMaxWidth(570);
        Label time = new Label(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))); time.getStyleClass().add("bubble-time");
        VBox bubble = new VBox(6, heading, body, time); bubble.getStyleClass().add(outgoing ? "bubble-outgoing" : "bubble-incoming"); bubble.setMaxWidth(620);
        HBox row = new HBox(bubble); row.setAlignment(outgoing ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT); row.setPadding(new Insets(0, 12, 0, 12)); messageList.getChildren().add(row);
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
