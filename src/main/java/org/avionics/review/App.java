package org.avionics.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.avionics.review.store.Database;
import org.avionics.review.store.Store;
import org.avionics.review.web.WebServer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for 字路译码台 (ARINC 429 word review bench).
 *
 * <pre>
 * ./gradlew run --args='--host 127.0.0.1 --port 5385'
 * </pre>
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5385;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--host".equals(args[i])) {
                host = args[i + 1];
            }
            if ("--port".equals(args[i])) {
                port = Integer.parseInt(args[i + 1]);
            }
        }
        String dbPath = Store.databasePath(args);
        Path parent = Path.of(dbPath).toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ObjectMapper mapper = new ObjectMapper();
        Database database = new Database(dbPath);
        Store store = new Store(database, mapper);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                database.close();
            } catch (Exception ignore) {
            }
        }));
        WebServer server = new WebServer(mapper, store);
        server.start(host, port);
        System.out.println("ARINC 429 review bench listening on http://" + host + ":" + port);
    }
}
