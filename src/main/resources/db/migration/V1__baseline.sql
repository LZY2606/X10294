CREATE TABLE schema_meta (
    version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL
);

CREATE TABLE cases (
    case_id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    created_at TEXT NOT NULL,
    pack_path TEXT
);

CREATE TABLE branches (
    branch_id TEXT NOT NULL,
    case_id TEXT NOT NULL,
    parent_branch_id TEXT,
    name TEXT NOT NULL,
    created_event_seq INTEGER NOT NULL,
    PRIMARY KEY (case_id, branch_id)
);

CREATE TABLE events (
    event_id TEXT NOT NULL,
    case_id TEXT NOT NULL,
    branch_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    parent_event_id TEXT,
    type TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    depends_json TEXT NOT NULL,
    invalidates_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    revision INTEGER NOT NULL,
    PRIMARY KEY (case_id, branch_id, seq)
);

CREATE TABLE raw_words (
    word_id TEXT NOT NULL,
    case_id TEXT NOT NULL,
    fragment_id TEXT NOT NULL,
    channel_id TEXT NOT NULL,
    position INTEGER NOT NULL,
    received_at_ns INTEGER NOT NULL,
    received_at_iso TEXT NOT NULL,
    wire_hex TEXT NOT NULL,
    rev INTEGER NOT NULL DEFAULT 0,
    superseded_by TEXT,
    PRIMARY KEY (case_id, word_id)
);

CREATE INDEX idx_events_case ON events (case_id, branch_id, seq);
CREATE INDEX idx_words_case ON raw_words (case_id, channel_id, position);
