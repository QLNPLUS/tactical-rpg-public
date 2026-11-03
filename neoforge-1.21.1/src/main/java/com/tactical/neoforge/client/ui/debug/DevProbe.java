package com.tactical.neoforge.client.ui.debug;

import com.tactical.neoforge.Tactical;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Read-only development probe for UI acceptance runs.
 *
 * <p>It exists so a test driver can locate a control without hard-coded coordinates, replacing the
 * removed {@code /ui} endpoint of the external helper. It never performs an action: it only reports
 * the control ids, rectangles, enabled flags and the layout version of what is currently on screen.
 * A rectangle is a location, never proof that an interaction succeeded.</p>
 *
 * <p>It listens only when {@code -Dtactical.devprobe.port} is set (the development client run sets
 * it), binds to 127.0.0.1 only, and is absent from every release configuration.</p>
 */
public final class DevProbe {

    public static final String PORT_PROPERTY = "tactical.devprobe.port";
    public static final int LAYOUT_VERSION = 1;

    private static volatile int port;
    private static volatile String snapshot = "{\"screen\":\"null\"}";
    /**
     * The battle HUD's read-only report: layout form, bounds, every panel and row rectangle, the
     * hovered control and the journal's state. It is a client mirror and is never presented as a
     * server-side readout; a rectangle is a location, not proof that an interaction succeeded.
     */
    private static volatile String hud = "{\"nodes\":[]}";
    /**
     * The game's own screen while one is open (language list, options pages). Those screens are not
     * painted by our toolkit, so a driver that has to click them with real mouse input gets their
     * widget rectangles here. It is a location report only: the probe never clicks anything.
     */
    private static volatile String activeScreen = "{\"class\":\"none\"}";

    private DevProbe() {
    }

    public static boolean enabled() {
        return port > 0;
    }

    public static int port() {
        return port;
    }

    /** Called by the active native screen once per frame while the probe is enabled. */
    public static void publish(String screenId, String report) {
        if (port <= 0) return;
        String id = screenId == null ? "" : screenId.replace("\"", "");
        snapshot = "{\"screen\":\"" + id + "\",\"layoutVersion\":" + LAYOUT_VERSION
                + ",\"report\":" + (report == null || report.isBlank() ? "{}" : report) + "}";
    }

    /** Publishes "no native tactical screen is open" so a driver can detect a closed menu. */
    public static void publishClosed() {
        snapshot = "{\"screen\":\"null\",\"layoutVersion\":" + LAYOUT_VERSION + "}";
    }

    /**
     * Republish the read-only report of the game's own screen, or {@code none} when our own screen or
     * no screen is open. Called once per client tick; the probe must be enabled to reach any work here.
     */
    public static void publishActiveScreen(boolean foreignScreenOpen) {
        if (port <= 0) return;
        if (!foreignScreenOpen) {
            if (!"{\"class\":\"none\"}".equals(activeScreen)) activeScreen = "{\"class\":\"none\"}";
            return;
        }
        activeScreen = ScreenTree.json();
    }

    /**
     * Publishes the battle HUD's read-only report. It is kept apart from the native screen report
     * because the HUD exists with or without a screen open; a driver has to be able to locate the
     * roster, the journal and the rail in both cases.
     */
    public static void publishHud(String report) {
        if (port <= 0) return;
        hud = report == null || report.isBlank() ? "{\"nodes\":[]}" : report;
    }

    /** Starts the listener when the run asked for it. Idempotent; safe to call on every setup. */
    public static synchronized void startIfConfigured() {
        if (port > 0) return;
        Integer configured = readPort();
        if (configured == null || configured <= 0) return;
        port = configured;
        Thread thread = new Thread(DevProbe::serve, "tactical-devprobe");
        thread.setDaemon(true);
        thread.start();
        Tactical.LOGGER.info("[Tactical UI] development probe listening on 127.0.0.1:{}", port);
    }

    private static Integer readPort() {
        try {
            String value = System.getProperty(PORT_PROPERTY);
            if (value == null || value.isBlank()) return null;
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void serve() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    client.setSoTimeout(4000);
                    readRequest(client);
                    writeResponse(client, compose());
                } catch (IOException ignored) {
                    // A driver that disconnects early must not end the listener.
                }
            }
        } catch (IOException failure) {
            Tactical.LOGGER.warn("[Tactical UI] development probe cannot bind 127.0.0.1:{}", port, failure);
        }
    }

    private static void readRequest(Socket client) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // Only the request line matters; the probe answers every GET with the same snapshot.
        }
    }

    /** The reports are published by different owners, so the answer is composed on request. */
    private static String compose() {
        String base = snapshot;
        int end = base.lastIndexOf('}');
        if (end < 0) return base;
        return base.substring(0, end) + ",\"hud\":" + hud + ",\"activeScreen\":" + activeScreen
                + ",\"text\":" + DrawnText.drainJson() + "}";
    }

    private static void writeResponse(Socket client, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }
}
