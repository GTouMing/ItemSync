package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.model.GroupMember;
import github.gtouming.itemsync.domain.model.SyncGroup;
import github.gtouming.itemsync.store.metadata.MetadataStore.SyncGroupRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class JdbcSyncGroupRepository implements SyncGroupRepository {

    private final Connection connection;

    JdbcSyncGroupRepository(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void insert(SyncGroup group) {
        String sql = "INSERT OR REPLACE INTO sync_group(id, name, logical_clock, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, group.id().value());
            ps.setString(2, group.name());
            ps.setLong(3, group.logicalClock());
            JdbcSupport.setInstant(ps, 4, group.createdAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("insert sync group", e);
        }
    }

    @Override
    public Optional<SyncGroup> find(GroupId id) {
        String sql = "SELECT id, name, logical_clock, created_at FROM sync_group WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SyncGroup(
                        GroupId.parse(rs.getString("id")),
                        rs.getString("name"),
                        rs.getLong("logical_clock"),
                        JdbcSupport.getInstant(rs, "created_at")));
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("find sync group", e);
        }
    }

    @Override
    public void updateLogicalClock(GroupId id, long logicalClock) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sync_group SET logical_clock = ? WHERE id = ?")) {
            ps.setLong(1, logicalClock);
            ps.setString(2, id.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("update logical clock", e);
        }
    }

    @Override
    public void upsertMember(GroupMember member) {
        String sql = "INSERT OR REPLACE INTO group_member(group_id, node_id, display_name, consent, blocked, joined_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, member.groupId().value());
            ps.setString(2, member.nodeId().value());
            ps.setString(3, member.displayName());
            ps.setInt(4, member.consent() ? 1 : 0);
            ps.setInt(5, member.blocked() ? 1 : 0);
            JdbcSupport.setInstant(ps, 6, member.joinedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("upsert group member", e);
        }
    }

    @Override
    public Optional<GroupMember> findMember(GroupId groupId, NodeId nodeId) {
        String sql = "SELECT group_id, node_id, display_name, consent, blocked, joined_at "
                + "FROM group_member WHERE group_id = ? AND node_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, groupId.value());
            ps.setString(2, nodeId.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapMember(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("find group member", e);
        }
    }

    @Override
    public List<GroupMember> members(GroupId groupId) {
        String sql = "SELECT group_id, node_id, display_name, consent, blocked, joined_at "
                + "FROM group_member WHERE group_id = ? ORDER BY node_id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, groupId.value());
            try (ResultSet rs = ps.executeQuery()) {
                List<GroupMember> members = new ArrayList<>();
                while (rs.next()) {
                    members.add(mapMember(rs));
                }
                return List.copyOf(members);
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("list group members", e);
        }
    }

    @Override
    public void deleteMember(GroupId groupId, NodeId nodeId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM group_member WHERE group_id = ? AND node_id = ?")) {
            ps.setString(1, groupId.value());
            ps.setString(2, nodeId.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("delete group member", e);
        }
    }

    private static GroupMember mapMember(ResultSet rs) throws SQLException {
        return new GroupMember(
                GroupId.parse(rs.getString("group_id")),
                NodeId.of(rs.getString("node_id")),
                rs.getString("display_name"),
                rs.getInt("consent") != 0,
                rs.getInt("blocked") != 0,
                JdbcSupport.getInstant(rs, "joined_at"));
    }
}
