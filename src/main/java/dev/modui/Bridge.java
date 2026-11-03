package dev.modui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Client side of the launcher socket. One JSON object per line. */
final class Bridge {
    final ConcurrentLinkedQueue<JsonObject> requests = new ConcurrentLinkedQueue<>();
    private final int port;
    private final String token;
    private volatile Writer out;
    volatile boolean connected;
    volatile boolean justConnected;

    Bridge() {
        int p = 0;
        try {
            p = Integer.parseInt(System.getProperty("modui.port", "0"));
        } catch (NumberFormatException ignored) { }
        port = p;
        token = System.getProperty("modui.token", "");
        if (port > 0) {
            Thread t = new Thread(this::run, "ModUi-Bridge");
            t.setDaemon(true);
            t.start();
        }
    }

    boolean enabled() {
        return port > 0;
    }

    private void run() {
        while (true) {
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                JsonObject hello = new JsonObject();
                hello.addProperty("t", "hello");
                hello.addProperty("token", token);
                hello.addProperty("mc", "1.20.1");
                write(hello);
                connected = true;
                justConnected = true;
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        requests.add(JsonParser.parseString(line).getAsJsonObject());
                    } catch (Exception ignored) { }
                }
            } catch (IOException ignored) {
            } finally {
                connected = false;
                out = null;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    void write(JsonObject o) {
        Writer w = out;
        if (w == null) return;
        try {
            synchronized (this) {
                w.write(o.toString());
                w.write('\n');
                w.flush();
            }
        } catch (IOException ignored) { }
    }
}
