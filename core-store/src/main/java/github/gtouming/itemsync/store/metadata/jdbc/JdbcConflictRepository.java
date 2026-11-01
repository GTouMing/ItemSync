package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.domain.identity.ConflictId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.Conflict;
import github.gtouming.itemsync.store.metadata.MetadataStore.ConflictRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class JdbcConflictRepository implements ConflictRepository {

    private static final String COLUMNS =
            "id, item_id, local_version_id, remote_version_id, copy_path, detected_at, resolved_at";

    private final Connection connection;

    JdbcConflictRepository(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void insert(Conflict conflict) {
        String sql = "INSERT OR REPLACE INTO conflict(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conflict.id().value());
            ps.setString(2, conflict.itemId().value());
            ps.setString(3, conflict.localVersion() == null ? null : conflict.localVersion().value());
            ps.setString(4, conflict.remoteVersion() == null ? null : conflict.remoteVersion().value());
            ps.setString(5, conflict.copyPath() == null ? null : conflict.copyPath().value());
            JdbcSupport.setInstant(ps, 6, conflict.detectedAt());
            JdbcSupport.setInstant(ps, 7, conflict.resolvedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("insert conflict", e);
        }
    }

    @Override
    public Optional<Conflict> find(ConflictId id) {
        String sql = "SELECT " + COLUMNS + " FROM conflict WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("find conflict", e);
        }
    }

    @Override
    public List<Conflict> findOpen(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        String sql = "SELECT " + COLUMNS + " FROM conflict WHERE resolved_at IS NULL "
                + "ORDER BY detected_at, id LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Conflict> conflicts = new ArrayList<>();
                while (rs.next()) {
                    conflicts.add(map(rs));
                }
                return List.copyOf(conflicts);
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("list open conflicts", e);
        }
    }

    @Override
    public List<Conflict> findByItem(ItemId itemId) {
        String sql = "SELECT " + COLUMNS + " FROM conflict WHERE item_id = ? ORDER BY detected_at, id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, itemId.value());
            try (ResultSet rs = ps.executeQuery()) {
                List<Conflict> conflicts = new ArrayList<>();
                while (rs.next()) {
                    conflicts.add(map(rs));
                }
                return List.copyOf(conflicts);
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("list conflicts for item", e);
        }
    }

    @Override
    public void resolve(ConflictId id, Instant resolvedAt) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE conflict SET resolved_at = ? WHERE id = ?")) {
            JdbcSupport.setInstant(ps, 1, resolvedAt);
            ps.setString(2, id.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("resolve conflict", e);
        }
    }

    private static Conflict map(ResultSet rs) throws SQLException {
        String localVersionId = rs.getString("local_version_id");
        String remoteVersionId = rs.getString("remote_version_id");
        String copyPath = rs.getString("copy_path");
        return new Conflict(
                ConflictId.of(rs.getString("id")),
                ItemId.of(rs.getString("item_id")),
                localVersionId == null ? null : VersionId.of(localVersionId),
                remoteVersionId == null ? null : VersionId.of(remoteVersionId),
                copyPath == null ? null : LogicalPath.of(copyPath),
                JdbcSupport.getInstant(rs, "detected_at"),
                JdbcSupport.getInstant(rs, "resolved_at"));
    }
}
