import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class NetTrafficServer {

    private static final String PROC_NET_DEV = "/proc/net/dev";
    private static final int HISTORY_SIZE = 120; // 2 minutes at a 1s sample rate
    private static final int SAMPLE_INTERVAL_MS = 1000;

    // /proc/net/dev column indices (after interface name)
    private static final int RX_BYTES = 0, RX_PACKETS = 1, RX_ERRS = 2, RX_DROP = 3;
    private static final int TX_BYTES = 8, TX_PACKETS = 9, TX_ERRS = 10, TX_DROP = 11;

    private final TrafficModel model = new TrafficModel();
    private final Path publicDir;

    public NetTrafficServer(Path publicDir) {
        this.publicDir = publicDir;
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path publicDir = Paths.get("public").toAbsolutePath().normalize();

        if (!Files.isDirectory(publicDir) && Files.isDirectory(Paths.get("../public"))) {
            publicDir = Paths.get("../public").toAbsolutePath().normalize();
        }

        if (!Files.isDirectory(publicDir)) {
            System.err.println("Warning: Could not find the 'public' directory at " + publicDir);
            System.err.println("Run this server from the project root (the folder that contains 'backend' and 'public').");
        }

        NetTrafficServer app = new NetTrafficServer(publicDir);
        app.start(port);
    }

    public void start(int port) throws IOException {
        boolean supported = isOsSupported();
        model.setSupported(supported);

        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "traffic-sampler");
            t.setDaemon(true);
            return t;
        });
        if (supported) {
            sampler.scheduleAtFixedRate(this::sampleOnce, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/stats", new StatsHandler());
        server.createContext("/api/history", new HistoryHandler());
        server.createContext("/", new StaticHandler(publicDir));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("Network Traffic Monitor backend running at http://localhost:" + port);
        System.out.println("OS: " + System.getProperty("os.name") + " | Real-time capture supported: " + supported);
        if (!supported) {
            System.out.println("Live throughput is unavailable on this OS; the API will report supported:false.");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sampler.shutdownNow();
            server.stop(0);
        }));
    }

    // ------------------------------------------------------------------
    // Sampling
    // ------------------------------------------------------------------

    private static boolean isOsSupported() {
        if (Files.isReadable(Paths.get(PROC_NET_DEV))) return true;
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") || os.contains("mac") || os.contains("darwin") || os.contains("nix") || os.contains("nux");
    }

    /** Reads OS network stats, updates per-interface + totals history. */
    private void sampleOnce() {
        try {
            Map<String, long[]> raw = readNetworkStats();
            if (raw != null && !raw.isEmpty()) {
                long now = System.currentTimeMillis();
                model.applySample(raw, now);
            }
        } catch (Exception e) {
            // Transient read failure; skip this tick.
        }
    }

    private static Map<String, long[]> readNetworkStats() {
        if (Files.isReadable(Paths.get(PROC_NET_DEV))) {
            try {
                return readProcNetDev();
            } catch (IOException ignored) {}
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return readWindowsStats();
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return readMacStats();
        }
        return Collections.emptyMap();
    }

    private static Map<String, long[]> readProcNetDev() throws IOException {
        Map<String, long[]> result = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(Paths.get(PROC_NET_DEV));
        for (int i = 2; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            String[] parts = line.substring(colon + 1).trim().split("\\s+");
            if (parts.length < 16) continue;
            long[] values = new long[16];
            for (int j = 0; j < 16; j++) {
                try {
                    values[j] = Long.parseUnsignedLong(parts[j]);
                } catch (NumberFormatException e) {
                    values[j] = 0;
                }
            }
            result.put(name, values);
        }
        return result;
    }

    private static Map<String, long[]> readWindowsStats() {
        Map<String, long[]> result = new LinkedHashMap<>();
        parseNetshSubinterfaces("ipv4", result);
        parseNetshSubinterfaces("ipv6", result);
        return result;
    }

    private static void parseNetshSubinterfaces(String ipVersion, Map<String, long[]> result) {
        Process process = null;
        try {
            process = new ProcessBuilder("netsh", "interface", ipVersion, "show", "subinterfaces").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean headerPassed = false;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("---")) {
                        headerPassed = true;
                        continue;
                    }
                    if (!headerPassed) continue;

                    String[] parts = line.split("\\s+", 5);
                    if (parts.length >= 5) {
                        try {
                            long bytesIn = Long.parseUnsignedLong(parts[2]);
                            long bytesOut = Long.parseUnsignedLong(parts[3]);
                            String name = parts[4].trim();

                            long[] values = result.computeIfAbsent(name, k -> new long[16]);
                            values[RX_BYTES] += bytesIn;
                            values[TX_BYTES] += bytesOut;
                            values[RX_PACKETS] += (bytesIn > 0 ? Math.max(1, bytesIn / 1200) : 0);
                            values[TX_PACKETS] += (bytesOut > 0 ? Math.max(1, bytesOut / 1200) : 0);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            if (!process.waitFor(600, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static Map<String, long[]> readMacStats() {
        Map<String, long[]> result = new LinkedHashMap<>();
        Process process = null;
        try {
            process = new ProcessBuilder("netstat", "-b", "-i", "-n").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean headerPassed = false;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("Name") && line.contains("Ibytes")) {
                        headerPassed = true;
                        continue;
                    }
                    if (!headerPassed) continue;

                    String[] parts = line.split("\\s+");
                    if (parts.length >= 11 && parts[2].startsWith("<Link")) {
                        String name = parts[0];
                        try {
                            long rxPkts = Long.parseUnsignedLong(parts[4]);
                            long rxErrs = Long.parseUnsignedLong(parts[5]);
                            long rxBytes = Long.parseUnsignedLong(parts[6]);
                            long txPkts = Long.parseUnsignedLong(parts[7]);
                            long txErrs = Long.parseUnsignedLong(parts[8]);
                            long txBytes = Long.parseUnsignedLong(parts[9]);

                            long[] values = result.computeIfAbsent(name, k -> new long[16]);
                            values[RX_BYTES] = rxBytes;
                            values[RX_PACKETS] = rxPkts;
                            values[RX_ERRS] = rxErrs;
                            values[TX_BYTES] = txBytes;
                            values[TX_PACKETS] = txPkts;
                            values[TX_ERRS] = txErrs;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            if (!process.waitFor(600, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // In-memory model
    // ------------------------------------------------------------------

    /** One computed data point: instantaneous rates + running totals at time t. */
    static final class Sample {
        final long t;
        final double rxRate, txRate, rxPktRate, txPktRate;
        final long rxTotal, txTotal, rxErr, txErr;

        Sample(long t, double rxRate, double txRate, double rxPktRate, double txPktRate,
               long rxTotal, long txTotal, long rxErr, long txErr) {
            this.t = t;
            this.rxRate = rxRate;
            this.txRate = txRate;
            this.rxPktRate = rxPktRate;
            this.txPktRate = txPktRate;
            this.rxTotal = rxTotal;
            this.txTotal = txTotal;
            this.rxErr = rxErr;
            this.txErr = txErr;
        }
    }

    static final class IfaceState {
        final Deque<Sample> history = new ArrayDeque<>(HISTORY_SIZE);
        long[] lastRaw;
        long lastTime;
        volatile Sample latest;
    }

    /** Thread-safe holder for the latest snapshot + rolling history. */
    static final class TrafficModel {
        private volatile boolean supported = true;
        private final Map<String, IfaceState> ifaces = new ConcurrentHashMap<>();
        private final Deque<Sample> totalHistory = new ArrayDeque<>(HISTORY_SIZE);
        private volatile Sample totalLatest = new Sample(System.currentTimeMillis(), 0, 0, 0, 0, 0, 0, 0, 0);
        private long lastTickTime = 0;

        void setSupported(boolean s) { this.supported = s; }
        boolean isSupported() { return supported; }

        synchronized void applySample(Map<String, long[]> raw, long now) {
            double elapsedSec = lastTickTime == 0 ? SAMPLE_INTERVAL_MS / 1000.0
                    : Math.max(0.001, (now - lastTickTime) / 1000.0);
            lastTickTime = now;

            double totalRx = 0, totalTx = 0, totalRxPkt = 0, totalTxPkt = 0;
            long totalRxBytes = 0, totalTxBytes = 0, totalRxErr = 0, totalTxErr = 0;

            for (Map.Entry<String, long[]> e : raw.entrySet()) {
                String name = e.getKey();
                long[] v = e.getValue();
                IfaceState st = ifaces.computeIfAbsent(name, n -> new IfaceState());

                double rxRate = 0, txRate = 0, rxPktRate = 0, txPktRate = 0;
                if (st.lastRaw != null) {
                    long rxDiff = v[RX_BYTES] - st.lastRaw[RX_BYTES];
                    long txDiff = v[TX_BYTES] - st.lastRaw[TX_BYTES];
                    long rxPktDiff = v[RX_PACKETS] - st.lastRaw[RX_PACKETS];
                    long txPktDiff = v[TX_PACKETS] - st.lastRaw[TX_PACKETS];

                    if (rxDiff >= 0) rxRate = rxDiff / elapsedSec;
                    if (txDiff >= 0) txRate = txDiff / elapsedSec;
                    if (rxPktDiff >= 0) rxPktRate = rxPktDiff / elapsedSec;
                    if (txPktDiff >= 0) txPktRate = txPktDiff / elapsedSec;
                }
                st.lastRaw = v;
                st.lastTime = now;

                Sample s = new Sample(now, rxRate, txRate, rxPktRate, txPktRate,
                        v[RX_BYTES], v[TX_BYTES], v[RX_ERRS] + v[RX_DROP], v[TX_ERRS] + v[TX_DROP]);
                st.latest = s;
                pushCapped(st.history, s);

                // Exclude loopback from the network-wide totals - it never leaves the box.
                boolean isLoopback = name.equalsIgnoreCase("lo")
                        || name.toLowerCase().contains("loopback")
                        || name.toLowerCase().contains("pseudo-interface");
                if (!isLoopback) {
                    totalRx += rxRate;
                    totalTx += txRate;
                    totalRxPkt += rxPktRate;
                    totalTxPkt += txPktRate;
                    totalRxBytes += v[RX_BYTES];
                    totalTxBytes += v[TX_BYTES];
                    totalRxErr += v[RX_ERRS] + v[RX_DROP];
                    totalTxErr += v[TX_ERRS] + v[TX_DROP];
                }
            }

            Sample totals = new Sample(now, totalRx, totalTx, totalRxPkt, totalTxPkt,
                    totalRxBytes, totalTxBytes, totalRxErr, totalTxErr);
            totalLatest = totals;
            pushCapped(totalHistory, totals);
        }

        private static void pushCapped(Deque<Sample> dq, Sample s) {
            dq.addLast(s);
            while (dq.size() > HISTORY_SIZE) dq.removeFirst();
        }

        Map<String, Sample> latestByIface() {
            Map<String, Sample> out = new TreeMap<>();
            for (Map.Entry<String, IfaceState> e : ifaces.entrySet()) {
                if (e.getValue().latest != null) out.put(e.getKey(), e.getValue().latest);
            }
            return out;
        }

        Sample totalLatest() { return totalLatest; }

        List<Sample> historyFor(String iface) {
            if (iface == null || iface.isEmpty() || iface.equals("_total")) {
                synchronized (this) {
                    return new ArrayList<>(totalHistory);
                }
            }
            IfaceState st = ifaces.get(iface);
            if (st == null) return List.of();
            synchronized (this) {
                return new ArrayList<>(st.history);
            }
        }
    }

    // ------------------------------------------------------------------
    // HTTP handlers
    // ------------------------------------------------------------------

    private class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"supported\":").append(model.isSupported()).append(",");
            json.append("\"os\":\"").append(escape(System.getProperty("os.name"))).append("\",");
            json.append("\"timestamp\":").append(System.currentTimeMillis()).append(",");

            Sample t = model.totalLatest();
            json.append("\"totals\":").append(sampleJson(t)).append(",");

            json.append("\"interfaces\":[");
            boolean first = true;
            for (Map.Entry<String, Sample> e : model.latestByIface().entrySet()) {
                if (!first) json.append(",");
                first = false;
                json.append("{\"name\":\"").append(escape(e.getKey())).append("\",")
                        .append("\"data\":").append(sampleJson(e.getValue())).append("}");
            }
            json.append("]}");
            sendJson(ex, 200, json.toString());
        }
    }

    private class HistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            Map<String, String> q = parseQuery(ex.getRequestURI());
            String iface = q.get("iface");
            List<Sample> hist = model.historyFor(iface);

            StringBuilder json = new StringBuilder();
            json.append("{\"iface\":\"").append(escape(iface == null ? "_total" : iface)).append("\",");
            json.append("\"supported\":").append(model.isSupported()).append(",");
            json.append("\"samples\":[");
            for (int i = 0; i < hist.size(); i++) {
                if (i > 0) json.append(",");
                Sample s = hist.get(i);
                json.append("{\"t\":").append(s.t)
                        .append(",\"rxRate\":").append(fmt(s.rxRate))
                        .append(",\"txRate\":").append(fmt(s.txRate)).append("}");
            }
            json.append("]}");
            sendJson(ex, 200, json.toString());
        }
    }

    /** Serves static files from the "public" directory; "/" maps to index.html. */
    private static class StaticHandler implements HttpHandler {
        private final Path root;

        StaticHandler(Path root) { this.root = root; }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
                return;
            }

            String path = ex.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";

            // Prevent path traversal outside the public directory.
            String relative = path.startsWith("/") ? path.substring(1) : path;
            Path resolved = root.resolve(relative).normalize();

            if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
                byte[] body = "404 not found".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                if ("HEAD".equalsIgnoreCase(method)) {
                    ex.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
                    ex.sendResponseHeaders(404, -1);
                } else {
                    ex.sendResponseHeaders(404, body.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(body); }
                }
                return;
            }

            byte[] bytes = Files.readAllBytes(resolved);
            ex.getResponseHeaders().set("Content-Type", contentType(resolved.toString()));
            if ("HEAD".equalsIgnoreCase(method)) {
                ex.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
                ex.sendResponseHeaders(200, -1);
            } else {
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
            }
        }

        private static String contentType(String name) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".html")) return "text/html; charset=utf-8";
            if (lower.endsWith(".css")) return "text/css; charset=utf-8";
            if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (lower.endsWith(".json")) return "application/json; charset=utf-8";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            return "application/octet-stream";
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String sampleJson(Sample s) {
        return "{\"rxRate\":" + fmt(s.rxRate) + ",\"txRate\":" + fmt(s.txRate)
                + ",\"rxPktRate\":" + fmt(s.rxPktRate) + ",\"txPktRate\":" + fmt(s.txPktRate)
                + ",\"rxTotal\":" + s.rxTotal + ",\"txTotal\":" + s.txTotal
                + ",\"rxErr\":" + s.rxErr + ",\"txErr\":" + s.txErr + "}";
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> out = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null) return out;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = urlDecode(pair.substring(0, eq));
            String v = urlDecode(pair.substring(eq + 1));
            out.put(k, v);
        }
        return out;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
    }
}
