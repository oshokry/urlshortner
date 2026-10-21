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
                public void disable(String code) throws IOException { throw new IOException("disk full"); }
                public Link extend(String code, long seconds, Instant now) throws IOException { throw new IOException("disk full"); }
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
        deactivationTests(dir);
        expiryExtensionTests(dir);
        httpTests(dir);
    }
    private static void expiryExtensionTests(Path dir) throws Exception {
        Path journal = dir.resolve("extensions.journal");
        try (JournalLinkStore store = new JournalLinkStore(journal)) {
            ShortenerService service = new ShortenerService(store, CLOCK);
            test("extend active expiry without changing link identity or analytics", () -> {
                Link created = service.create("https://example.com/path", "extend-demo", "60");
                service.resolve(created.code());
                Link extended = service.extend(created.code(), "120");
                check(extended.expiresAt().equals(NOW.plusSeconds(180)), "Added to current expiry");
                check(extended.code().equals(created.code()) && extended.url().equals(created.url()), "Identity unchanged");
                check(extended.createdAt().equals(created.createdAt()) && extended.clicks() == 1 && extended.lastClickedAt().equals(NOW), "History retained");
                var afterOldExpiry = new ShortenerService(store, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
                afterOldExpiry.resolve(created.code());
                var atNewExpiry = new ShortenerService(store, Clock.fixed(NOW.plusSeconds(180), ZoneOffset.UTC));
                error(410, () -> atNewExpiry.resolve(created.code()));
            });
            test("renew expired link and exact-boundary expiry from now", () -> {
                service.create("https://example.com", "expired-extend", "1");
                var later = new ShortenerService(store, Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
                check(later.extend("expired-extend", "60").expiresAt().equals(NOW.plusSeconds(70)), "Expired renewed from now");
                service.create("https://example.com", "boundary-extend", "10");
                check(later.extend("boundary-extend", "60").expiresAt().equals(NOW.plusSeconds(70)), "Exact boundary renewed");
                later.resolve("expired-extend");
            });
            test("reject invalid extension durations without appending", () -> {
                long size = Files.size(journal);
                for (String value : List.of("", " ", "0", "-1", "1.5", "abc", "31536001", "999999999999999999999"))
                    error(400, () -> service.extend("extend-demo", value));
                error(400, () -> service.extend("extend-demo", null));
                check(Files.size(journal) == size, "Invalid requests do not write");
            });
            test("extension enforces minimum and maximum durations", () -> {
                service.create("https://example.com", "range-extend", "1");
                check(service.extend("range-extend", "1").expiresAt().equals(NOW.plusSeconds(2)), "Minimum");
                check(service.extend("range-extend", "31536000").expiresAt().equals(NOW.plusSeconds(31_536_002)), "Maximum");
            });
            test("reject missing, malformed, disabled and non-expiring links", () -> {
                error(404, () -> service.extend("missing", "60"));
                error(404, () -> service.extend("../bad", "60"));
                service.create("https://example.com", "forever-extend", null);
                error(409, () -> service.extend("forever-extend", "60"));
                service.create("https://example.com", "disabled-extend", "60");
                service.disable("disabled-extend");
                error(410, () -> service.extend("disabled-extend", "60"));
                check(service.stats("disabled-extend").disabled(), "Cannot reactivate disabled links");
                check(service.stats("forever-extend").expiresAt() == null, "Never expiry retained");
            });
            test("concurrent extensions do not lose updates", () -> {
                service.create("https://example.com", "extend-race", "60");
                var pool = Executors.newFixedThreadPool(8);
                try {
                    List<Callable<Link>> tasks = new ArrayList<>();
                    for (int i = 0; i < 20; i++) tasks.add(() -> service.extend("extend-race", "60"));
                    for (var result : pool.invokeAll(tasks)) result.get();
                    check(service.stats("extend-race").expiresAt().equals(NOW.plusSeconds(1260)), "All additions retained");
                } finally { pool.shutdownNow(); }
            });
        }
        test("expiry extensions and later disable survive replay", () -> {
            try (JournalLinkStore store = new JournalLinkStore(journal)) {
                check(store.find("extend-demo").expiresAt().equals(NOW.plusSeconds(180)), "Expiry persisted");
                check(store.find("extend-demo").clicks() == 2, "Visits preserved across extension");
                check(store.find("extend-race").expiresAt().equals(NOW.plusSeconds(1260)), "All events replayed");
                store.disable("extend-demo");
            }
            try (JournalLinkStore store = new JournalLinkStore(journal)) {
                check(store.find("extend-demo").disabled() && store.find("extend-demo").expiresAt().equals(NOW.plusSeconds(180)), "Later disable retained");
            }
        });
        test("failed extension write leaves old expiry intact", () -> {
            JournalLinkStore store = new JournalLinkStore(dir.resolve("extend-failure.journal"));
            ShortenerService service = new ShortenerService(store, CLOCK);
            service.create("https://example.com", "failure", "60");
            store.close();
            try { service.extend("failure", "60"); throw new AssertionError("Acknowledged failed extension"); }
            catch (IOException expected) { check(service.stats("failure").expiresAt().equals(NOW.plusSeconds(60)), "No in-memory update"); }
        });
        test("orphan and non-increasing expiry events fail startup", () -> {
            Path corrupt = dir.resolve("bad-extension.journal");
            Files.writeString(corrupt, "E\tmissing\t" + NOW + "\n");
            try (JournalLinkStore store = new JournalLinkStore(corrupt)) { store.find("missing"); throw new AssertionError("Accepted orphan"); }
            catch (IOException expected) { check(expected.getMessage().contains("Invalid journal"), "Orphan rejected"); }
            Path decrease = dir.resolve("decreasing-expiry.journal");
            try (JournalLinkStore store = new JournalLinkStore(decrease)) {
                new ShortenerService(store, CLOCK).create("https://example.com", "decrease", "60");
            }
            Files.writeString(decrease, "E\tdecrease\t" + NOW + "\n", StandardOpenOption.APPEND);
            try (JournalLinkStore store = new JournalLinkStore(decrease)) { store.find("decrease"); throw new AssertionError("Accepted shrinking expiry"); }
            catch (IOException expected) { check(expected.getMessage().contains("Invalid journal"), "Decreasing expiry rejected"); }
        });
    }
    private static void deactivationTests(Path dir) throws Exception {
        Path journal = dir.resolve("disable.journal");
        try (JournalLinkStore store = new JournalLinkStore(journal)) {
            ShortenerService service = new ShortenerService(store, CLOCK);
            test("disable preserves metadata, clicks and reserved alias", () -> {
                Link original = service.create("https://example.com", "disable-demo", "60");
                service.resolve(original.code());
                service.disable(original.code());
                Link disabled = service.stats(original.code());
                check(disabled.disabled() && !disabled.expired(NOW), "Disabled is distinct from expired");
                check(disabled.clicks() == 1 && disabled.lastClickedAt().equals(NOW), "Analytics retained");
                check(disabled.url().equals(original.url()) && disabled.expiresAt().equals(original.expiresAt()), "Metadata retained");
                error(410, () -> service.resolve(original.code()));
                check(service.stats(original.code()).clicks() == 1, "Blocked visit not counted");
                error(409, () -> service.create("https://other.example", original.code(), null));
            });
            test("repeated disable is idempotent without extra journal records", () -> {
                long size = Files.size(journal);
                service.disable("disable-demo");
                check(Files.size(journal) == size, "No duplicate event");
            });
            test("disable rejects missing and malformed codes", () -> {
                error(404, () -> service.disable("missing"));
                error(404, () -> service.disable("../bad"));
                error(404, () -> service.disable(null));
            });
            test("expired link can also be disabled", () -> {
                service.create("https://example.com", "old-link", "1");
                var later = new ShortenerService(store, Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
                later.disable("old-link");
                check(later.stats("old-link").disabled() && later.expired(later.stats("old-link")), "Both states preserved");
            });
            test("concurrent disable and visits have consistent analytics", () -> {
                service.create("https://example.com", "disable-race", null);
                var pool = Executors.newFixedThreadPool(8);
                AtomicInteger visits = new AtomicInteger();
                try {
                    List<Callable<Void>> tasks = new ArrayList<>();
                    for (int i = 0; i < 50; i++) {
                        if (i == 20) tasks.add(() -> { service.disable("disable-race"); return null; });
                        tasks.add(() -> {
                            try { service.resolve("disable-race"); visits.incrementAndGet(); }
                            catch (ApiException e) { check(e.status() == 410, "Only disabled visits fail"); }
                            return null;
                        });
                    }
                    for (var result : pool.invokeAll(tasks)) result.get();
                    check(service.stats("disable-race").disabled(), "Disable committed");
                    check(service.stats("disable-race").clicks() == visits.get(), "Count exactly successful visits");
                    error(410, () -> service.resolve("disable-race"));
                } finally { pool.shutdownNow(); }
            });
        }
        test("disabled status and analytics survive journal replay", () -> {
            try (JournalLinkStore store = new JournalLinkStore(journal)) {
                ShortenerService service = new ShortenerService(store, CLOCK);
                check(service.stats("disable-demo").disabled() && service.stats("disable-demo").clicks() == 1, "Replay retains state");
                error(410, () -> service.resolve("disable-demo"));
                service.disable("disable-demo");
            }
        });
        test("failed disable write does not publish disabled state", () -> {
            JournalLinkStore store = new JournalLinkStore(dir.resolve("disable-failure.journal"));
            ShortenerService service = new ShortenerService(store, CLOCK);
            service.create("https://example.com", "failure", null);
            store.close();
            try { service.disable("failure"); throw new AssertionError("Acknowledged failed disable"); }
            catch (IOException expected) { check(!service.stats("failure").disabled(), "State unchanged after failed append"); }
        });
        test("orphan disable event fails startup", () -> {
            Path corrupt = dir.resolve("orphan-disable.journal");
            Files.writeString(corrupt, "D\tmissing\n");
            try (JournalLinkStore store = new JournalLinkStore(corrupt)) { store.find("missing"); throw new AssertionError("Accepted orphan event"); }
            catch (IOException expected) { check(expected.getMessage().contains("Invalid journal"), "Fail closed"); }
        });
    }
    private static void httpTests(Path dir) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();
        try (JournalLinkStore store = new JournalLinkStore(dir.resolve("http.journal"));
             ShortenerServer server = new ShortenerServer(new InetSocketAddress("127.0.0.1", 0), store, CLOCK, null)) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            test("HTTP health and backend-only routes", () -> {
                check(get(client, base + "/health").statusCode() == 200, "Health");
                check(get(client, base).statusCode() == 404, "UI belongs to React frontend");
                check(get(client, base + "/app.js").statusCode() == 404, "No embedded script");
                check(get(client, base + "/style.css").statusCode() == 404, "No embedded stylesheet");
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
            test("HTTP DELETE returns empty 204 and is idempotent", () -> {
                for (int i = 0; i < 2; i++) {
                    var response = method(client, base + "/api/links/webdemo", "DELETE");
                    check(response.statusCode() == 204 && response.body().isEmpty(), "Empty idempotent response");
                }
            });
            test("HTTP disabled GET and HEAD return 410 without redirect or clicks", () -> {
                for (String method : List.of("GET", "HEAD")) {
                    var response = method(client, base + "/r/webdemo", method);
                    check(response.statusCode() == 410 && response.headers().firstValue("Location").isEmpty(), "No redirect");
                    if (method.equals("HEAD")) check(response.body().isEmpty(), "No HEAD body");
                }
                check(store.find("webdemo").clicks() == 1, "No disabled clicks");
            });
            test("HTTP disabled metadata stays readable", () -> {
                var response = get(client, base + "/api/links/webdemo");
                check(response.statusCode() == 200 && response.body().contains("\"disabled\":true"), "Disabled field");
                check(response.body().contains("\"clicks\":1") && response.body().contains("\"expired\":false"), "History and expiry unchanged");
            });
            test("HTTP disable missing code and method contract", () -> {
                check(method(client, base + "/api/links/nope", "DELETE").statusCode() == 404, "Missing link");
                var response = method(client, base + "/api/links/webdemo", "PUT");
                check(response.statusCode() == 405 && response.headers().firstValue("Allow").orElse("").equals("GET, DELETE, PATCH"), "Allowed methods");
            });
            test("HTTP PATCH extends expiry and returns updated metadata", () -> {
                store.create(new Link("patch-demo", "https://example.com/path", NOW, NOW.plusSeconds(60), 0, null));
                var response = patch(client, base, "patch-demo", "ttlSeconds=3600", "application/x-www-form-urlencoded");
                check(response.statusCode() == 200 && response.body().contains(NOW.plusSeconds(3660).toString()), "Updated expiry");
                check(response.body().contains("\"code\":\"patch-demo\"") && response.body().contains("\"clicks\":0"), "Metadata retained");
            });
            test("HTTP PATCH renews expired link and blocks disabled or forever links", () -> {
                check(patch(client, base, "expired", "ttlSeconds=60", "application/x-www-form-urlencoded").statusCode() == 200, "Renewed");
                check(get(client, base + "/r/expired").statusCode() == 302, "Renewed redirect works");
                check(patch(client, base, "webdemo", "ttlSeconds=60", "application/x-www-form-urlencoded").statusCode() == 410, "Disabled stays disabled");
                store.create(new Link("forever-http", "https://example.com", NOW, null, 0, null));
                check(patch(client, base, "forever-http", "ttlSeconds=60", "application/x-www-form-urlencoded").statusCode() == 409, "Forever unchanged");
            });
            test("HTTP PATCH rejects invalid forms and missing links", () -> {
                for (String body : List.of("", "ttlSeconds=0", "ttlSeconds=abc", "ttlSeconds=60&alias=other", "url=https%3A%2F%2Fexample.com", "ttlSeconds=60&ttlSeconds=120", "ttlSeconds=%ZZ"))
                    check(patch(client, base, "patch-demo", body, "application/x-www-form-urlencoded").statusCode() == 400, "Invalid form rejected");
                check(patch(client, base, "nope", "ttlSeconds=60", "application/x-www-form-urlencoded").statusCode() == 404, "Missing");
                check(store.find("patch-demo").expiresAt().equals(NOW.plusSeconds(3660)), "No mutation");
            });
            test("HTTP PATCH enforces media type and body limit", () -> {
                check(patch(client, base, "patch-demo", "{}", "application/json").statusCode() == 415, "Media type");
                check(patch(client, base, "patch-demo", "ttlSeconds=" + "1".repeat(8192), "application/x-www-form-urlencoded").statusCode() == 413, "Body limit");
            });
        }
    }
    private static HttpResponse<String> patch(HttpClient client, String base, String code, String body, String type) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/api/links/" + code)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", type).method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> method(HttpClient client, String url, String method) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> post(HttpClient client, String base, String body, String type) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/api/links")).timeout(Duration.ofSeconds(5)).header("Content-Type", type)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
