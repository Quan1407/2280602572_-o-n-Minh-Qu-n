import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Single-binary server for:
 * - Serving static files (your blog HTML) from the repo root
 * - Providing /api/chat proxy to Gemini (API key stays on server)
 *
 * Run:
 *   cd server
 *   javac Main.java Json.java
 *   setx GEMINI_API_KEY "YOUR_KEY"
 *   java Main
 */
public final class Main {
    private static final int PORT = intEnv("PORT", 8000);
    private static final String GEMINI_API_KEY = env("GEMINI_API_KEY");
    private static final String GEMINI_MODEL = envOr("GEMINI_MODEL", "gemini-1.5-flash");
    private static final int MAX_INPUT_CHARS = intEnv("MAX_INPUT_CHARS", 1200);
    private static final int MAX_HISTORY = intEnv("MAX_HISTORY", 20);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public static void main(String[] args) throws Exception {
        Path siteRoot = resolveSiteRoot();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);

        // API routes
        server.createContext("/api/health", new JsonHandler((ex, body) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("model", GEMINI_MODEL);
            out.put("hasKey", GEMINI_API_KEY != null && !GEMINI_API_KEY.isBlank());
            return out;
        }));
        server.createContext("/api/chat", new JsonHandler(Main::handleChat));

        // Static handler for everything else
        server.createContext("/", new StaticHandler(siteRoot));

        server.setExecutor(null);
        server.start();
        System.out.println("Server running at http://localhost:" + PORT + "/");
        System.out.println("Serving static files from: " + siteRoot);
        System.out.println("Gemini model: " + GEMINI_MODEL);
        System.out.println("Gemini key configured: " + (GEMINI_API_KEY != null && !GEMINI_API_KEY.isBlank()));
    }

    private static Map<String, Object> handleChat(HttpExchange ex, Map<String, Object> body) throws Exception {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            return error("Method not allowed", 405);
        }

        String message = safeTrim(string(body.get("message")));
        if (message == null || message.isBlank()) {
            return error("Bạn chưa nhập nội dung.", 400);
        }
        if (message.length() > MAX_INPUT_CHARS) {
            return error("Nội dung quá dài (tối đa " + MAX_INPUT_CHARS + " ký tự).", 400);
        }

        if (GEMINI_API_KEY == null || GEMINI_API_KEY.isBlank()) {
            return error("Server chưa cấu hình GEMINI_API_KEY. (Để deploy lên web: set env var trên hosting.)", 501);
        }

        List<Map<String, Object>> contents = new ArrayList<>();
        // Optional history: [{role:"user"|"ai", text:"..."}]
        Object h = body.get("history");
        if (h instanceof List<?> list) {
            int added = 0;
            for (Object item : list) {
                if (added >= MAX_HISTORY) break;
                if (!(item instanceof Map<?, ?> m)) continue;
                String role = safeTrim(string(m.get("role")));
                String text = safeTrim(string(m.get("text")));
                if (role == null || text == null || text.isBlank()) continue;
                String apiRole = role.equalsIgnoreCase("ai") ? "model" : "user";
                contents.add(content(apiRole, text));
                added++;
            }
        }

        // Current user message
        contents.add(content("user", message));

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text",
                        "Bạn là trợ lý AI cho blog cá nhân của Đoàn Minh Quân. " +
                        "Trả lời ngắn gọn, rõ ràng, ưu tiên Java 22/JavaScript, học tập và dự án. " +
                        "Không hướng dẫn hành vi trái phép (hack/âm mưu)."
                ))
        ));
        req.put("contents", contents);
        req.put("generationConfig", Map.of(
                "temperature", 0.6,
                "maxOutputTokens", 512
        ));

        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                + urlEncode(GEMINI_MODEL) + ":generateContent?key=" + urlEncode(GEMINI_API_KEY);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(req), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return error("Gemini API lỗi (" + resp.statusCode() + ").", 502, Map.of("raw", resp.body()));
        }

        String reply = extractGeminiText(resp.body());
        if (reply == null || reply.isBlank()) {
            return error("Không lấy được nội dung trả lời từ Gemini.", 502, Map.of("raw", resp.body()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("reply", reply);
        return out;
    }

    private static String extractGeminiText(String json) {
        try {
            Object root = Json.parse(json);
            Map<String, Object> obj = Json.asObject(root);
            Object cand0 = null;
            Object candidates = obj.get("candidates");
            if (candidates instanceof List<?> list && !list.isEmpty()) cand0 = list.get(0);
            if (!(cand0 instanceof Map<?, ?> c0)) return null;

            Object content = c0.get("content");
            if (!(content instanceof Map<?, ?> contentObj)) return null;

            Object parts = contentObj.get("parts");
            if (!(parts instanceof List<?> partsList) || partsList.isEmpty()) return null;
            Object p0 = partsList.get(0);
            if (!(p0 instanceof Map<?, ?> p0m)) return null;

            Object text = p0m.get("text");
            return (text == null) ? null : String.valueOf(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> content(String role, String text) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("role", role);
        c.put("parts", List.of(Map.of("text", text)));
        return c;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            in.transferTo(baos);
            String s = baos.toString(StandardCharsets.UTF_8);
            // Bug-catcher: some clients send UTF-8 with BOM; strip it so JSON parsing works.
            if (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') {
                s = s.substring(1);
            }
            return s;
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        // same-origin by default; add CORS only if you host frontend separately
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendText(HttpExchange ex, int status, String text, String contentType) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", contentType + "; charset=utf-8");
        h.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, Object> error(String msg, int status) {
        return error(msg, status, null);
    }

    private static Map<String, Object> error(String msg, int status, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("status", status);
        out.put("error", msg);
        if (extra != null) out.putAll(extra);
        return out;
    }

    private static Path resolveSiteRoot() {
        // server/ is inside repo root => static root is parent directory
        Path cwd = Path.of("").toAbsolutePath().normalize();
        // If running from repo root, use it; if running from server/, use parent.
        if (Files.exists(cwd.resolve("index.html"))) return cwd;
        if (Files.exists(cwd.resolve("server").resolve("Main.java"))) return cwd;
        Path parent = cwd.getParent();
        if (parent != null && Files.exists(parent.resolve("index.html"))) return parent;
        return cwd;
    }

    private static final class StaticHandler implements HttpHandler {
        private final Path root;
        StaticHandler(Path root) { this.root = root; }

        @Override public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                sendText(ex, 405, "Method Not Allowed", "text/plain");
                return;
            }
            String rawPath = ex.getRequestURI().getPath();
            if (rawPath == null || rawPath.isBlank()) rawPath = "/";
            if (rawPath.equals("/")) rawPath = "/index.html";

            // protect against path traversal
            Path target = root.resolve(rawPath.substring(1)).normalize();
            if (!target.startsWith(root)) {
                sendText(ex, 400, "Bad Request", "text/plain");
                return;
            }
            if (!Files.exists(target) || Files.isDirectory(target)) {
                sendText(ex, 404, "Not Found", "text/plain");
                return;
            }

            String ct = contentType(target);
            byte[] data = Files.readAllBytes(target);
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", ct);
            // modest caching for assets
            if (rawPath.startsWith("/img/")) {
                h.set("Cache-Control", "public, max-age=86400");
            } else {
                h.set("Cache-Control", "no-cache");
            }

            if ("HEAD".equalsIgnoreCase(method)) {
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(data);
            }
        }

        private static String contentType(Path p) {
            String name = p.getFileName().toString().toLowerCase();
            if (name.endsWith(".html")) return "text/html; charset=utf-8";
            if (name.endsWith(".css")) return "text/css; charset=utf-8";
            if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (name.endsWith(".json")) return "application/json; charset=utf-8";
            if (name.endsWith(".png")) return "image/png";
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
            if (name.endsWith(".webp")) return "image/webp";
            if (name.endsWith(".gif")) return "image/gif";
            if (name.endsWith(".svg")) return "image/svg+xml";
            if (name.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }

    private static final class JsonHandler implements HttpHandler {
        interface Handler { Object handle(HttpExchange ex, Map<String, Object> body) throws Exception; }
        private final Handler handler;
        JsonHandler(Handler handler) { this.handler = handler; }

        @Override public void handle(HttpExchange ex) throws IOException {
            try {
                // preflight
                if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                    Headers h = ex.getResponseHeaders();
                    h.set("Access-Control-Allow-Origin", "*");
                    h.set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
                    h.set("Access-Control-Allow-Headers", "Content-Type");
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                    return;
                }

                Headers h = ex.getResponseHeaders();
                h.set("Access-Control-Allow-Origin", "*"); // OK for demo; for production, tighten to your domain

                Map<String, Object> body = Map.of();
                if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    String raw = readBody(ex);
                    if (raw != null && !raw.isBlank()) {
                        Object parsed = Json.parse(raw);
                        if (parsed instanceof Map<?, ?> m) body = castObject(m);
                    }
                }

                Object out = handler.handle(ex, body);
                int status = 200;
                if (out instanceof Map<?, ?> mm) {
                    Object st = mm.get("status");
                    if (st instanceof Number n) status = n.intValue();
                }
                sendJson(ex, status, out);
            } catch (Exception e) {
                sendJson(ex, 500, error("Server error: " + e.getMessage(), 500));
            }
        }
    }

    private static Map<String, Object> castObject(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() instanceof String k) out.put(k, e.getValue());
        }
        return out;
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static int intEnv(String key, int def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }

    private static String string(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    private static String safeTrim(String s) {
        return s == null ? null : s.trim();
    }

    private static String urlEncode(String s) {
        // minimal encode for query/path segments used here
        return java.net.URLEncoder.encode(Objects.toString(s, ""), StandardCharsets.UTF_8);
    }
}


