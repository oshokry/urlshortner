package com.oshokry.shortener;

import java.time.Instant;

/** Immutable snapshot: readers never observe partially updated analytics. */
public record Link(String code, String url, Instant createdAt, Instant expiresAt,
                   long clicks, Instant lastClickedAt, boolean disabled) {
    public Link(String code, String url, Instant createdAt, Instant expiresAt, long clicks, Instant lastClickedAt) {
        this(code, url, createdAt, expiresAt, clicks, lastClickedAt, false);
    }
    public Link disable() {
        return new Link(code, url, createdAt, expiresAt, clicks, lastClickedAt, true);
    }
    public boolean expired(Instant now) { return expiresAt != null && !now.isBefore(expiresAt); }
    public Link clicked(Instant now) {
        return new Link(code, url, createdAt, expiresAt, Math.addExact(clicks, 1), now, disabled);
    }
}
