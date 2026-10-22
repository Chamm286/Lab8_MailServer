package client;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Reads the newest message through Gmail POP3 over SSL. */
public final class GmailPop3Service {
    private GmailPop3Service() { }

    public static String fetchLatest(String email, String appPassword) throws IOException {
        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket("pop.gmail.com", 995);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String greeting = in.readLine(); expectOk(greeting, "Gmail POP3 greeting");
            out.write("USER " + clean(email) + "\r\n"); out.flush(); expectOk(in.readLine(), "Gmail account");
            out.write("PASS " + appPassword.replaceAll("\\s", "") + "\r\n"); out.flush(); expectOk(in.readLine(), "Gmail App Password");
            out.write("STAT\r\n"); out.flush(); String stat = in.readLine(); expectOk(stat, "Gmail inbox status");
            String[] status = stat.split("\\s+"); int count = status.length > 1 ? Integer.parseInt(status[1]) : 0;
            if (count == 0) return "Your Gmail inbox is empty.";
            out.write("RETR " + count + "\r\n"); out.flush(); expectOk(in.readLine(), "latest Gmail message");
            StringBuilder message = new StringBuilder(); String line;
            while ((line = in.readLine()) != null && !".".equals(line)) message.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
            out.write("QUIT\r\n"); out.flush();
            return message.toString();
        } catch (NumberFormatException e) { throw new IOException("Gmail returned an invalid mailbox status.", e); }
    }

    private static String clean(String value) throws IOException { if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IOException("Invalid Gmail account value."); return value.trim(); }
    private static void expectOk(String line, String step) throws IOException { if (line == null || !line.startsWith("+OK")) throw new IOException(step + " failed: " + (line == null ? "connection closed" : line)); }
}
