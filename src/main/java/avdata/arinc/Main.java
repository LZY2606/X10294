package avdata.arinc;

import java.nio.file.Files;
import java.nio.file.Path;

/** Entry point. Usage: run --host 127.0.0.1 --port 5385 */
public final class Main {

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5385;
        Path dbPath = Path.of("data", "arinc429-review.sqlite");
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host" -> host = args[i + 1];
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--db" -> dbPath = Path.of(args[i + 1]);
                default -> { }
            }
        }
        Files.createDirectories(dbPath.getParent() == null ? Path.of(".") : dbPath.getParent());
        Db db = new Db(dbPath.toString());
        AppService app = new AppService(db);
        Runtime.getRuntime().addShutdownHook(new Thread(db::close));
        new WebServer(app).start(host, port);
        System.out.println("ARINC 429 review console: http://" + host + ":" + port
                + "  (db " + dbPath.toAbsolutePath() + ")");
    }
}
