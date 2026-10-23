package com.oshokry.shortener;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

public final class ShortenerService {
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final LinkStore store;
    private final Clock clock;
    private final Supplier<String> codes;

    public ShortenerService(LinkStore store, Clock clock) { this(store, clock, ShortenerService::randomCode); }
    ShortenerService(LinkStore store, Clock clock, Supplier<String> codes) {
        this.store = store; this.clock = clock; this.codes = codes;
    }
    private static String randomCode() {
        StringBuilder code = new StringBuilder(8);
        for (int i = 0; i < 8; i++) code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return code.toString();
    }
    public Link create(String rawUrl, String customCode, String ttlSeconds) throws IOException {
        String url = validateUrl(rawUrl);
        String alias = customCode == null || customCode.isBlank() ? null : customCode.trim();
        if (alias != null && !validCode(alias)) throw new ApiException(400, "Alias must be 4-32 letters, digits, underscores or hyphens");
        Instant now = clock.instant();
        Instant expiry = null;
        if (ttlSeconds != null && !ttlSeconds.isBlank()) {
            try {
                long ttl = Long.parseLong(ttlSeconds);
                if (ttl < 1 || ttl > 31_536_000) throw new NumberFormatException();
                expiry = now.plusSeconds(ttl);
            } catch (NumberFormatException e) { throw new ApiException(400, "ttlSeconds must be an integer between 1 and 31536000"); }
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            Link link = new Link(alias == null ? codes.get() : alias, url, now, expiry, 0, null);
            if (store.create(link)) return link;
            if (alias != null) throw new ApiException(409, "Alias is already taken");
        }
        throw new ApiException(503, "Could not allocate a short code; retry later");
    }
    public java.util.List<Link> list() { return store.list(); }
    public Link stats(String code) {
        if (!validCode(code)) throw new ApiException(404, "Short link not found");
        Link link = store.find(code);
        if (link == null) throw new ApiException(404, "Short link not found");
        return link;
    }
    public Link resolve(String code) throws IOException {
        if (!validCode(code)) throw new ApiException(404, "Short link not found");
        return store.visit(code, clock.instant());
    }
    public boolean expired(Link link) { return link.expired(clock.instant()); }
    public void disable(String code) throws IOException {
        if (!validCode(code)) throw new ApiException(404, "Short link not found");
        store.disable(code);
    }
    public Link enable(String code) throws IOException {
        if (!validCode(code)) throw new ApiException(404, "Short link not found");
        return store.enable(code);
    }
    public Link extend(String code, String ttlSeconds) throws IOException {
        if (!validCode(code)) throw new ApiException(404, "Short link not found");
        try {
            long seconds = Long.parseLong(ttlSeconds);
            if (seconds < 1 || seconds > 31_536_000) throw new NumberFormatException();
            return store.extend(code, seconds, clock.instant());
        } catch (NumberFormatException e) { throw new ApiException(400, "ttlSeconds must be an integer between 1 and 31536000"); }
    }
    public static boolean validCode(String code) { return code != null && code.matches("[A-Za-z0-9_-]{4,32}"); }
    static String validateUrl(String raw) {
        if (raw == null || raw.isBlank()) throw new ApiException(400, "url is required");
        String value = raw.trim();
        if (value.length() > 2048 || value.chars().anyMatch(Character::isISOControl) || value.contains("\\"))
            throw new ApiException(400, "URL contains invalid characters or exceeds 2048 characters");
        try {
            URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() > 65535 || uri.getPort() == 0)
                throw new URISyntaxException(value, "Expected HTTP(S) URL without credentials");
            // Reject encoded CR/LF as defense in depth at the redirect boundary.
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("%0d") || lower.contains("%0a")) throw new URISyntaxException(value, "Encoded newline");
            return uri.toASCIIString();
        } catch (URISyntaxException e) { throw new ApiException(400, "Use an absolute HTTP(S) URL with a host and no credentials"); }
    }
}
