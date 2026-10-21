package com.oshokry.shortener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Thin HTTP adapter; no external HTTP/JSON dependency needed for this demo. */
public final class ShortenerServer implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final ShortenerService service;
    private final String publicBaseUrl;
    private final CreationLimiter limiter = new CreationLimiter();

    public ShortenerServer(InetSocketAddress address, LinkStore store, Clock clock, String publicBaseUrl) throws IOException {
        server = HttpServer.create(address, 64);
        this.service = new ShortenerService(store, clock);
        this.publicBaseUrl = publicBaseUrl == null ? "http://localhost:" + server.getAddress().getPort() : validateBase(publicBaseUrl);
        executor = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), new ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }
    static String validateBase(String base) {
        String clean = ShortenerService.validateUrl(base);
        URI uri = URI.create(clean);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("BASE_URL must be an HTTP(S) origin without a path, query or fragment");
        return clean.endsWith("/") ? clean.substring(0, clean.length() - 1) : clean;
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        int status = 500;
        var headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        try {
            String path = exchange.getRequestURI().getRawPath();
            if (path.equals("/health")) {
                requireMethod(exchange, "GET");
                status = 200;
                send(exchange, status, "application/json", "{\"status\":\"up\"}");
            } else if (path.equals("/api/links")) {
                requireMethod(exchange, "GET", "POST");
                if (exchange.getRequestMethod().equals("GET")) {
                    status = 200;
                    send(exchange, status, "application/json", service.list().stream().map(this::json)
                            .collect(java.util.stream.Collectors.joining(",", "[", "]")));
                    return;
                }
                if (!limiter.allow()) { headers.set("Retry-After", "1"); throw new ApiException(429, "Creation rate exceeded; retry later"); }
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded"))
                    throw new ApiException(415, "Use application/x-www-form-urlencoded");
                byte[] bytes = exchange.getRequestBody().readNBytes(8193);
                if (bytes.length > 8192) throw new ApiException(413, "Request body exceeds 8192 bytes");
                Map<String, String> form = parseForm(new String(bytes, StandardCharsets.UTF_8));
                Link link = service.create(form.get("url"), form.get("alias"), form.get("ttlSeconds"));
                status = 201;
                headers.set("Location", "/api/links/" + link.code());
                send(exchange, status, "application/json", json(link));
            } else if (path.startsWith("/api/links/")) {
                requireMethod(exchange, "GET", "DELETE", "PATCH");
                String code = path.substring("/api/links/".length());
                if (exchange.getRequestMethod().equals("DELETE")) {
                    service.disable(code);
                    status = 204;
                    exchange.sendResponseHeaders(status, -1);
                } else if (exchange.getRequestMethod().equals("PATCH")) {
                    String type = exchange.getRequestHeaders().getFirst("Content-Type");
                    if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded"))
                        throw new ApiException(415, "Use application/x-www-form-urlencoded");
                    byte[] bytes = exchange.getRequestBody().readNBytes(8193);
                    if (bytes.length > 8192) throw new ApiException(413, "Request body exceeds 8192 bytes");
                    Map<String, String> form = parseForm(new String(bytes, StandardCharsets.UTF_8));
                    if (form.size() != 1 || !form.containsKey("ttlSeconds")) throw new ApiException(400, "Provide only ttlSeconds");
                    Link updated = service.extend(code, form.get("ttlSeconds"));
                    status = 200;
                    send(exchange, status, "application/json", json(updated));
                } else {
                    status = 200;
                    send(exchange, status, "application/json", json(service.stats(code)));
                }
            } else if (path.startsWith("/r/")) {
                String method = exchange.getRequestMethod();
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    headers.set("Allow", "GET, HEAD"); throw new ApiException(405, "Method not allowed");
                }
                String code = path.substring(3);
                Link link;
                if (method.equals("HEAD")) {
                    link = service.stats(code);
                    if (link.disabled()) throw new ApiException(410, "Short link has been disabled");
                    if (service.expired(link)) throw new ApiException(410, "Short link has expired");
                } else link = service.resolve(code);
                status = 302;
                headers.set("Location", link.url());
                exchange.sendResponseHeaders(status, -1);
            } else throw new ApiException(404, "Route not found");
        } catch (ApiException e) {
            status = e.status();
            send(exchange, status, "application/json", "{\"error\":" + quote(e.getMessage()) + "}");
        } catch (IOException e) {
            status = 503;
            // Do not log destinations, query parameters or user payloads.
            System.err.println("request_io_failure type=" + e.getClass().getSimpleName());
            send(exchange, status, "application/json", "{\"error\":\"I/O failure; operation may have committed; check before retrying\"}");
        } catch (RuntimeException e) {
            status = 500;
            System.err.println("request_failure type=" + e.getClass().getSimpleName());
            send(exchange, status, "application/json", "{\"error\":\"Internal server error\"}");
        } finally {
            exchange.close();
            System.out.printf("request status=%d duration_ms=%.2f%n", status, (System.nanoTime() - started) / 1_000_000.0);
        }
    }
    private static void requireMethod(HttpExchange e, String... methods) {
        if (!Set.of(methods).contains(e.getRequestMethod())) {
            e.getResponseHeaders().set("Allow", String.join(", ", methods));
            throw new ApiException(405, "Method not allowed");
        }
    }
    static Map<String, String> parseForm(String body) {
        Map<String, String> result = new HashMap<>();
        if (body.isEmpty()) return result;
        try {
            for (String pair : body.split("&", -1)) {
                String[] fields = pair.split("=", 2);
                String key = URLDecoder.decode(fields[0], StandardCharsets.UTF_8);
                String value = fields.length == 2 ? URLDecoder.decode(fields[1], StandardCharsets.UTF_8) : "";
                if (!Set.of("url", "alias", "ttlSeconds").contains(key)) throw new ApiException(400, "Unknown field");
                if (result.putIfAbsent(key, value) != null) throw new ApiException(400, "Duplicate field");
            }
            return result;
        } catch (IllegalArgumentException e) { throw new ApiException(400, "Malformed form encoding"); }
    }
    private String json(Link link) {
        return "{\"code\":" + quote(link.code()) + ",\"shortUrl\":" + quote(publicBaseUrl + "/r/" + link.code())
                + ",\"url\":" + quote(link.url()) + ",\"createdAt\":" + quote(link.createdAt().toString())
                + ",\"expiresAt\":" + quote(link.expiresAt() == null ? null : link.expiresAt().toString())
                + ",\"clicks\":" + link.clicks() + ",\"lastClickedAt\":" + quote(link.lastClickedAt() == null ? null : link.lastClickedAt().toString())
                + ",\"expired\":" + service.expired(link) + ",\"disabled\":" + link.disabled() + "}";
    }
    static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"') out.append('\\').append(c);
            else if (c < 32) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    private static void send(HttpExchange e, int status, String contentType, String body) throws IOException {
        e.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        if (e.getRequestMethod().equals("HEAD")) { e.sendResponseHeaders(status, -1); return; }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.sendResponseHeaders(status, bytes.length);
        e.getResponseBody().write(bytes);
    }
    @Override public void close() {
        server.stop(1);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
    /** Global bounded-memory demo limiter: burst 30, sustained 1 creation/second. */
    static final class CreationLimiter {
        private double tokens = 30;
        private long last = System.nanoTime();
        synchronized boolean allow() {
            long now = System.nanoTime();
            tokens = Math.min(30, tokens + (now - last) / 1_000_000_000.0);
            last = now;
            if (tokens < 1) return false;
            tokens--;
            return true;
        }
    }
}
