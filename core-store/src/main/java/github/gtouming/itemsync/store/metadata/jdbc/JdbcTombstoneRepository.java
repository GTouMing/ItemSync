package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.model.Tombstone;
import github.gtouming.itemsync.domain.replication.VersionVectorCodec;
import github.gtouming.itemsync.store.metadata.MetadataStore.TombstoneRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class JdbcTombstoneRepository implements TombstoneRepository {

    private static final String COLUMNS = "item_id, vector_json, deleted_at, source_node_id";

    private final Connection connection;

    JdbcTombstoneRepository(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void upsert(Tombstone tombstone) {
        String sql = "INSERT OR REPLACE INTO tombstone(" + COLUMNS + ") VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tombstone.itemId().value());
            ps.setString(2, VersionVectorCodec.encode(tombstone.vector()));
            JdbcSupport.setInstant(ps, 3, tombstone.deletedAt());
            ps.setString(4, tombstone.sourceNodeId().value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("upsert tombstone", e);
        }
    }

    @Override
    public Optional<Tombstone> find(ItemId itemId) {
        String sql = "SELECT " + COLUMNS + " FROM tombstone WHERE item_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, itemId.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("find tombstone", e);
        }
    }

    @Override
    public List<Tombstone> page(String cursor, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        boolean hasCursor = cursor != null;
        String sql = "SELECT " + COLUMNS + " FROM tombstone"
                + (hasCursor ? " WHERE item_id > ?" : "")
                + " ORDER BY item_id LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            if (hasCursor) {
                ps.setString(index++, cursor);
            }
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Tombstone> tombstones = new ArrayList<>();
                while (rs.next()) {
                    tombstones.add(map(rs));
                }
                return List.copyOf(tombstones);
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("page tombstones", e);
        }
    }

    @Override
    public void delete(ItemId itemId) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM tombstone WHERE item_id = ?")) {
            ps.setString(1, itemId.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("delete tombstone", e);
        }
    }

    private static Tombstone map(ResultSet rs) throws SQLException {
        return new Tombstone(
                ItemId.of(rs.getString("item_id")),
                VersionVectorCodec.decode(rs.getString("vector_json")),
                JdbcSupport.getInstant(rs, "deleted_at"),
                NodeId.of(rs.getString("source_node_id")));
    }
}
