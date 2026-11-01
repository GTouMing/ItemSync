package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.model.SyncRoot;
import github.gtouming.itemsync.store.metadata.MetadataStore.SyncRootRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class JdbcSyncRootRepository implements SyncRootRepository {

    private static final String COLUMNS =
            "id, group_id, physical_path, display_name, ignore_snapshot, created_at";

    private final Connection connection;

    JdbcSyncRootRepository(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void insert(SyncRoot root) {
        String sql = "INSERT OR REPLACE INTO sync_root(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, root.id().value());
            ps.setString(2, root.groupId().value());
            ps.setString(3, root.physicalPath());
            ps.setString(4, root.displayName());
            ps.setString(5, root.ignoreSnapshot());
            JdbcSupport.setInstant(ps, 6, root.createdAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("insert sync root", e);
        }
    }

    @Override
    public Optional<SyncRoot> find(SyncRootId id) {
        String sql = "SELECT " + COLUMNS + " FROM sync_root WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("find sync root", e);
        }
    }

    @Override
    public List<SyncRoot> findByGroup(GroupId groupId) {
        String sql = "SELECT " + COLUMNS + " FROM sync_root WHERE group_id = ? ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, groupId.value());
            try (ResultSet rs = ps.executeQuery()) {
                List<SyncRoot> roots = new ArrayList<>();
                while (rs.next()) {
                    roots.add(map(rs));
                }
                return List.copyOf(roots);
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("list sync roots", e);
        }
    }

    @Override
    public void updateIgnoreSnapshot(SyncRootId id, String ignoreSnapshot) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sync_root SET ignore_snapshot = ? WHERE id = ?")) {
            ps.setString(1, ignoreSnapshot);
            ps.setString(2, id.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("update ignore snapshot", e);
        }
    }

    @Override
    public void delete(SyncRootId id) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM sync_root WHERE id = ?")) {
            ps.setString(1, id.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("delete sync root", e);
        }
    }

    private static SyncRoot map(ResultSet rs) throws SQLException {
        return new SyncRoot(
                SyncRootId.of(rs.getString("id")),
                GroupId.parse(rs.getString("group_id")),
                rs.getString("physical_path"),
                rs.getString("display_name"),
                rs.getString("ignore_snapshot"),
                JdbcSupport.getInstant(rs, "created_at"));
    }
}
