-- Group id becomes the unique sync-root key: sync_root folds into sync_group,
-- group_member is replaced by the persistent group device list, and every
-- root-scoped table switches from root_id to group_id.
-- Table rebuilds are used instead of DROP COLUMN for Android SQLite compatibility.

ALTER TABLE sync_group ADD COLUMN physical_path TEXT NOT NULL DEFAULT '';
ALTER TABLE sync_group ADD COLUMN display_name TEXT;
ALTER TABLE sync_group ADD COLUMN ignore_snapshot TEXT;

-- One group owns exactly one root, so the group row inherits the oldest root row.
UPDATE sync_group SET
    physical_path = COALESCE((SELECT r.physical_path FROM sync_root r
                              WHERE r.group_id = sync_group.id ORDER BY r.id LIMIT 1), ''),
    display_name = (SELECT r.display_name FROM sync_root r
                    WHERE r.group_id = sync_group.id ORDER BY r.id LIMIT 1),
    ignore_snapshot = (SELECT r.ignore_snapshot FROM sync_root r
                       WHERE r.group_id = sync_group.id ORDER BY r.id LIMIT 1)
WHERE EXISTS (SELECT 1 FROM sync_root r WHERE r.group_id = sync_group.id);

-- A group without a sync root cannot exist in the one-root-per-group model.
DELETE FROM sync_group WHERE physical_path = '';

CREATE TABLE group_device (
    group_id         TEXT NOT NULL,
    device_id        TEXT NOT NULL,
    display_name     TEXT,
    cert_fingerprint TEXT NOT NULL,
    last_seen_at     INTEGER,
    known_at         INTEGER NOT NULL,
    PRIMARY KEY (group_id, device_id)
);

INSERT OR IGNORE INTO group_device(
        group_id, device_id, display_name, cert_fingerprint, last_seen_at, known_at)
    SELECT m.group_id, p.device_id, COALESCE(m.display_name, p.device_name),
           p.cert_fingerprint, p.last_seen_at, COALESCE(m.joined_at, 0)
    FROM group_member m
    JOIN peer p ON p.node_id = m.node_id;

CREATE TABLE item_new (
    id                 TEXT PRIMARY KEY,
    group_id           TEXT NOT NULL,
    logical_path       TEXT NOT NULL,
    kind               TEXT NOT NULL,
    current_version_id TEXT,
    deleted_at         INTEGER,
    UNIQUE (group_id, logical_path)
);

INSERT INTO item_new(id, group_id, logical_path, kind, current_version_id, deleted_at)
    SELECT i.id, r.group_id, i.logical_path, i.kind, i.current_version_id, i.deleted_at
    FROM item i
    JOIN sync_root r ON r.id = i.root_id;

DROP TABLE item;
ALTER TABLE item_new RENAME TO item;

CREATE TABLE item_vector_new (
    group_id     TEXT NOT NULL,
    logical_path TEXT NOT NULL,
    node_id      TEXT NOT NULL,
    counter      INTEGER NOT NULL,
    PRIMARY KEY (group_id, logical_path, node_id)
);

INSERT OR IGNORE INTO item_vector_new(group_id, logical_path, node_id, counter)
    SELECT r.group_id, v.logical_path, v.node_id, v.counter
    FROM item_vector v
    JOIN sync_root r ON r.id = v.root_id;

DROP TABLE item_vector;
ALTER TABLE item_vector_new RENAME TO item_vector;

-- The AUTOINCREMENT sequence is preserved by copying seq explicitly.
CREATE TABLE item_change_new (
    seq          INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id     TEXT NOT NULL,
    logical_path TEXT NOT NULL,
    UNIQUE (group_id, logical_path)
);

INSERT OR IGNORE INTO item_change_new(seq, group_id, logical_path)
    SELECT c.seq, r.group_id, c.logical_path
    FROM item_change c
    JOIN sync_root r ON r.id = c.root_id;

DROP TABLE item_change;
ALTER TABLE item_change_new RENAME TO item_change;

CREATE INDEX IF NOT EXISTS idx_item_change_group_seq
    ON item_change (group_id, seq);

CREATE TABLE sync_cursor_new (
    group_id      TEXT NOT NULL,
    node_id       TEXT NOT NULL,
    pull_sequence INTEGER NOT NULL DEFAULT 0,
    push_sequence INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (group_id, node_id)
);

INSERT OR REPLACE INTO sync_cursor_new(group_id, node_id, pull_sequence, push_sequence)
    SELECT r.group_id, c.node_id, c.pull_sequence, c.push_sequence
    FROM sync_cursor c
    JOIN sync_root r ON r.id = c.root_id;

DROP TABLE sync_cursor;
ALTER TABLE sync_cursor_new RENAME TO sync_cursor;

CREATE TABLE trigger_new (
    id           TEXT PRIMARY KEY,
    group_id     TEXT NOT NULL,
    type         TEXT NOT NULL,
    config_json  TEXT NOT NULL,
    enabled      INTEGER NOT NULL,
    next_fire_at INTEGER
);

INSERT INTO trigger_new(id, group_id, type, config_json, enabled, next_fire_at)
    SELECT id, group_id, type, config_json, enabled, next_fire_at FROM trigger;

DROP TABLE trigger;
ALTER TABLE trigger_new RENAME TO trigger;

CREATE INDEX IF NOT EXISTS idx_trigger_due ON trigger (enabled, next_fire_at);

CREATE TABLE sync_run_new (
    id             TEXT PRIMARY KEY,
    group_id       TEXT NOT NULL,
    trigger_id     TEXT,
    source_node_id TEXT NOT NULL,
    state          TEXT NOT NULL,
    started_at     INTEGER NOT NULL,
    ended_at       INTEGER,
    stats_json     TEXT,
    error_code     TEXT
);

INSERT INTO sync_run_new(
        id, group_id, trigger_id, source_node_id, state, started_at, ended_at, stats_json, error_code)
    SELECT id, group_id, trigger_id, source_node_id, state, started_at, ended_at, stats_json, error_code
    FROM sync_run;

DROP TABLE sync_run;
ALTER TABLE sync_run_new RENAME TO sync_run;

CREATE INDEX IF NOT EXISTS idx_run_group ON sync_run (group_id, started_at DESC);

DROP TABLE sync_root;
DROP TABLE group_member;
