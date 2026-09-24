-- V2: counter-example archive, adjudication links and provenance dependency edges.
CREATE TABLE archive_run (
    run_uid TEXT PRIMARY KEY,
    branch_id TEXT NOT NULL,
    case_pack_id TEXT NOT NULL,
    case_pack_version TEXT NOT NULL,
    baseline_snapshot_json TEXT NOT NULL,
    created_at_nanos INTEGER NOT NULL
);

CREATE TABLE archive_mutation (
    run_uid TEXT NOT NULL,
    mutation_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    input_fragment_json TEXT NOT NULL,
    adopted INTEGER NOT NULL,
    expected_scope_json TEXT NOT NULL,
    actual_scope_json TEXT NOT NULL,
    confined INTEGER NOT NULL,
    rationale_event_json TEXT NOT NULL,
    PRIMARY KEY (run_uid, mutation_id)
);

CREATE TABLE adjudication (
    branch_id TEXT NOT NULL,
    scope_uid TEXT NOT NULL,
    event_id INTEGER NOT NULL,
    decision TEXT NOT NULL,
    revoked_event_id INTEGER,
    PRIMARY KEY (branch_id, scope_uid)
);

CREATE TABLE provenance_edge (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id INTEGER NOT NULL,
    from_node TEXT NOT NULL,
    to_node TEXT NOT NULL,
    edge_kind TEXT NOT NULL
);
CREATE INDEX idx_prov_from ON provenance_edge (from_node);
CREATE INDEX idx_prov_event ON provenance_edge (event_id);

UPDATE schema_meta SET value = '2' WHERE key = 'schema_version';
