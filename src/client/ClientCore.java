package client;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** UDP transport with automatic local/LAN discovery (no fixed server IP). */
public class ClientCore {
    private static final int DISCOVERY_PORT = 20230;
    private volatile InetAddress serverAddress;
    private volatile int port = 2023;
    private final DatagramSocket socket;

    public ClientCore() throws Exception {
        socket = new DatagramSocket();
        socket.setSoTimeout(4000);
        serverAddress = InetAddress.getLoopbackAddress();
    }

    public String getServerIP() { return serverAddress.getHostAddress(); }
    public int getPort() { return port; }

    public synchronized String send(String request) throws Exception {
        try { return exchange(request, serverAddress, port, 4000); }
        catch (SocketTimeoutException first) {
            discoverServer();
            return exchange(request, serverAddress, port, 5000);
        }
    }

    private String exchange(String request, InetAddress address, int destinationPort, int timeout) throws Exception {
        socket.setSoTimeout(timeout);
        byte[] bytes = request.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(bytes, bytes.length, address, destinationPort));
        byte[] response = new byte[65507]; DatagramPacket packet = new DatagramPacket(response, response.length);
        socket.receive(packet);
        return new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
    }

    private void discoverServer() throws Exception {
        socket.setBroadcast(true);
        byte[] query = "DISCOVER|MAILFLOW".getBytes(StandardCharsets.UTF_8);
        List<InetAddress> targets = new ArrayList<>(); targets.add(InetAddress.getLoopbackAddress()); targets.add(InetAddress.getByName("255.255.255.255"));
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
        }
        for (InetAddress target : targets) {
            try {
                socket.setSoTimeout(700); socket.send(new DatagramPacket(query, query.length, target, DISCOVERY_PORT));
                byte[] bytes = new byte[256]; DatagramPacket answer = new DatagramPacket(bytes, bytes.length); socket.receive(answer);
                String[] parts = new String(answer.getData(), answer.getOffset(), answer.getLength(), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length >= 3 && "OK".equals(parts[0]) && "SERVER".equals(parts[1])) {
                    serverAddress = answer.getAddress(); port = Integer.parseInt(parts[2]); return;
                }
            } catch (SocketTimeoutException ignored) { }
        }
        throw new SocketTimeoutException("Mail server not found. Start the server and allow UDP port 2023 through Windows Firewall.");
    }

    public void close() { socket.close(); }
}
