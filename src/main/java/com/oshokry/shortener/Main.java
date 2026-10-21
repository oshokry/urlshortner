package com.oshokry.shortener;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;

public final class Main {
    private Main() { }
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String host = System.getenv().getOrDefault("HOST", "127.0.0.1");
        Path journal = Path.of(System.getenv().getOrDefault("DATA_FILE", "data/links.journal"));
        JournalLinkStore store = new JournalLinkStore(journal);
        ShortenerServer server;
        try {
            server = new ShortenerServer(new InetSocketAddress(host, port), store, Clock.systemUTC(), System.getenv("BASE_URL"));
        } catch (Exception e) { store.close(); throw e; }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            try { store.close(); } catch (Exception e) { System.err.println("Journal close failed"); }
        }));
        server.start();
        System.out.println("URL shortener ready: http://localhost:" + server.port());
        System.out.println("Press Ctrl+C to stop. Links and clicks survive restart.");
    }
}
