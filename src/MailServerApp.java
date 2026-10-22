import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.chart.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

public class MailServerApp extends Application {
    private static final int DEFAULT_PORT = 2525;
    private volatile int port = DEFAULT_PORT;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private TextArea logArea;
    private Label statusLabel, clientCount, requestCount, uptimeLabel, addressLabel;
    private Label rateLabel;
    private Button startButton;
    private XYChart.Series<String, Number> requestSeries;
    private ServerSocket activeServerSocket;
    private volatile boolean running;
    private volatile int clients, requests;
    private long startedAt;

    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) {
        Label brand = new Label("✉  MAILFLOW"); brand.setStyle("-fx-font-size:19px;-fx-font-weight:bold;-fx-text-fill:#132238;");
        Label subtitle = new Label("MAIL SERVER  /  ADMIN CONSOLE"); subtitle.setStyle("-fx-font-size:10px;-fx-text-fill:#8291a5;-fx-font-weight:bold;");
        VBox brandBox = new VBox(3, brand, subtitle);
        statusLabel = badge("●  OFFLINE", "#fff1f0", "#c2413a");
        addressLabel = new Label("Detecting network…"); addressLabel.setStyle("-fx-background-color:#f1f5f9;-fx-background-radius:18;-fx-padding:9 14;-fx-text-fill:#52627a;-fx-font-family:monospace;");
        HBox header = new HBox(16, brandBox, spacer(), addressLabel, statusLabel); header.setAlignment(Pos.CENTER_LEFT); header.setPadding(new Insets(18, 26, 18, 26)); header.setStyle("-fx-background-color:white;-fx-border-color:transparent transparent #e8edf3 transparent;");

        Label pageTitle = new Label("Server overview"); pageTitle.setStyle("-fx-font-size:22px;-fx-font-weight:bold;-fx-text-fill:#18283c;");
        Label pageHint = new Label("Monitor incoming mail requests and service availability."); pageHint.setStyle("-fx-font-size:12px;-fx-text-fill:#7b899b;");
        VBox intro = new VBox(5, pageTitle, pageHint);
        startButton = new Button("Start mail server", icon(FontAwesomeSolid.PLAY, "#ffffff", 12)); startButton.setStyle(primary()); startButton.setOnAction(e -> startServer());
        Button stopButton = new Button("Stop", icon(FontAwesomeSolid.STOP, "#c2413a", 12)); stopButton.setStyle(danger()); stopButton.setOnAction(e -> stopServer());
        HBox controls = new HBox(10, startButton, stopButton); controls.setAlignment(Pos.CENTER_RIGHT);
        HBox titleRow = new HBox(intro, spacer(), controls); titleRow.setAlignment(Pos.CENTER_LEFT);

        clientCount = new Label("0"); requestCount = new Label("0"); uptimeLabel = new Label("—");
        rateLabel = new Label("0");
        HBox metrics = new HBox(14, metric("↔", "Requests / sec", rateLabel, "Mail operations received"), metric("◉", "Clients online", clientCount, "Active socket sessions"), metric("◷", "Total requests", requestCount, "Since server started"), metric("▣", "Server uptime", uptimeLabel, "Current session"));
        for (javafx.scene.Node n : metrics.getChildren()) HBox.setHgrow(n, Priority.ALWAYS);

        CategoryAxis xAxis = new CategoryAxis(); xAxis.setLabel("Request sequence");
        NumberAxis yAxis = new NumberAxis(); yAxis.setLabel("Requests"); yAxis.setForceZeroInRange(true);
        LineChart<String, Number> chart = new LineChart<>(xAxis, yAxis); chart.setTitle("Mail requests over time"); chart.setLegendVisible(false); chart.setAnimated(false); chart.setCreateSymbols(true); chart.setMinHeight(240); chart.setPrefHeight(280);
        requestSeries = new XYChart.Series<>(); chart.getData().add(requestSeries);
        VBox chartCard = card(chart); VBox.setVgrow(chart, Priority.ALWAYS);

        Label logsTitle = new Label("Activity log"); logsTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#1d2c40;");
        Label live = badge("●  LIVE", "#e6f8f0", "#16845b");
        HBox logHeader = new HBox(logsTitle, spacer(), live); logHeader.setAlignment(Pos.CENTER_LEFT);
        logArea = new TextArea(); logArea.setEditable(false); logArea.setWrapText(true); logArea.setFont(Font.font("Consolas", 12)); logArea.setStyle("-fx-control-inner-background:#111d2d;-fx-text-fill:#d6e2ef;-fx-highlight-fill:#245d72;-fx-background-radius:10;");
        VBox logCard = card(logHeader, logArea); VBox.setVgrow(logArea, Priority.ALWAYS);
        VBox content = new VBox(18, titleRow, metrics, chartCard, logCard); content.setPadding(new Insets(26)); VBox.setVgrow(chartCard, Priority.ALWAYS); VBox.setVgrow(logCard, Priority.ALWAYS);
        BorderPane root = new BorderPane(content, header, null, null, null); root.setStyle("-fx-background-color:#f3f7fb;");
        stage.setTitle("MailFlow — Server Console"); stage.setMinWidth(920); stage.setMinHeight(680); Scene scene = new Scene(root, 1220, 860); scene.getStylesheets().add(getClass().getResource("mailflow.css").toExternalForm()); stage.setScene(scene); stage.show();
        log("Console ready. Detecting an available port and network address…");
        startServer();
        final int[] previousRequests = {0}; javafx.animation.Timeline timer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> { rateLabel.setText(String.valueOf(requests - previousRequests[0])); previousRequests[0] = requests; refreshMetrics(); })); timer.setCycleCount(javafx.animation.Animation.INDEFINITE); timer.play();
    }

    private void startServer() {
        if (running) return;
        running = true; startedAt = System.currentTimeMillis(); startButton.setDisable(true); statusLabel.setText("●  STARTING");
        Thread listener = new Thread(() -> {
            try {
                ServerSocket selected = null;
                for (int candidate = DEFAULT_PORT; candidate < DEFAULT_PORT + 20 && selected == null; candidate++) {
                    try { selected = new ServerSocket(candidate); }
                    catch (BindException occupied) { log("Port " + candidate + " is occupied; checking the next available port…"); }
                }
                if (selected == null) throw new BindException("No available port in range " + DEFAULT_PORT + "–" + (DEFAULT_PORT + 19));
                try (ServerSocket server = selected) {
                activeServerSocket = server;
                port = server.getLocalPort();
                String host = detectLocalAddress();
                writeEndpoint(host, port);
                startDiscoveryResponder();
                log("Listener active at " + host + ":" + port + ". Client discovery is enabled.");
                Platform.runLater(() -> { statusLabel.setText("●  RUNNING"); statusLabel.setStyle("-fx-background-color:#e6f8f0;-fx-text-fill:#16845b;-fx-font-size:11px;-fx-font-weight:bold;-fx-padding:9 13;-fx-background-radius:20;"); addressLabel.setText("TCP  •  " + host + ":" + port); });
                while (running) { Socket socket = server.accept(); clients++; pool.submit(() -> { try { handleClient(socket); } finally { clients--; refreshMetrics(); } }); }
                }
            } catch (IOException e) { if (running) { log("Server could not start: " + e.getMessage()); Platform.runLater(() -> { statusLabel.setText("●  ERROR"); statusLabel.setStyle("-fx-background-color:#fff1f0;-fx-text-fill:#c2413a;-fx-padding:9 13;-fx-background-radius:20;"); }); } }
            finally { activeServerSocket = null; running = false; Platform.runLater(() -> startButton.setDisable(false)); }
        }, "mail-server-listener"); listener.setDaemon(true); listener.start();
    }

    private void stopServer() {
        running = false;
        ServerSocket socket = activeServerSocket;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        statusLabel.setText("●  STOPPING");
        log("Server stopped.");
    }

    private void startDiscoveryResponder() {
        Thread discovery = new Thread(() -> {
            try (DatagramSocket udp = new DatagramSocket(25250)) {
                byte[] buffer = new byte[128];
                while (running) {
                    DatagramPacket request = new DatagramPacket(buffer, buffer.length); udp.receive(request);
                    String message = new String(request.getData(), request.getOffset(), request.getLength(), StandardCharsets.UTF_8);
                    if ("MAILFLOW_DISCOVER".equals(message)) {
                        byte[] response = ("MAILFLOW|" + port).getBytes(StandardCharsets.UTF_8);
                        udp.send(new DatagramPacket(response, response.length, request.getAddress(), request.getPort()));
                    }
                }
            } catch (IOException e) { if (running) log("LAN discovery unavailable: " + e.getMessage() + ". Local auto-detection remains available."); }
        }, "mailflow-discovery"); discovery.setDaemon(true); discovery.start();
    }

    private static String detectLocalAddress() {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) { socket.connect(InetAddress.getByName("8.8.8.8"), 53); return socket.getLocalAddress().getHostAddress(); }
        catch (Exception ignored) { try { return InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { return "127.0.0.1"; } }
    }

    private void writeEndpoint(String host, int selectedPort) {
        try { Files.write(Paths.get("mailflow-server.properties"), ("host=" + host + "\nport=" + selectedPort + "\n").getBytes(StandardCharsets.UTF_8)); }
        catch (IOException e) { log("Could not save local discovery info: " + e.getMessage()); }
    }

    private static FontIcon icon(FontAwesomeSolid glyph, String color, int size) { FontIcon icon = new FontIcon(glyph); icon.setIconColor(Color.web(color)); icon.setIconSize(size); return icon; }
    private static String dec(String value) { return new String(java.util.Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private void handleClient(Socket client) {
        requests++; refreshMetrics(); Platform.runLater(() -> { if (requestSeries != null) { requestSeries.getData().add(new XYChart.Data<>(String.valueOf(requests), requests)); if (requestSeries.getData().size() > 20) requestSeries.getData().remove(0); } });
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)); PrintWriter out = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true)) {
            log("Client connected: " + client.getInetAddress().getHostAddress());
            String command = in.readLine(); if (command == null) return;
            String[] p = command.split("\\|", 6);
            if (p[0].equals("SEND") && p.length == 6) { String from = dec(p[1]), to = dec(p[3]); log("SMTP send request: " + from + " → " + to); out.println(sendMailViaGmail(from, dec(p[2]), to, dec(p[4]), dec(p[5])) ? "OK" : "FAIL"); }
            else if (p[0].equals("RECV") && p.length == 3) { String email = dec(p[1]); log("POP3 inbox request: " + email); out.println(receiveMailViaGmail(email, dec(p[2]))); }
            else out.println("FAIL");
        } catch (Exception e) { log("Client handling error: " + e.getMessage()); }
    }

    private boolean sendMailViaGmail(String from, String pass, String to, String subject, String content) {
        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket("smtp.gmail.com", 465); BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)); BufferedWriter w = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            r.readLine(); w.write("EHLO localhost\r\n"); w.flush(); String line; while ((line = r.readLine()) != null) if (line.length() > 3 && line.charAt(3) == ' ') break;
            w.write("AUTH LOGIN\r\n"); w.flush(); r.readLine(); w.write(java.util.Base64.getEncoder().encodeToString(from.getBytes(StandardCharsets.UTF_8)) + "\r\n"); w.flush(); r.readLine(); w.write(java.util.Base64.getEncoder().encodeToString(pass.getBytes(StandardCharsets.UTF_8)) + "\r\n"); w.flush(); String auth = r.readLine(); if (auth == null || !auth.startsWith("235")) { log("SMTP authentication failed: " + auth); return false; }
            w.write("MAIL FROM:<" + from + ">\r\n"); w.flush(); r.readLine(); w.write("RCPT TO:<" + to + ">\r\n"); w.flush(); r.readLine(); w.write("DATA\r\n"); w.flush(); r.readLine();
            w.write("Subject: " + subject + "\r\nFrom: " + from + "\r\nTo: " + to + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + content.replace("\r\n", "\n").replace("\n", "\r\n") + "\r\n.\r\n"); w.flush(); String result = r.readLine(); log("SMTP result: " + result); w.write("QUIT\r\n"); w.flush(); return result != null && result.startsWith("250");
        } catch (Exception e) { log("SMTP error: " + e.getMessage()); return false; }
    }

    private String receiveMailViaGmail(String email, String pass) {
        StringBuilder result = new StringBuilder();
        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket("pop.gmail.com", 995); BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)); BufferedWriter w = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            r.readLine(); w.write("USER " + email + "\r\n"); w.flush(); String user = r.readLine(); w.write("PASS " + pass + "\r\n"); w.flush(); String auth = r.readLine();
            if (user == null || !user.startsWith("+OK") || auth == null || !auth.startsWith("+OK")) return "POP3 authentication failed. Check Gmail App Password and POP access.";
            w.write("STAT\r\n"); w.flush(); String stat = r.readLine(); result.append("Mailbox: ").append(stat).append("\n\n");
            if (stat != null && stat.matches("\\+OK\\s+0\\s+.*")) return "Your inbox is empty.";
            w.write("RETR 1\r\n"); w.flush(); String line; while ((line = r.readLine()) != null) { if (line.equals(".")) break; result.append(line.startsWith("..") ? line.substring(1) : line).append('\n'); }
            w.write("QUIT\r\n"); w.flush();
        } catch (Exception e) { return "POP3 error: " + e.getMessage(); }
        return result.toString();
    }

    private void refreshMetrics() { Platform.runLater(() -> { if (clientCount != null) { clientCount.setText(String.valueOf(clients)); requestCount.setText(String.valueOf(requests)); if (running) { long s = (System.currentTimeMillis() - startedAt) / 1000; uptimeLabel.setText(String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)); } } }); }
    private void log(String text) { Platform.runLater(() -> { if (logArea != null) logArea.appendText(String.format("%tT  %s%n", System.currentTimeMillis(), text)); }); }
    private static Region spacer() { Region r = new Region(); HBox.setHgrow(r, Priority.ALWAYS); return r; }
    private static Label badge(String text, String bg, String fg) { Label l = new Label(text); l.setStyle("-fx-background-color:" + bg + ";-fx-text-fill:" + fg + ";-fx-font-size:11px;-fx-font-weight:bold;-fx-padding:9 13;-fx-background-radius:20;"); return l; }
    private static VBox metric(String icon, String title, Label value, String hint) { Label i = new Label(icon); i.setStyle("-fx-text-fill:#078b91;-fx-font-size:19px;-fx-font-weight:bold;-fx-background-color:#e5f6f5;-fx-background-radius:12;-fx-padding:8 12;"); Label t = new Label(title); t.setStyle("-fx-text-fill:#718096;-fx-font-size:12px;"); value.setStyle("-fx-text-fill:#17283d;-fx-font-size:27px;-fx-font-weight:bold;"); Label h = new Label(hint); h.setStyle("-fx-text-fill:#94a0af;-fx-font-size:10px;"); VBox v = new VBox(10, i, t, value, h); v.setPadding(new Insets(18)); v.setStyle("-fx-background-color:white;-fx-background-radius:14;-fx-border-color:#e4eaf1;-fx-border-radius:14;"); return v; }
    private static VBox card(javafx.scene.Node... nodes) { VBox v = new VBox(14, nodes); v.setPadding(new Insets(18)); v.setStyle("-fx-background-color:white;-fx-background-radius:14;-fx-border-color:#e4eaf1;-fx-border-radius:14;"); return v; }
    private static String primary() { return "-fx-background-color:#078b91;-fx-text-fill:white;-fx-font-weight:bold;-fx-background-radius:9;-fx-padding:11 18;-fx-cursor:hand;"; }
    private static String danger() { return "-fx-background-color:#fff1f0;-fx-text-fill:#c2413a;-fx-font-weight:bold;-fx-background-radius:9;-fx-padding:11 18;-fx-cursor:hand;"; }
}
