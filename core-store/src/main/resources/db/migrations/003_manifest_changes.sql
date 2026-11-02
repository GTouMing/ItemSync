CREATE TABLE IF NOT EXISTS item_change (
    seq          INTEGER PRIMARY KEY AUTOINCREMENT,
    root_id      TEXT NOT NULL,
    logical_path TEXT NOT NULL,
    UNIQUE (root_id, logical_path)
);

CREATE INDEX IF NOT EXISTS idx_item_change_root_seq
    ON item_change (root_id, seq);

INSERT OR REPLACE INTO item_change(root_id, logical_path)
    SELECT root_id, logical_path FROM item ORDER BY root_id, logical_path;

CREATE TABLE IF NOT EXISTS sync_cursor (
    root_id       TEXT NOT NULL,
    node_id       TEXT NOT NULL,
    pull_sequence INTEGER NOT NULL DEFAULT 0,
    push_sequence INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (root_id, node_id)
);
