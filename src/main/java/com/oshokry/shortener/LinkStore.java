package com.oshokry.shortener;

import java.io.IOException;
import java.time.Instant;

/** Store owns atomic uniqueness and atomic click updates. */
public interface LinkStore extends AutoCloseable {
    boolean create(Link link) throws IOException;
    Link find(String code);
    Link visit(String code, Instant now) throws IOException;
    void disable(String code) throws IOException;
    Link extend(String code, long seconds, Instant now) throws IOException;
    @Override void close() throws IOException;
}
