package avionics.arinc429;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/** 状态迁移测试：v1 库迁移到 v2，列追加且历史行保留。 */
class MigrationTest {

    @Test
    void v1ToV2PreservesData() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var st = c.createStatement()) {
                for (String sql : Db.MIGRATIONS.get(0)) st.execute(sql);
                st.execute("INSERT INTO schema_migrations(version, applied_at) VALUES(1, 't0')");
                st.execute("INSERT INTO events(id,seq,ts,type,branch_id,payload) "
                        + "VALUES('ev-1',1,'t','import',NULL,'{}')");
                st.execute("INSERT INTO decisions(id,branch_id,target_type,target_id,action,"
                        + "base_version,payload,status,created_at) "
                        + "VALUES('dec-1','br','rate-candidate','A-s0-c0','accept',0,'{}','applied','t')");
            }
            Db.migrate(c);
            assertEquals(2, Db.version(c));
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SELECT supersedes FROM events WHERE id='ev-1'")) {
                assertTrue(rs.next()); // 历史行保留，新列存在
                assertNull(rs.getString(1));
            }
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SELECT undone FROM decisions WHERE id='dec-1'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
            // 幂等：再迁移一次不出错
            Db.migrate(c);
            assertEquals(2, Db.version(c));
        }
    }
}
