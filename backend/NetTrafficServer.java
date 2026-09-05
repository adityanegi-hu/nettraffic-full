import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * NetTrafficServer — Lightweight, zero-dependency network traffic monitor.
 * Samples OS interface counters every second and serves a live JSON API and web dashboard.
 */
public class NetTrafficServer {

    private static final int PORT = 8080;
    private static final int HISTORY_SIZE = 120; // 2 minutes of rolling history at 1s intervals
    private static final int SAMPLE_INTERVAL_MS = 1000;
    private static final String PROC_NET_DEV = "/proc/net/dev";

    // -------------------------------------------------------------------------
    // 1. Core Data Records (Clean, immutable carriers)
    // -------------------------------------------------------------------------

    /** Raw cumulative counters from an OS network interface. */
    record Counters(long rxBytes, long txBytes, long rxPkts, long txPkts, long rxErr, long txErr) {
        Counters add(Counters o) {
            return new Counters(
                rxBytes + o.rxBytes, txBytes + o.txBytes,
                rxPkts + o.rxPkts, txPkts + o.txPkts,
                rxErr + o.rxErr, txErr + o.txErr
            );
        }
    }

    /** Instantaneous throughput rates and running totals at timestamp t. */
    record Sample(
        long t,
        double rxRate, double txRate,
        double rxPktRate, double txPktRate,
        long rxTotal, long txTotal,
        long rxErr, long txErr
    ) {}

    // -------------------------------------------------------------------------
    // 2. Server Startup & Main
    // -------------------------------------------------------------------------

    private final TrafficModel model = new TrafficModel();
    private final Path publicDir;

    public NetTrafficServer(Path publicDir) {
        this.publicDir = publicDir;
    }

    public static void main(String[] args) throws Exception {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : PORT;

        // Auto-locate web directory whether executed from root or backend/
        Path publicDir = Paths.get("public").toAbsolutePath().normalize();
        if (!Files.isDirectory(publicDir) && Files.isDirectory(Paths.get("../public"))) {
            publicDir = Paths.get("../public").toAbsolutePath().normalize();
        }

        new NetTrafficServer(publicDir).start(port);
    }

    public void start(int port) throws IOException {
        boolean supported = isOsSupported();
        model.supported = supported;

        // 1-second fixed background sampler
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "traffic-sampler");
            t.setDaemon(true);
            return t;
        });

        if (supported) {
            sampler.scheduleAtFixedRate(this::sampleOnce, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        // Built-in JDK HTTP Server
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/stats", new StatsHandler());
        server.createContext("/api/history", new HistoryHandler());
        server.createContext("/", new StaticHandler(publicDir));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("Network Traffic Monitor running at http://localhost:" + port);
        System.out.println("Host OS: " + System.getProperty("os.name") + " | Live capture: " + supported);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sampler.shutdownNow();
            server.stop(0);
        }));
    }

    private static boolean isOsSupported() {
        if (Files.isReadable(Paths.get(PROC_NET_DEV))) return true;
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") || os.contains("mac") || os.contains("darwin") || os.contains("nix") || os.contains("nux");
    }

    // -------------------------------------------------------------------------
    // 3. Platform Samplers (Linux, Windows, macOS)
    // -------------------------------------------------------------------------

    private void sampleOnce() {
        try {
            Map<String, Counters> raw = readNetworkStats();
            if (raw != null && !raw.isEmpty()) {
                model.applySample(raw, System.currentTimeMillis());
            }
        } catch (Exception ignored) {
            // Transient read skip
        }
    }

    private static Map<String, Counters> readNetworkStats() {
        if (Files.isReadable(Paths.get(PROC_NET_DEV))) {
            try { return readLinuxStats(); } catch (Exception ignored) {}
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return readWindowsStats();
        if (os.contains("mac") || os.contains("darwin")) return readMacStats();
        return Collections.emptyMap();
    }

    /** Linux: reads /proc/net/dev directly without root privileges. */
    private static Map<String, Counters> readLinuxStats() throws IOException {
        Map<String, Counters> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Paths.get(PROC_NET_DEV))) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            String[] cols = line.substring(colon + 1).trim().split("\\s+");
            if (cols.length < 16) continue;

            long rxBytes = parseLong(cols[0]);
            long rxPkts  = parseLong(cols[1]);
            long rxErrs  = parseLong(cols[2]) + parseLong(cols[3]);
            long txBytes = parseLong(cols[8]);
            long txPkts  = parseLong(cols[9]);
            long txErrs  = parseLong(cols[10]) + parseLong(cols[11]);

            map.put(name, new Counters(rxBytes, txBytes, rxPkts, txPkts, rxErrs, txErrs));
        }
        return map;
    }

    /** Windows: reads IPv4 & IPv6 subinterfaces via netsh. */
    private static Map<String, Counters> readWindowsStats() {
        Map<String, Counters> map = new LinkedHashMap<>();
        for (String ipVer : List.of("ipv4", "ipv6")) {
            for (String line : runCommand("netsh", "interface", ipVer, "show", "subinterfaces")) {
                String[] parts = line.trim().split("\\s+", 5);
                if (parts.length < 5 || parts[0].startsWith("-") || parts[0].equalsIgnoreCase("mtu")) continue;

                long bytesIn  = parseLong(parts[2]);
                long bytesOut = parseLong(parts[3]);
                String name   = parts[4].trim();

                long rxPkts = bytesIn > 0 ? Math.max(1, bytesIn / 1200) : 0;
                long txPkts = bytesOut > 0 ? Math.max(1, bytesOut / 1200) : 0;

                Counters c = new Counters(bytesIn, bytesOut, rxPkts, txPkts, 0, 0);
                map.merge(name, c, Counters::add);
            }
        }
        return map;
    }

    /** macOS: reads BSD link-layer counters via netstat. */
    private static Map<String, Counters> readMacStats() {
        Map<String, Counters> map = new LinkedHashMap<>();
        for (String line : runCommand("netstat", "-b", "-i", "-n")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length >= 11 && cols[2].startsWith("<Link")) {
                String name   = cols[0];
                long rxPkts  = parseLong(cols[4]);
                long rxErrs  = parseLong(cols[5]);
                long rxBytes = parseLong(cols[6]);
                long txPkts  = parseLong(cols[7]);
                long txErrs  = parseLong(cols[8]);
                long txBytes = parseLong(cols[9]);

                Counters c = new Counters(rxBytes, txBytes, rxPkts, txPkts, rxErrs, txErrs);
                map.merge(name, c, Counters::add);
            }
        }
        return map;
    }

    /** Safely executes a system command and terminates child process on timeout. */
    private static List<String> runCommand(String... cmd) {
        List<String> lines = new ArrayList<>();
        Process process = null;
        try {
            process = new ProcessBuilder(cmd).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) lines.add(line);
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
        return lines;
    }

    // -------------------------------------------------------------------------
    // 4. In-Memory Model & Rolling Buffers
    // -------------------------------------------------------------------------

    static final class IfaceHistory {
        final Deque<Sample> history = new ArrayDeque<>(HISTORY_SIZE);
        Counters lastCounters;
        volatile Sample latest;
    }

    static final class TrafficModel {
        volatile boolean supported = true;
        private final Map<String, IfaceHistory> ifaces = new ConcurrentHashMap<>();
        private final Deque<Sample> totalHistory = new ArrayDeque<>(HISTORY_SIZE);
        private volatile Sample totalLatest = new Sample(System.currentTimeMillis(), 0, 0, 0, 0, 0, 0, 0, 0);
        private long lastTime = 0;

        synchronized void applySample(Map<String, Counters> current, long now) {
            double dt = (lastTime == 0) ? 1.0 : Math.max(0.001, (now - lastTime) / 1000.0);
            lastTime = now;

            double sumRxRate = 0, sumTxRate = 0, sumRxPkts = 0, sumTxPkts = 0;
            long sumRxBytes = 0, sumTxBytes = 0, sumRxErr = 0, sumTxErr = 0;

            for (var entry : current.entrySet()) {
                String name = entry.getKey();
                Counters c = entry.getValue();
                IfaceHistory state = ifaces.computeIfAbsent(name, k -> new IfaceHistory());

                double rxRate = 0, txRate = 0, rxPktRate = 0, txPktRate = 0;
                if (state.lastCounters != null) {
                    rxRate    = rate(c.rxBytes() - state.lastCounters.rxBytes(), dt);
                    txRate    = rate(c.txBytes() - state.lastCounters.txBytes(), dt);
                    rxPktRate = rate(c.rxPkts()  - state.lastCounters.rxPkts(), dt);
                    txPktRate = rate(c.txPkts()  - state.lastCounters.txPkts(), dt);
                }
                state.lastCounters = c;

                Sample sample = new Sample(now, rxRate, txRate, rxPktRate, txPktRate,
                        c.rxBytes(), c.txBytes(), c.rxErr(), c.txErr());
                state.latest = sample;
                push(state.history, sample);

                // Exclude loopback from network-wide totals
                if (!isLoopback(name)) {
                    sumRxRate  += rxRate;
                    sumTxRate  += txRate;
                    sumRxPkts  += rxPktRate;
                    sumTxPkts  += txPktRate;
                    sumRxBytes += c.rxBytes();
                    sumTxBytes += c.txBytes();
                    sumRxErr   += c.rxErr();
                    sumTxErr   += c.txErr();
                }
            }

            Sample totals = new Sample(now, sumRxRate, sumTxRate, sumRxPkts, sumTxPkts,
                    sumRxBytes, sumTxBytes, sumRxErr, sumTxErr);
            totalLatest = totals;
            push(totalHistory, totals);
        }

        private static double rate(long delta, double dt) {
            return delta > 0 ? (delta / dt) : 0;
        }

        private static boolean isLoopback(String name) {
            String s = name.toLowerCase();
            return s.equals("lo") || s.contains("loopback") || s.contains("pseudo-interface");
        }

        private static void push(Deque<Sample> dq, Sample s) {
            dq.addLast(s);
            while (dq.size() > HISTORY_SIZE) dq.removeFirst();
        }

        Map<String, Sample> latestByIface() {
            Map<String, Sample> map = new TreeMap<>();
            ifaces.forEach((k, v) -> { if (v.latest != null) map.put(k, v.latest); });
            return map;
        }

        Sample totalLatest() { return totalLatest; }

        synchronized List<Sample> historyFor(String iface) {
            if (iface == null || iface.isEmpty() || iface.equals("_total")) {
                return new ArrayList<>(totalHistory);
            }
            IfaceHistory h = ifaces.get(iface);
            return (h != null) ? new ArrayList<>(h.history) : List.of();
        }
    }

    // -------------------------------------------------------------------------
    // 5. HTTP Handlers & REST API
    // -------------------------------------------------------------------------

    private class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!isMethodAllowed(ex)) return;

            StringBuilder sb = new StringBuilder();
            sb.append("{\"supported\":").append(model.supported)
              .append(",\"os\":\"").append(escape(System.getProperty("os.name"))).append("\"")
              .append(",\"timestamp\":").append(System.currentTimeMillis())
              .append(",\"totals\":").append(sampleJson(model.totalLatest()))
              .append(",\"interfaces\":[");

            boolean first = true;
            for (var entry : model.latestByIface().entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"name\":\"").append(escape(entry.getKey()))
                  .append("\",\"data\":").append(sampleJson(entry.getValue())).append("}");
            }
            sb.append("]}");

            sendJson(ex, 200, sb.toString());
        }
    }

    private class HistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!isMethodAllowed(ex)) return;

            Map<String, String> params = parseQuery(ex.getRequestURI());
            String iface = params.getOrDefault("iface", "_total");
            List<Sample> samples = model.historyFor(iface);

            StringBuilder sb = new StringBuilder();
            sb.append("{\"iface\":\"").append(escape(iface))
              .append("\",\"supported\":").append(model.supported)
              .append(",\"samples\":[");

            for (int i = 0; i < samples.size(); i++) {
                if (i > 0) sb.append(",");
                Sample s = samples.get(i);
                sb.append("{\"t\":").append(s.t())
                  .append(",\"rxRate\":").append(fmt(s.rxRate()))
                  .append(",\"txRate\":").append(fmt(s.txRate())).append("}");
            }
            sb.append("]}");

            sendJson(ex, 200, sb.toString());
        }
    }

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
            String relative = path.startsWith("/") ? path.substring(1) : path;
            Path resolved = root.resolve(relative).normalize();

            if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
                byte[] notFound = "404 Not Found".getBytes(StandardCharsets.UTF_8);
                sendResponse(ex, 404, "text/plain; charset=utf-8", notFound);
                return;
            }

            byte[] content = Files.readAllBytes(resolved);
            sendResponse(ex, 200, contentType(resolved.toString()), content);
        }

        private static String contentType(String filename) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".html")) return "text/html; charset=utf-8";
            if (lower.endsWith(".css"))  return "text/css; charset=utf-8";
            if (lower.endsWith(".js"))   return "application/javascript; charset=utf-8";
            if (lower.endsWith(".json")) return "application/json; charset=utf-8";
            if (lower.endsWith(".svg"))  return "image/svg+xml";
            return "application/octet-stream";
        }
    }

    // -------------------------------------------------------------------------
    // 6. Utility Helpers
    // -------------------------------------------------------------------------

    private static boolean isMethodAllowed(HttpExchange ex) throws IOException {
        String m = ex.getRequestMethod();
        if ("GET".equalsIgnoreCase(m) || "HEAD".equalsIgnoreCase(m)) return true;
        sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
        return false;
    }

    private static void sendResponse(HttpExchange ex, int status, String mime, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", mime);
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        sendResponse(ex, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static String sampleJson(Sample s) {
        return "{\"rxRate\":" + fmt(s.rxRate()) + ",\"txRate\":" + fmt(s.txRate())
                + ",\"rxPktRate\":" + fmt(s.rxPktRate()) + ",\"txPktRate\":" + fmt(s.txPktRate())
                + ",\"rxTotal\":" + s.rxTotal() + ",\"txTotal\":" + s.txTotal()
                + ",\"rxErr\":" + s.rxErr() + ",\"txErr\":" + s.txErr() + "}";
    }

    private static String fmt(double val) {
        return String.format(Locale.ROOT, "%.2f", val);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseUnsignedLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String escape(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }
}
