package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.store.metadata.MetadataStore.CursorRepository;

final class SqlCursorRepository implements CursorRepository {

    private final SqlSession session;

    SqlCursorRepository(SqlSession session) {
        this.session = session;
    }

    @Override
    public long pullSequence(SyncRootId rootId, NodeId peerNodeId) {
        return sequence(rootId, peerNodeId, "pull_sequence");
    }

    @Override
    public void updatePull(SyncRootId rootId, NodeId peerNodeId, long sequence) {
        update(rootId, peerNodeId, "pull_sequence", sequence);
    }

    @Override
    public long pushSequence(SyncRootId rootId, NodeId peerNodeId) {
        return sequence(rootId, peerNodeId, "push_sequence");
    }

    @Override
    public void updatePush(SyncRootId rootId, NodeId peerNodeId, long sequence) {
        update(rootId, peerNodeId, "push_sequence", sequence);
    }

    private long sequence(SyncRootId rootId, NodeId peerNodeId, String column) {
        return session.queryOne(
                "SELECT " + column + " FROM sync_cursor WHERE root_id = ? AND node_id = ?",
                cursor -> cursor.getLong(column),
                rootId.value(), peerNodeId.value()).orElse(0L);
    }

    private void update(SyncRootId rootId, NodeId peerNodeId, String column, long sequence) {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative: " + sequence);
        }
        session.runAtomically(() -> {
            session.update("INSERT OR IGNORE INTO sync_cursor"
                            + "(root_id, node_id, pull_sequence, push_sequence) VALUES (?, ?, 0, 0)",
                    rootId.value(), peerNodeId.value());
            session.update("UPDATE sync_cursor SET " + column + " = ?"
                            + " WHERE root_id = ? AND node_id = ?",
                    sequence, rootId.value(), peerNodeId.value());
        });
    }
}
