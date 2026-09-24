package avionics.arinc429;

import java.nio.file.Path;
import java.sql.Connection;

/** 入口：./gradlew run --args='--host 127.0.0.1 --port 5385' */
public final class App {

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5385;
        String db = "data/arinc429.db";
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--host" -> host = args[i + 1];
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--db" -> db = args[i + 1];
                default -> { }
            }
        }
        Connection conn = Db.open(db);
        Store store = new Store(conn);
        Web web = new Web(store, Path.of("data", "export-snapshot.json"));
        web.create().start(host, port);
        System.out.println("ARINC 429 字解析/链路速率审阅工具: http://" + host + ":" + port);
    }
}
