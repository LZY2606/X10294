-- ARINC 429 review bench baseline schema.
-- Derived data is append-only per branch; nothing derived is ever overwritten.

CREATE TABLE schema_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE material (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  version INTEGER NOT NULL,
  source_path TEXT NOT NULL,
  imported_at_us TEXT NOT NULL,
  payload TEXT NOT NULL,
  superseded_by INTEGER
);

CREATE TABLE raw_word (
  id TEXT NOT NULL,
  material_version INTEGER NOT NULL,
  orig_index TEXT NOT NULL,
  receive_time_us TEXT NOT NULL,
  channel TEXT NOT NULL,
  raw_text TEXT NOT NULL,
  raw_bits INTEGER NOT NULL,
  present INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (id, material_version)
);

CREATE TABLE channel_config (
  id TEXT PRIMARY KEY,
  material_version INTEGER NOT NULL,
  name TEXT NOT NULL,
  bus_bps INTEGER,
  payload TEXT NOT NULL
);

CREATE TABLE dict_version (
  version TEXT PRIMARY KEY,
  source_path TEXT NOT NULL,
  imported_at_us TEXT NOT NULL,
  payload TEXT NOT NULL
);

CREATE TABLE rule_version (
  version TEXT PRIMARY KEY,
  imported_at_us TEXT NOT NULL,
  rate_tolerance REAL NOT NULL,
  acquisition_gap_us INTEGER NOT NULL,
  ssm_table TEXT NOT NULL,
  note TEXT
);

CREATE TABLE branch (
  id TEXT PRIMARY KEY,
  parent_branch TEXT,
  base_event_id TEXT,
  material_version INTEGER NOT NULL,
  dictionary_version TEXT NOT NULL,
  rule_version TEXT NOT NULL,
  head_event_id TEXT,
  created_at_us TEXT NOT NULL
);

CREATE TABLE event (
  id TEXT PRIMARY KEY,
  branch_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  kind TEXT NOT NULL,
  at_us TEXT NOT NULL,
  actor TEXT NOT NULL,
  material_version INTEGER NOT NULL,
  dictionary_version TEXT NOT NULL,
  rule_version TEXT NOT NULL,
  payload TEXT NOT NULL,
  prev_event_id TEXT,
  UNIQUE (branch_id, seq)
);

CREATE TABLE derived_word (
  id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  branch_id TEXT NOT NULL,
  raw_word_id TEXT NOT NULL,
  material_version INTEGER NOT NULL,
  dictionary_version TEXT NOT NULL,
  rule_version TEXT NOT NULL,
  payload TEXT NOT NULL,
  present INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (id, event_id)
);

CREATE TABLE aggregate (
  id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  branch_id TEXT NOT NULL,
  channel TEXT NOT NULL,
  label_octal TEXT NOT NULL,
  material_version INTEGER NOT NULL,
  dictionary_version TEXT NOT NULL,
  rule_version TEXT NOT NULL,
  payload TEXT NOT NULL,
  present INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (id, event_id)
);

CREATE TABLE rate_analysis (
  id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  branch_id TEXT NOT NULL,
  channel TEXT NOT NULL,
  material_version INTEGER NOT NULL,
  rule_version TEXT NOT NULL,
  payload TEXT NOT NULL,
  present INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (id, event_id)
);

CREATE TABLE invalidation (
  event_id TEXT NOT NULL,
  target_table TEXT NOT NULL,
  target_id TEXT NOT NULL,
  reason TEXT NOT NULL,
  PRIMARY KEY (event_id, target_table, target_id)
);

CREATE TABLE mutation_run (
  id TEXT PRIMARY KEY,
  branch_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  mutation TEXT NOT NULL,
  baseline_event_id TEXT NOT NULL,
  candidate_event_id TEXT,
  accepted INTEGER NOT NULL,
  expected_scope TEXT NOT NULL,
  actual_scope TEXT NOT NULL,
  constrained INTEGER NOT NULL,
  note TEXT,
  UNIQUE (branch_id, seq)
);

CREATE INDEX idx_event_branch ON event(branch_id, seq);
CREATE INDEX idx_derived_branch ON derived_word(branch_id, event_id);
CREATE INDEX idx_aggregate_branch ON aggregate(branch_id, event_id);
CREATE INDEX idx_rate_branch ON rate_analysis(branch_id, event_id);
