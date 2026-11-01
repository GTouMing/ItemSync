CREATE TABLE IF NOT EXISTS peer (
    node_id          TEXT PRIMARY KEY,
    device_id        TEXT NOT NULL,
    device_name      TEXT,
    platform         TEXT,
    cert_fingerprint TEXT NOT NULL,
    last_seen_at     INTEGER
);

CREATE TABLE IF NOT EXISTS sync_group (
    id            TEXT PRIMARY KEY,
    name          TEXT,
    logical_clock INTEGER NOT NULL DEFAULT 0,
    created_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS group_member (
    group_id     TEXT NOT NULL,
    node_id      TEXT NOT NULL,
    display_name TEXT,
    consent      INTEGER NOT NULL DEFAULT 0,
    blocked      INTEGER NOT NULL DEFAULT 0,
    joined_at    INTEGER,
    PRIMARY KEY (group_id, node_id)
);

CREATE TABLE IF NOT EXISTS sync_root (
    id              TEXT PRIMARY KEY,
    group_id        TEXT NOT NULL,
    physical_path   TEXT NOT NULL,
    display_name    TEXT,
    ignore_snapshot TEXT,
    created_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS item (
    id                 TEXT PRIMARY KEY,
    root_id            TEXT NOT NULL,
    logical_path       TEXT NOT NULL,
    kind               TEXT NOT NULL,
    current_version_id TEXT,
    deleted_at         INTEGER,
    UNIQUE (root_id, logical_path)
);

CREATE TABLE IF NOT EXISTS item_vector (
    root_id      TEXT NOT NULL,
    logical_path TEXT NOT NULL,
    node_id      TEXT NOT NULL,
    counter      INTEGER NOT NULL,
    PRIMARY KEY (root_id, logical_path, node_id)
);

CREATE TABLE IF NOT EXISTS version (
    id             TEXT PRIMARY KEY,
    item_id        TEXT NOT NULL,
    blob_hash      TEXT NOT NULL,
    size           INTEGER NOT NULL,
    mtime          INTEGER NOT NULL,
    source_node_id TEXT NOT NULL,
    vector_json    TEXT NOT NULL,
    run_id         TEXT,
    created_at     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_version_item ON version (item_id, created_at DESC);

CREATE TABLE IF NOT EXISTS tombstone (
    item_id        TEXT PRIMARY KEY,
    vector_json    TEXT NOT NULL,
    deleted_at     INTEGER NOT NULL,
    source_node_id TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS conflict (
    id                TEXT PRIMARY KEY,
    item_id           TEXT NOT NULL,
    local_version_id  TEXT,
    remote_version_id TEXT,
    copy_path         TEXT,
    detected_at       INTEGER NOT NULL,
    resolved_at       INTEGER
);

CREATE INDEX IF NOT EXISTS idx_conflict_open ON conflict (resolved_at, detected_at);

CREATE TABLE IF NOT EXISTS trigger (
    id           TEXT PRIMARY KEY,
    group_id     TEXT NOT NULL,
    root_id      TEXT NOT NULL,
    type         TEXT NOT NULL,
    config_json  TEXT NOT NULL,
    enabled      INTEGER NOT NULL,
    next_fire_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_trigger_due ON trigger (enabled, next_fire_at);

CREATE TABLE IF NOT EXISTS sync_run (
    id             TEXT PRIMARY KEY,
    group_id       TEXT NOT NULL,
    root_id        TEXT NOT NULL,
    trigger_id     TEXT,
    source_node_id TEXT NOT NULL,
    state          TEXT NOT NULL,
    started_at     INTEGER NOT NULL,
    ended_at       INTEGER,
    stats_json     TEXT,
    error_code     TEXT
);

CREATE INDEX IF NOT EXISTS idx_run_root ON sync_run (root_id, started_at DESC);
