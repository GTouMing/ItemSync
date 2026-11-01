package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.model.GroupMember;
import github.gtouming.itemsync.domain.model.SyncGroup;
import github.gtouming.itemsync.store.metadata.MetadataStore.SyncGroupRepository;

import java.util.List;
import java.util.Optional;

final class SqlSyncGroupRepository implements SyncGroupRepository {

    private final SqlSession session;

    SqlSyncGroupRepository(SqlSession session) {
        this.session = session;
    }

    @Override
    public void insert(SyncGroup group) {
        session.update("INSERT OR REPLACE INTO sync_group(id, name, logical_clock, created_at) VALUES (?, ?, ?, ?)",
                group.id().value(), group.name(), group.logicalClock(), group.createdAt());
    }

    @Override
    public Optional<SyncGroup> find(GroupId id) {
        return session.queryOne("SELECT id, name, logical_clock, created_at FROM sync_group WHERE id = ?",
                cursor -> new SyncGroup(
                        GroupId.parse(cursor.getString("id")),
                        cursor.getString("name"),
                        cursor.getLong("logical_clock"),
                        SqlValues.instant(cursor, "created_at")),
                id.value());
    }

    @Override
    public void updateLogicalClock(GroupId id, long logicalClock) {
        session.update("UPDATE sync_group SET logical_clock = ? WHERE id = ?", logicalClock, id.value());
    }

    @Override
    public void upsertMember(GroupMember member) {
        session.update("INSERT OR REPLACE INTO group_member(group_id, node_id, display_name, consent, blocked, joined_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                member.groupId().value(), member.nodeId().value(), member.displayName(),
                member.consent(), member.blocked(), member.joinedAt());
    }

    @Override
    public Optional<GroupMember> findMember(GroupId groupId, NodeId nodeId) {
        return session.queryOne("SELECT group_id, node_id, display_name, consent, blocked, joined_at "
                        + "FROM group_member WHERE group_id = ? AND node_id = ?",
                SqlSyncGroupRepository::mapMember, groupId.value(), nodeId.value());
    }

    @Override
    public List<GroupMember> members(GroupId groupId) {
        return session.query("SELECT group_id, node_id, display_name, consent, blocked, joined_at "
                        + "FROM group_member WHERE group_id = ? ORDER BY node_id",
                SqlSyncGroupRepository::mapMember, groupId.value());
    }

    @Override
    public void deleteMember(GroupId groupId, NodeId nodeId) {
        session.update("DELETE FROM group_member WHERE group_id = ? AND node_id = ?",
                groupId.value(), nodeId.value());
    }

    private static GroupMember mapMember(RowCursor cursor) {
        return new GroupMember(
                GroupId.parse(cursor.getString("group_id")),
                NodeId.of(cursor.getString("node_id")),
                cursor.getString("display_name"),
                cursor.getInt("consent") != 0,
                cursor.getInt("blocked") != 0,
                SqlValues.instant(cursor, "joined_at"));
    }
}
