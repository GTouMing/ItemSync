package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.metadata.MetadataStore.ItemRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class JdbcItemRepository implements ItemRepository {

    private static final String COLUMNS =
            "id, root_id, logical_path, kind, current_version_id, deleted_at";

    private final Connection connection;

    JdbcItemRepository(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void upsert(Item item) {
        String sql = "INSERT OR REPLACE INTO item(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, item.id().value());
            ps.setString(2, item.rootId().value());
            ps.setString(3, item.logicalPath().value());
            ps.setString(4, item.kind().name());
            ps.setString(5, item.currentVersion() == null ? null : item.currentVersion().value());
            JdbcSupport.setInstant(ps, 6, item.deletedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("upsert item", e);
        }
        try {
            replaceVector(item.rootId(), item.logicalPath(), item.vector());
        } catch (SQLException e) {
            throw JdbcSupport.fail("upsert item vector", e);
        }
    }

    @Override
    public Optional<Item> find(SyncRootId rootId, LogicalPath path) {
        String sql = "SELECT " + COLUMNS + " FROM item WHERE root_id = ? AND logical_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, rootId.value());
            ps.setString(2, path.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(map(rs, loadVector(rootId, path)));
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("find item", e);
        }
    }

    @Override
    public Optional<Item> findById(ItemId id) {
        String sql = "SELECT " + COLUMNS + " FROM item WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                SyncRootId rootId = SyncRootId.of(rs.getString("root_id"));
                LogicalPath path = LogicalPath.of(rs.getString("logical_path"));
                return Optional.of(map(rs, loadVector(rootId, path)));
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("find item by id", e);
        }
    }

    @Override
    public ItemPage page(SyncRootId rootId, String cursor, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        boolean hasCursor = cursor != null;
        String sql = "SELECT i.id, i.root_id, i.logical_path, i.kind, i.current_version_id, i.deleted_at, "
                + "v.node_id AS vector_node_id, v.counter AS vector_counter "
                + "FROM (SELECT " + COLUMNS + " FROM item WHERE root_id = ?"
                + (hasCursor ? " AND logical_path > ?" : "")
                + " ORDER BY logical_path LIMIT ?) i "
                + "LEFT JOIN item_vector v ON v.root_id = i.root_id AND v.logical_path = i.logical_path "
                + "ORDER BY i.logical_path, v.node_id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            ps.setString(index++, rootId.value());
            if (hasCursor) {
                ps.setString(index++, cursor);
            }
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Item> items = new ArrayList<>();
                String currentPath = null;
                ItemBuilder builder = null;
                while (rs.next()) {
                    String path = rs.getString("logical_path");
                    if (!path.equals(currentPath)) {
                        if (builder != null) {
                            items.add(builder.build());
                        }
                        builder = new ItemBuilder(rs);
                        currentPath = path;
                    }
                    String node = rs.getString("vector_node_id");
                    if (node != null && builder != null) {
                        builder.counters.put(NodeId.of(node), rs.getLong("vector_counter"));
                    }
                }
                if (builder != null) {
                    items.add(builder.build());
                }
                String nextCursor = items.size() == limit
                        ? items.get(items.size() - 1).logicalPath().value()
                        : null;
                return new ItemPage(List.copyOf(items), nextCursor);
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("page items", e);
        }
    }

    @Override
    public void delete(ItemId id) {
        try {
            String rootId;
            String logicalPath;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT root_id, logical_path FROM item WHERE id = ?")) {
                ps.setString(1, id.value());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return;
                    }
                    rootId = rs.getString("root_id");
                    logicalPath = rs.getString("logical_path");
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM item_vector WHERE root_id = ? AND logical_path = ?")) {
                ps.setString(1, rootId);
                ps.setString(2, logicalPath);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM item WHERE id = ?")) {
                ps.setString(1, id.value());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("delete item", e);
        }
    }

    private void replaceVector(SyncRootId rootId, LogicalPath path, VersionVector vector) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM item_vector WHERE root_id = ? AND logical_path = ?")) {
            delete.setString(1, rootId.value());
            delete.setString(2, path.value());
            delete.executeUpdate();
        }
        if (vector.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO item_vector(root_id, logical_path, node_id, counter) VALUES (?, ?, ?, ?)")) {
            for (NodeId node : vector.nodes()) {
                insert.setString(1, rootId.value());
                insert.setString(2, path.value());
                insert.setString(3, node.value());
                insert.setLong(4, vector.counterOf(node));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private VersionVector loadVector(SyncRootId rootId, LogicalPath path) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT node_id, counter FROM item_vector WHERE root_id = ? AND logical_path = ?")) {
            ps.setString(1, rootId.value());
            ps.setString(2, path.value());
            try (ResultSet rs = ps.executeQuery()) {
                Map<NodeId, Long> counters = new LinkedHashMap<>();
                while (rs.next()) {
                    counters.put(NodeId.of(rs.getString("node_id")), rs.getLong("counter"));
                }
                return counters.isEmpty() ? VersionVector.empty() : VersionVector.of(counters);
            }
        }
    }

    private static Item map(ResultSet rs, VersionVector vector) throws SQLException {
        String currentVersionId = rs.getString("current_version_id");
        return new Item(
                ItemId.of(rs.getString("id")),
                SyncRootId.of(rs.getString("root_id")),
                LogicalPath.of(rs.getString("logical_path")),
                ItemKind.valueOf(rs.getString("kind")),
                currentVersionId == null ? null : VersionId.of(currentVersionId),
                vector,
                JdbcSupport.getInstant(rs, "deleted_at"));
    }

    private static final class ItemBuilder {

        private final String id;
        private final String rootId;
        private final String logicalPath;
        private final String kind;
        private final String currentVersionId;
        private final Instant deletedAt;
        private final Map<NodeId, Long> counters = new LinkedHashMap<>();

        private ItemBuilder(ResultSet rs) throws SQLException {
            this.id = rs.getString("id");
            this.rootId = rs.getString("root_id");
            this.logicalPath = rs.getString("logical_path");
            this.kind = rs.getString("kind");
            this.currentVersionId = rs.getString("current_version_id");
            this.deletedAt = JdbcSupport.getInstant(rs, "deleted_at");
        }

        private Item build() {
            return new Item(
                    ItemId.of(id),
                    SyncRootId.of(rootId),
                    LogicalPath.of(logicalPath),
                    ItemKind.valueOf(kind),
                    currentVersionId == null ? null : VersionId.of(currentVersionId),
                    counters.isEmpty() ? VersionVector.empty() : VersionVector.of(counters),
                    deletedAt);
        }
    }
}
