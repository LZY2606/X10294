-- Migration test: a later release adds provenance columns without losing data.
ALTER TABLE raw_word ADD COLUMN source_marker TEXT;
ALTER TABLE dict_version ADD COLUMN coord_datum TEXT;
ALTER TABLE branch ADD COLUMN notes TEXT;

INSERT INTO schema_meta(key, value) VALUES ('provenance_columns', 'V2');
