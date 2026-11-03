package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.store.metadata.MetadataStore.CursorRepository;

final class SqlCursorRepository implements CursorRepository {

    private final SqlSession session;

    SqlCursorRepository(SqlSession session) {
        this.session = session;
    }

    @Override
    public long pullSequence(GroupId groupId, NodeId peerNodeId) {
        return sequence(groupId, peerNodeId, "pull_sequence");
    }

    @Override
    public void updatePull(GroupId groupId, NodeId peerNodeId, long sequence) {
        update(groupId, peerNodeId, "pull_sequence", sequence);
    }

    @Override
    public long pushSequence(GroupId groupId, NodeId peerNodeId) {
        return sequence(groupId, peerNodeId, "push_sequence");
    }

    @Override
    public void updatePush(GroupId groupId, NodeId peerNodeId, long sequence) {
        update(groupId, peerNodeId, "push_sequence", sequence);
    }

    private long sequence(GroupId groupId, NodeId peerNodeId, String column) {
        return session.queryOne(
                "SELECT " + column + " FROM sync_cursor WHERE group_id = ? AND node_id = ?",
                cursor -> cursor.getLong(column),
                groupId.value(), peerNodeId.value()).orElse(0L);
    }

    private void update(GroupId groupId, NodeId peerNodeId, String column, long sequence) {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative: " + sequence);
        }
        session.runAtomically(() -> {
            session.update("INSERT OR IGNORE INTO sync_cursor"
                            + "(group_id, node_id, pull_sequence, push_sequence) VALUES (?, ?, 0, 0)",
                    groupId.value(), peerNodeId.value());
            session.update("UPDATE sync_cursor SET " + column + " = ?"
                            + " WHERE group_id = ? AND node_id = ?",
                    sequence, groupId.value(), peerNodeId.value());
        });
    }
}
