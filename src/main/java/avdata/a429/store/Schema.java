package avdata.a429.store;

/** SQLite schema. Versioned with user_version; migrations live beside the baseline. */
public final class Schema {
    private Schema() {}

    public static final int VERSION = 2;

    public static final String DDL = """
        PRAGMA journal_mode = WAL;

        CREATE TABLE IF NOT EXISTS meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        -- channel configurations, immutable per version
        CREATE TABLE IF NOT EXISTS channel_config (
            version_id TEXT PRIMARY KEY,
            label_convention TEXT NOT NULL,
            note TEXT NOT NULL DEFAULT '',
            created_at_us INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS dictionary (
            version_id TEXT PRIMARY KEY,
            created_at_us INTEGER NOT NULL,
            payload_json TEXT NOT NULL
        );

        -- each import batch; failure rolls the whole batch back
        CREATE TABLE IF NOT EXISTS import_batch (
            batch_id TEXT PRIMARY KEY,
            imported_at_us INTEGER NOT NULL,
            source_name TEXT NOT NULL,
            channel TEXT NOT NULL,
            dict_version TEXT NOT NULL,
            config_version TEXT NOT NULL,
            note TEXT NOT NULL DEFAULT ''
        );

        -- raw words: source of truth, never rewritten by interpretation
        CREATE TABLE IF NOT EXISTS raw_word (
            word_id TEXT PRIMARY KEY,
            batch_id TEXT NOT NULL REFERENCES import_batch(batch_id),
            seq INTEGER NOT NULL,
            received_at_us INTEGER NOT NULL,
            channel TEXT NOT NULL,
            raw_bits INTEGER NOT NULL,
            raw_repr TEXT NOT NULL,
            UNIQUE(batch_id, seq)
        );

        CREATE TABLE IF NOT EXISTS branch (
            branch_id TEXT PRIMARY KEY,
            parent_branch_id TEXT,
            name TEXT NOT NULL,
            created_at_us INTEGER NOT NULL,
            tip_event_id TEXT
        );

        -- append-only event log
        CREATE TABLE IF NOT EXISTS event (
            event_id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL REFERENCES branch(branch_id),
            seq INTEGER NOT NULL,
            parent_event_id TEXT,
            kind TEXT NOT NULL,
            occurred_at_us INTEGER NOT NULL,
            actor TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            rationale TEXT NOT NULL DEFAULT '',
            UNIQUE(branch_id, seq)
        );

        -- dependency edges used to compute invalidation scope
        CREATE TABLE IF NOT EXISTS event_dep (
            event_id TEXT NOT NULL REFERENCES event(event_id),
            dep_type TEXT NOT NULL,   -- WORD | DICT | CONFIG | BATCH | EVENT
            dep_ref TEXT NOT NULL,
            PRIMARY KEY(event_id, dep_type, dep_ref)
        );

        -- derived artifacts keyed by stable identity, computed per branch event
        CREATE TABLE IF NOT EXISTS artifact (
            artifact_id TEXT PRIMARY KEY,
            identity TEXT NOT NULL,
            branch_id TEXT NOT NULL,
            produced_by_event TEXT NOT NULL,
            invalidated_by_event TEXT,
            kind TEXT NOT NULL,
            status TEXT NOT NULL,    -- ACTIVE | SUPERSEDED | REJECTED | INVALID
            target_ref TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            UNIQUE(identity, branch_id, produced_by_event)
        );

        -- human decisions, optimistic lock on the branch tip
        CREATE TABLE IF NOT EXISTS decision (
            decision_id TEXT PRIMARY KEY,
            event_id TEXT NOT NULL UNIQUE,
            branch_id TEXT NOT NULL,
            action TEXT NOT NULL,    -- ACCEPT | REJECT | ADJUST
            target_identity TEXT NOT NULL,
            base_event_id TEXT NOT NULL,
            params_json TEXT NOT NULL DEFAULT '{}',
            undone_by_event_id TEXT
        );

        CREATE INDEX IF NOT EXISTS idx_word_batch ON raw_word(batch_id);
        CREATE INDEX IF NOT EXISTS idx_event_branch ON event(branch_id, seq);
        CREATE INDEX IF NOT EXISTS idx_dep_ref ON event_dep(dep_type, dep_ref);
        CREATE INDEX IF NOT EXISTS idx_art_identity ON artifact(identity, branch_id, status);
        CREATE INDEX IF NOT EXISTS idx_art_target ON artifact(target_ref);
        """;
}
