package avionics.db;

import java.util.List;

/** 状态迁移脚本：v1 基线模式，v2 聚合表+事件基线头，v3 源覆盖表。 */
public final class Migrations {
    private Migrations() {}

    static final String[] V1 = {
        "CREATE TABLE events("
            + "id TEXT PRIMARY KEY, seq INTEGER UNIQUE NOT NULL, type TEXT NOT NULL,"
            + "batch_id TEXT, branch_id TEXT, actor TEXT, payload_json TEXT NOT NULL,"
            + "created_at_ms INTEGER NOT NULL)",
        "CREATE TABLE dictionaries("
            + "dict_id TEXT NOT NULL, version INTEGER NOT NULL, name TEXT NOT NULL,"
            + "payload_json TEXT NOT NULL, event_id TEXT NOT NULL,"
            + "PRIMARY KEY(dict_id, version))",
        "CREATE TABLE batches("
            + "id TEXT PRIMARY KEY, name TEXT NOT NULL, source TEXT NOT NULL,"
            + "dict_version INTEGER NOT NULL, rule_version INTEGER NOT NULL,"
            + "created_event TEXT NOT NULL)",
        "CREATE TABLE words("
            + "id TEXT PRIMARY KEY, batch_id TEXT NOT NULL, seq INTEGER NOT NULL,"
            + "rx_time_us INTEGER NOT NULL, rx_base TEXT NOT NULL, channel TEXT NOT NULL,"
            + "raw_hex TEXT NOT NULL, parity_ok INTEGER NOT NULL, label_octal TEXT NOT NULL,"
            + "sdi INTEGER NOT NULL, ssm INTEGER NOT NULL, data_field INTEGER NOT NULL,"
            + "dict_version INTEGER NOT NULL)",
        "CREATE TABLE branches("
            + "id TEXT PRIMARY KEY, batch_id TEXT NOT NULL, name TEXT NOT NULL,"
            + "parent_id TEXT, created_event TEXT NOT NULL, status TEXT NOT NULL)",
        "CREATE TABLE decisions("
            + "id TEXT PRIMARY KEY, event_id TEXT NOT NULL, branch_id TEXT NOT NULL,"
            + "target_kind TEXT NOT NULL, target_id TEXT NOT NULL,"
            + "verdict TEXT NOT NULL, note TEXT)",
        "CREATE TABLE decoded_values("
            + "id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, word_id TEXT NOT NULL,"
            + "status TEXT NOT NULL, eng_value REAL, unit TEXT, diagnostic TEXT,"
            + "encoding TEXT, dict_version INTEGER NOT NULL, rule_version INTEGER NOT NULL,"
            + "event_id TEXT NOT NULL, candidate_of TEXT)",
        "CREATE TABLE segments("
            + "id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, candidate_group TEXT NOT NULL,"
            + "channel TEXT NOT NULL, label_octal TEXT NOT NULL,"
            + "start_seq INTEGER NOT NULL, end_seq INTEGER NOT NULL, rate_hz REAL NOT NULL,"
            + "reason TEXT NOT NULL, status TEXT NOT NULL, event_id TEXT NOT NULL)",
        "CREATE TABLE branch_heads(branch_id TEXT PRIMARY KEY, head_seq INTEGER NOT NULL)"
    };

    static final String[] V2 = {
        "CREATE TABLE aggregations("
            + "id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, segment_id TEXT NOT NULL,"
            + "mean_value REAL, unit TEXT, n_words INTEGER NOT NULL,"
            + "excluded_json TEXT NOT NULL, rule_version INTEGER NOT NULL,"
            + "dict_version INTEGER NOT NULL, event_id TEXT NOT NULL)",
        "ALTER TABLE events ADD COLUMN base_head INTEGER"
    };

    static final String[] V3 = {
        "CREATE TABLE overrides("
            + "id TEXT PRIMARY KEY, word_id TEXT NOT NULL, field TEXT NOT NULL,"
            + "new_value TEXT NOT NULL, status TEXT NOT NULL, event_id TEXT NOT NULL)"
    };

    public static final List<String[]> ALL = List.of(V1, V2, V3);
    public static final int LATEST = ALL.size();
}
