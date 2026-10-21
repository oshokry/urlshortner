package com.oshokry.shortener;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Single-process append-only journal. Force to disk BEFORE publishing a mutation. */
public final class JournalLinkStore implements LinkStore {
    private final Map<String, Link> links = new HashMap<>();
    private final FileChannel channel;
    private final FileLock lock;
    private boolean failed;

    public JournalLinkStore(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Journal is already in use");
            lock = acquired;
            // A crash may leave an unacknowledged partial final record. Discard only that tail.
            long completeLength = channel.size();
            ByteBuffer last = ByteBuffer.allocate(1);
            while (completeLength > 0) {
                last.clear();
                channel.read(last, completeLength - 1);
                if (last.array()[0] == '\n') break;
                completeLength--;
            }
            channel.truncate(completeLength);
            // Windows enforces the lock against other handles, even in this
            // process. Replay through the SAME channel that owns the lock.
            // Do not close this reader: that would close the store's channel.
            channel.position(0);
            BufferedReader reader = new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) replay(line);
            channel.position(completeLength);
        } catch (IOException | RuntimeException e) {
            if (acquired != null) acquired.release();
            channel.close();
            if (e instanceof OverlappingFileLockException) throw new IOException("Journal is already in use", e);
            throw e;
        }
    }

    private void replay(String line) throws IOException {
        try {
            String[] fields = line.split("\t", -1);
            if (fields.length == 5 && fields[0].equals("C")) {
                Link link = new Link(fields[1], new String(Base64.getUrlDecoder().decode(fields[2]), StandardCharsets.UTF_8),
                        Instant.parse(fields[3]), fields[4].isEmpty() ? null : Instant.parse(fields[4]), 0, null);
                if (links.putIfAbsent(link.code(), link) != null) throw new IllegalArgumentException("duplicate code");
            } else if (fields.length == 3 && fields[0].equals("V")) {
                Link link = links.get(fields[1]);
                if (link == null) throw new IllegalArgumentException("visit without link");
                links.put(link.code(), link.clicked(Instant.parse(fields[2])));
            } else throw new IllegalArgumentException("unknown record");
        } catch (RuntimeException e) {
            throw new IOException("Invalid journal record; refusing to serve inconsistent data", e);
        }
    }

    private void append(String record) throws IOException {
        if (failed) throw new IOException("Journal unavailable; restart after checking storage");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(record + "\n");
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (IOException e) {
            failed = true; // Never append after a possibly partial write.
            throw e;
        }
    }

    @Override public synchronized boolean create(Link link) throws IOException {
        if (links.containsKey(link.code())) return false;
        append("C\t" + link.code() + "\t" + Base64.getUrlEncoder().withoutPadding().encodeToString(link.url().getBytes(StandardCharsets.UTF_8))
                + "\t" + link.createdAt() + "\t" + (link.expiresAt() == null ? "" : link.expiresAt()));
        links.put(link.code(), link);
        return true;
    }

    @Override public synchronized Link find(String code) { return links.get(code); }

    @Override public synchronized Link visit(String code, Instant now) throws IOException {
        Link link = links.get(code);
        if (link == null) throw new ApiException(404, "Short link not found");
        if (link.expired(now)) throw new ApiException(410, "Short link has expired");
        Link updated = link.clicked(now);
        append("V\t" + code + "\t" + now);
        links.put(code, updated);
        return updated;
    }

    @Override public synchronized void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
