package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.replication.VersionVectorCodec;
import github.gtouming.itemsync.store.metadata.MetadataStore.VersionRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class JdbcVersionRepository implements VersionRepository {

    private static final String COLUMNS =
            "id, item_id, blob_hash, size, mtime, source_node_id, vector_json, run_id, created_at";

    private final Connection connection;

    JdbcVersionRepository(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void insert(FileVersion version) {
        String sql = "INSERT OR REPLACE INTO version(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, version.id().value());
            ps.setString(2, version.itemId().value());
            ps.setString(3, version.blobHash().value());
            ps.setLong(4, version.size());
            JdbcSupport.setInstant(ps, 5, version.mtime());
            ps.setString(6, version.sourceNodeId().value());
            ps.setString(7, VersionVectorCodec.encode(version.vector()));
            ps.setString(8, version.producedBy() == null ? null : version.producedBy().value());
            JdbcSupport.setInstant(ps, 9, version.createdAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("insert version", e);
        }
    }

    @Override
    public Optional<FileVersion> find(VersionId id) {
        String sql = "SELECT " + COLUMNS + " FROM version WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("find version", e);
        }
    }

    @Override
    public List<FileVersion> recent(ItemId itemId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        String sql = "SELECT " + COLUMNS + " FROM version WHERE item_id = ? "
                + "ORDER BY created_at DESC, id DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, itemId.value());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<FileVersion> versions = new ArrayList<>();
                while (rs.next()) {
                    versions.add(map(rs));
                }
                return List.copyOf(versions);
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("list recent versions", e);
        }
    }

    @Override
    public int deleteOlderThan(ItemId itemId, int keepNewest) {
        if (keepNewest < 0) {
            throw new IllegalArgumentException("keepNewest must not be negative, got " + keepNewest);
        }
        String sql = "DELETE FROM version WHERE item_id = ? AND id NOT IN ("
                + "SELECT id FROM version WHERE item_id = ? ORDER BY created_at DESC, id DESC LIMIT ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, itemId.value());
            ps.setString(2, itemId.value());
            ps.setInt(3, keepNewest);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("delete old versions", e);
        }
    }

    private static FileVersion map(ResultSet rs) throws SQLException {
        String runId = rs.getString("run_id");
        return new FileVersion(
                VersionId.of(rs.getString("id")),
                ItemId.of(rs.getString("item_id")),
                BlobHash.parse(rs.getString("blob_hash")),
                rs.getLong("size"),
                JdbcSupport.getInstant(rs, "mtime"),
                NodeId.of(rs.getString("source_node_id")),
                VersionVectorCodec.decode(rs.getString("vector_json")),
                runId == null ? null : SyncRunId.of(runId),
                JdbcSupport.getInstant(rs, "created_at"));
    }
}
