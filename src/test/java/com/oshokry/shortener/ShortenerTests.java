package com.oshokry.shortener;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free unit/integration regression suite. Failed checks throw even without -ea. */
public final class ShortenerTests {
    private static int passed;
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private ShortenerTests() { }
    @FunctionalInterface interface Test { void run() throws Exception; }
    private static void test(String name, Test test) throws Exception {
        test.run(); passed++; System.out.println("PASS " + name);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void error(int status, Test action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + status); }
        catch (ApiException e) { check(e.status() == status, "Wrong status: " + e.status()); }
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("shortener-tests-");
        try {
            runSuite(directory);
            System.out.println("\nALL " + passed + " TESTS PASSED");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static void runSuite(Path dir) throws Exception {
        Path journal = dir.resolve("links.journal");
        try (JournalLinkStore store = new JournalLinkStore(journal)) {
            ShortenerService service = new ShortenerService(store, CLOCK);
            test("create generated link", () -> {
                Link link = service.create("https://example.com/path?q=one&other=two", null, null);
                check(link.code().matches("[A-Za-z0-9]{8}"), "8-character code");
                check(link.clicks() == 0 && link.expiresAt() == null, "Defaults");
            });
            test("custom alias and duplicate conflict", () -> {
                service.create("https://example.com", "demo", null);
                error(409, () -> service.create("https://other.example", "demo", null));
                check(service.stats("demo").url().equals("https://example.com"), "Original survives conflict");
            });
            test("invalid destinations rejected", () -> {
                for (String url : List.of("javascript:alert(1)", "file:///etc/passwd", "/relative", "https://u:p@example.com", "https://", "https://example.com/%0d%0aLocation:x", "https://example.com/\r\nx", "http://example.com:99999", "http://example.com:0"))
                    error(400, () -> service.create(url, null, null));
            });
            test("blank and oversized URL rejected", () -> {
                error(400, () -> service.create(null, null, null));
                error(400, () -> service.create(" ", null, null));
                error(400, () -> service.create("https://example.com/" + "a".repeat(2048), null, null));
            });
            test("invalid aliases rejected", () -> {
                for (String alias : List.of("abc", "a/bc", "a bcd", "x".repeat(33))) error(400, () -> service.create("https://example.com", alias, null));
            });
            test("invalid TTL rejected", () -> {
                for (String ttl : List.of("0", "-1", "3.5", "abc", "31536001", "999999999999999999999"))
                    error(400, () -> service.create("https://example.com", null, ttl));
            });
            test("unknown code is 404", () -> { error(404, () -> service.resolve("nope")); error(404, () -> service.stats("../x")); });
            test("expiry exact boundary is 410 and does not count", () -> {
                service.create("https://example.com", "expiry", "60");
                var before = new ShortenerService(store, Clock.fixed(NOW.plusSeconds(59), ZoneOffset.UTC));
                before.resolve("expiry");
                var at = new ShortenerService(store, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
                error(410, () -> at.resolve("expiry"));
                check(at.stats("expiry").clicks() == 1 && at.expired(at.stats("expiry")), "Expiry does not add clicks");
                error(409, () -> at.create("https://other.example", "expiry", null));
            });
            test("collision retries preserve old link", () -> {
                AtomicInteger attempts = new AtomicInteger();
                var collision = new ShortenerService(store, CLOCK, () -> attempts.getAndIncrement() == 0 ? "demo" : "newcode1");
                check(collision.create("https://example.org", null, null).code().equals("newcode1"), "Retry succeeds");
            });
            test("collision retry budget is bounded", () -> error(503, () -> new ShortenerService(store, CLOCK, () -> "demo").create("https://example.org", null, null)));
            test("concurrent alias creation has exactly one winner", () -> {
                var pool = Executors.newFixedThreadPool(8);
                try {
                    List<Callable<Boolean>> tasks = new ArrayList<>();
                    for (int i = 0; i < 24; i++) tasks.add(() -> {
                        try { service.create("https://example.com", "race", null); return true; }
                        catch (ApiException e) { check(e.status() == 409, "Expected conflict"); return false; }
                    });
                    int winners = 0;
                    for (var result : pool.invokeAll(tasks)) if (result.get()) winners++;
                    check(winners == 1, "Exactly one winner");
                } finally { pool.shutdownNow(); }
            });
            test("100 concurrent clicks are counted without lost updates", () -> {
                var pool = Executors.newFixedThreadPool(8);
                try {
                    List<Callable<Link>> tasks = new ArrayList<>();
                    for (int i = 0; i < 100; i++) tasks.add(() -> service.resolve("demo"));
                    for (var result : pool.invokeAll(tasks)) result.get();
                    check(service.stats("demo").clicks() == 100, "No lost updates");
                } finally { pool.shutdownNow(); }
            });
            test("second writer cannot open same journal", () -> {
                try (JournalLinkStore second = new JournalLinkStore(journal)) { second.find("demo"); throw new AssertionError("Second writer opened"); }
                catch (IOException expected) { check(expected.getMessage().contains("in use"), "Clear lock error"); }
            });
        }
        test("links and analytics survive reopen", () -> {
            try (JournalLinkStore reopened = new JournalLinkStore(journal)) {
                check(reopened.find("demo").clicks() == 100, "Clicks persisted");
                check(reopened.find("expiry").expiresAt().equals(NOW.plusSeconds(60)), "Expiry persisted");
            }
        });
        test("partial final record discarded after crash", () -> {
            Files.writeString(journal, "V\tdemo\tpartial", StandardOpenOption.APPEND);
            try (JournalLinkStore reopened = new JournalLinkStore(journal)) {
                check(reopened.find("demo").clicks() == 100, "Committed records preserved");
                reopened.visit("demo", NOW);
            }
            try (JournalLinkStore reopened = new JournalLinkStore(journal)) { check(reopened.find("demo").clicks() == 101, "Clean append after recovery"); }
        });
        test("complete corrupt record fails startup", () -> {
            Path corrupt = dir.resolve("corrupt.journal"); Files.writeString(corrupt, "garbage\n");
            try (JournalLinkStore bad = new JournalLinkStore(corrupt)) { bad.find("demo"); throw new AssertionError("Accepted corrupt journal"); }
            catch (IOException expected) { check(expected.getMessage().contains("Invalid journal"), "Fail closed"); }
        });
        test("failed storage mutation is not acknowledged", () -> {
            LinkStore broken = new LinkStore() {
                public boolean create(Link link) throws IOException { throw new IOException("disk full"); }
                public Link find(String code) { return null; }
                public Link visit(String code, Instant now) throws IOException { throw new IOException("disk full"); }
                public void close() { }
            };
            try { new ShortenerService(broken, CLOCK).create("https://example.com", null, null); throw new AssertionError("Acknowledged failed write"); }
            catch (IOException expected) { check(expected.getMessage().equals("disk full"), "Storage failure propagated"); }
        });
        test("form decoding rejects ambiguity and malformed encoding", () -> {
            error(400, () -> ShortenerServer.parseForm("url=x&url=y"));
            error(400, () -> ShortenerServer.parseForm("url=%ZZ"));
            error(400, () -> ShortenerServer.parseForm("unexpected=value"));
            check(ShortenerServer.parseForm("url=https%3A%2F%2Fexample.com%2F%3Fa%3Db%26c%3Dd").get("url").equals("https://example.com/?a=b&c=d"), "Query survives encoding");
        });
        test("JSON response escaping", () -> check(ShortenerServer.quote("a\"b\\c\n").equals("\"a\\\"b\\\\c\\u000a\""), "JSON strings escaped"));
        httpTests(dir);
    }
    private static void httpTests(Path dir) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();
        try (JournalLinkStore store = new JournalLinkStore(dir.resolve("http.journal"));
             ShortenerServer server = new ShortenerServer(new InetSocketAddress("127.0.0.1", 0), store, CLOCK, null)) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            test("HTTP health and UI", () -> {
                check(get(client, base + "/health").statusCode() == 200, "Health");
                check(get(client, base).body().contains("Create a short link"), "UI");
                check(get(client, base + "/app.js").statusCode() == 200, "Script");
                check(get(client, base + "/style.css").statusCode() == 200, "Styles");
            });
            test("HTTP create returns 201 and resource Location", () -> {
                var response = post(client, base, "url=https%3A%2F%2Fexample.com%2F%3Fa%3D1%26b%3D2&alias=webdemo", "application/x-www-form-urlencoded");
                check(response.statusCode() == 201, "Created: " + response.body());
                check(response.headers().firstValue("Location").orElse("").equals("/api/links/webdemo"), "Resource Location");
                check(response.body().contains("\"clicks\":0"), "Initial clicks");
            });
            test("HEAD redirect does not count", () -> {
                var response = client.send(HttpRequest.newBuilder(URI.create(base + "/r/webdemo")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                check(response.statusCode() == 302, "HEAD redirect");
                check(store.find("webdemo").clicks() == 0, "HEAD uncounted");
            });
            test("HTTP redirect target, no cache and analytics", () -> {
                var response = get(client, base + "/r/webdemo");
                check(response.statusCode() == 302, "Redirect");
                check(response.headers().firstValue("Location").orElse("").equals("https://example.com/?a=1&b=2"), "Exact target");
                check(response.headers().firstValue("Cache-Control").orElse("").equals("no-store"), "Do not bypass analytics with cached redirect");
                check(get(client, base + "/api/links/webdemo").body().contains("\"clicks\":1"), "Click counted");
            });
            test("HTTP duplicate, missing and wrong method", () -> {
                check(post(client, base, "url=https%3A%2F%2Fexample.com&alias=webdemo", "application/x-www-form-urlencoded").statusCode() == 409, "Duplicate");
                check(get(client, base + "/r/missing").statusCode() == 404, "Missing");
                var response = get(client, base + "/api/links");
                check(response.statusCode() == 405 && response.headers().firstValue("Allow").orElse("").equals("POST"), "Method contract");
            });
            test("HTTP expiry returns 410 without click", () -> {
                store.create(new Link("expired", "https://example.com", NOW.minusSeconds(60), NOW, 0, null));
                check(get(client, base + "/r/expired").statusCode() == 410, "Gone");
                check(store.find("expired").clicks() == 0, "No click");
            });
            test("HTTP payload and media type guardrails", () -> {
                check(post(client, base, "{}", "application/json").statusCode() == 415, "Media type");
                check(post(client, base, "url=" + "a".repeat(8192), "application/x-www-form-urlencoded").statusCode() == 413, "Body limit");
                check(post(client, base, "url=javascript%3Aalert%281%29", "application/x-www-form-urlencoded").statusCode() == 400, "Invalid URL");
            });
            test("HTTP creation rate limiter returns 429", () -> {
                boolean limited = false;
                for (int i = 0; i < 40; i++) {
                    var response = post(client, base, "url=https%3A%2F%2Fexample.com", "application/x-www-form-urlencoded");
                    if (response.statusCode() == 429) {
                        check(response.headers().firstValue("Retry-After").orElse("").equals("1"), "Retry hint"); limited = true; break;
                    }
                }
                check(limited, "Rate limit enforced");
            });
        }
    }
    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> post(HttpClient client, String base, String body, String type) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/api/links")).timeout(Duration.ofSeconds(5)).header("Content-Type", type)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
