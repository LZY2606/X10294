-- V1 baseline: source data, dictionary/params revisions, branches and append-only events.
CREATE TABLE schema_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE channel_config (
    channel TEXT PRIMARY KEY,
    bus TEXT NOT NULL,
    expected_labels_json TEXT NOT NULL
);

CREATE TABLE dictionary_rev (
    dict_id TEXT NOT NULL,
    version TEXT NOT NULL,
    defs_json TEXT NOT NULL,
    created_event_id INTEGER,
    PRIMARY KEY (dict_id, version)
);

CREATE TABLE params_rev (
    params_id TEXT NOT NULL,
    version TEXT NOT NULL,
    tolerance_ratio REAL NOT NULL,
    gap_factor REAL NOT NULL,
    max_candidates INTEGER NOT NULL,
    created_event_id INTEGER,
    PRIMARY KEY (params_id, version)
);

CREATE TABLE branch (
    branch_id TEXT PRIMARY KEY,
    parent_branch_id TEXT,
    forked_from_seq INTEGER,
    dict_id TEXT NOT NULL,
    dict_version TEXT NOT NULL,
    params_id TEXT NOT NULL,
    params_version TEXT NOT NULL,
    created_at_nanos INTEGER NOT NULL,
    label TEXT NOT NULL
);

CREATE TABLE raw_word (
    word_uid TEXT NOT NULL,
    branch_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    ts_nanos INTEGER NOT NULL,
    channel TEXT NOT NULL,
    word INTEGER NOT NULL,
    reversed_capture INTEGER NOT NULL DEFAULT 0,
    first_event_id INTEGER NOT NULL,
    superseded_by_event_id INTEGER,
    PRIMARY KEY (branch_id, seq, first_event_id)
);
CREATE INDEX idx_raw_word_live ON raw_word (branch_id, seq, superseded_by_event_id);

CREATE TABLE event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    branch_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    type TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    rationale TEXT NOT NULL DEFAULT '',
    created_at_nanos INTEGER NOT NULL,
    dependency_edges_json TEXT NOT NULL DEFAULT '[]',
    UNIQUE (branch_id, seq)
);
CREATE INDEX idx_event_branch ON event (branch_id, seq);

INSERT INTO schema_meta(key, value) VALUES ('schema_version', '1');
