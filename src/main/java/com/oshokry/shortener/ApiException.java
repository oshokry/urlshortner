package com.oshokry.shortener;

/** Expected errors safe to expose to API consumers. */
public final class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    public ApiException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
