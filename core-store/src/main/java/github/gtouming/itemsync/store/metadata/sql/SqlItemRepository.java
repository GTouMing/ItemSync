package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.metadata.MetadataStore.ItemRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class SqlItemRepository implements ItemRepository {

    private static final String COLUMNS =
            "id, group_id, logical_path, kind, current_version_id, deleted_at";

    private final SqlSession session;

    SqlItemRepository(SqlSession session) {
        this.session = session;
    }

    @Override
    public void upsert(Item item) {
        session.runAtomically(() -> {
            session.update("INSERT OR REPLACE INTO item(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)",
                    item.id().value(), item.groupId().value(), item.logicalPath().value(), item.kind().name(),
                    item.currentVersion() == null ? null : item.currentVersion().value(), item.deletedAt());
            replaceVector(item.groupId(), item.logicalPath(), item.vector());
            session.update("INSERT OR REPLACE INTO item_change(group_id, logical_path) VALUES (?, ?)",
                    item.groupId().value(), item.logicalPath().value());
        });
    }

    @Override
    public Optional<Item> find(GroupId groupId, LogicalPath path) {
        return session.queryOne("SELECT " + COLUMNS + " FROM item WHERE group_id = ? AND logical_path = ?",
                        SqlItemRepository::mapRow, groupId.value(), path.value())
                .map(row -> row.toItem(loadVector(groupId, path)));
    }

    @Override
    public Optional<Item> findById(ItemId id) {
        return session.queryOne("SELECT " + COLUMNS + " FROM item WHERE id = ?",
                        SqlItemRepository::mapRow, id.value())
                .map(row -> row.toItem(loadVector(
                        GroupId.parse(row.groupId()), LogicalPath.of(row.logicalPath()))));
    }

    @Override
    public ItemPage page(GroupId groupId, String cursor, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        boolean hasCursor = cursor != null;
        String sql = "SELECT i.id, i.group_id, i.logical_path, i.kind, i.current_version_id, i.deleted_at, "
                + "v.node_id AS vector_node_id, v.counter AS vector_counter "
                + "FROM (SELECT " + COLUMNS + " FROM item WHERE group_id = ?"
                + (hasCursor ? " AND logical_path > ?" : "")
                + " ORDER BY logical_path LIMIT ?) i "
                + "LEFT JOIN item_vector v ON v.group_id = i.group_id AND v.logical_path = i.logical_path "
                + "ORDER BY i.logical_path, v.node_id";

        List<Object> arguments = new ArrayList<>();
        arguments.add(groupId.value());
        if (hasCursor) {
            arguments.add(cursor);
        }
        arguments.add(limit);

        List<Item> items = new ArrayList<>();
        ItemAccumulator accumulator = new ItemAccumulator(items);
        session.queryEach(sql, accumulator::accept, arguments.toArray());
        accumulator.finish();

        String nextCursor = items.size() == limit
                ? items.get(items.size() - 1).logicalPath().value()
                : null;
        return new ItemPage(Compat.copyList(items), nextCursor);
    }

    @Override
    public List<ChangedItem> changedSince(GroupId groupId, long sinceExclusive, long untilInclusive) {
        if (sinceExclusive < 0) {
            throw new IllegalArgumentException("sinceExclusive must not be negative");
        }
        String sql = "SELECT i.id, i.group_id, i.logical_path, i.kind, i.current_version_id, i.deleted_at, "
                + "c.seq AS change_seq, "
                + "v.node_id AS vector_node_id, v.counter AS vector_counter "
                + "FROM item_change c "
                + "JOIN item i ON i.group_id = c.group_id AND i.logical_path = c.logical_path "
                + "LEFT JOIN item_vector v ON v.group_id = i.group_id AND v.logical_path = i.logical_path "
                + "WHERE c.group_id = ? AND c.seq > ? AND c.seq <= ? "
                + "ORDER BY c.seq, v.node_id";

        List<ChangedItem> items = new ArrayList<>();
        ChangedItemAccumulator accumulator = new ChangedItemAccumulator(items);
        session.queryEach(sql, accumulator::accept,
                groupId.value(), sinceExclusive, untilInclusive);
        accumulator.finish();
        return Compat.copyList(items);
    }

    @Override
    public long currentSequence(GroupId groupId) {
        return session.queryOne(
                "SELECT COALESCE(MAX(seq), 0) AS max_seq FROM item_change WHERE group_id = ?",
                cursor -> cursor.getLong("max_seq"),
                groupId.value()).orElse(0L);
    }

    @Override
    public void delete(ItemId id) {
        session.runAtomically(() -> {
            Optional<String[]> keys = session.queryOne(
                    "SELECT group_id, logical_path FROM item WHERE id = ?",
                    cursor -> new String[] {cursor.getString("group_id"), cursor.getString("logical_path")},
                    id.value());
            if (keys.isEmpty()) {
                return;
            }
            session.update("DELETE FROM item_vector WHERE group_id = ? AND logical_path = ?",
                    keys.get()[0], keys.get()[1]);
            session.update("DELETE FROM item WHERE id = ?", id.value());
        });
    }

    private void replaceVector(GroupId groupId, LogicalPath path, VersionVector vector) {
        session.update("DELETE FROM item_vector WHERE group_id = ? AND logical_path = ?",
                groupId.value(), path.value());
        for (NodeId node : vector.nodes()) {
            session.update("INSERT INTO item_vector(group_id, logical_path, node_id, counter) VALUES (?, ?, ?, ?)",
                    groupId.value(), path.value(), node.value(), vector.counterOf(node));
        }
    }

    private VersionVector loadVector(GroupId groupId, LogicalPath path) {
        List<Map.Entry<NodeId, Long>> counters = session.query(
                "SELECT node_id, counter FROM item_vector WHERE group_id = ? AND logical_path = ?",
                cursor -> Map.entry(NodeId.of(cursor.getString("node_id")), cursor.getLong("counter")),
                groupId.value(), path.value());
        if (counters.isEmpty()) {
            return VersionVector.empty();
        }
        Map<NodeId, Long> vector = new LinkedHashMap<>();
        for (Map.Entry<NodeId, Long> entry : counters) {
            vector.put(entry.getKey(), entry.getValue());
        }
        return VersionVector.of(vector);
    }

    private static ItemRow mapRow(RowCursor cursor) {
        return new ItemRow(
                cursor.getString("id"),
                cursor.getString("group_id"),
                cursor.getString("logical_path"),
                cursor.getString("kind"),
                cursor.getString("current_version_id"),
                SqlValues.instant(cursor, "deleted_at"));
    }

    private record ItemRow(String id, String groupId, String logicalPath, String kind,
                           String currentVersionId, Instant deletedAt) {

        Item toItem(VersionVector vector) {
            return new Item(
                    ItemId.of(id),
                    GroupId.parse(groupId),
                    LogicalPath.of(logicalPath),
                    ItemKind.valueOf(kind),
                    currentVersionId == null ? null : VersionId.of(currentVersionId),
                    vector,
                    deletedAt);
        }
    }

    private static final class ChangedItemAccumulator {

        private final List<ChangedItem> items;
        private String currentPath;
        private long currentSequence;
        private ItemBuilder builder;

        private ChangedItemAccumulator(List<ChangedItem> items) {
            this.items = items;
        }

        void accept(RowCursor cursor) {
            String path = cursor.getString("logical_path");
            if (!path.equals(currentPath)) {
                finish();
                builder = new ItemBuilder(mapRow(cursor));
                currentPath = path;
                currentSequence = cursor.getLong("change_seq");
            }
            String node = cursor.getString("vector_node_id");
            if (node != null && builder != null) {
                builder.counters.put(NodeId.of(node), cursor.getLong("vector_counter"));
            }
        }

        void finish() {
            if (builder != null) {
                items.add(new ChangedItem(builder.build(), currentSequence));
                builder = null;
                currentPath = null;
            }
        }
    }

    private static final class ItemAccumulator {

        private final List<Item> items;
        private String currentPath;
        private ItemBuilder builder;

        private ItemAccumulator(List<Item> items) {
            this.items = items;
        }

        void accept(RowCursor cursor) {
            String path = cursor.getString("logical_path");
            if (!path.equals(currentPath)) {
                finish();
                builder = new ItemBuilder(mapRow(cursor));
                currentPath = path;
            }
            String node = cursor.getString("vector_node_id");
            if (node != null && builder != null) {
                builder.counters.put(NodeId.of(node), cursor.getLong("vector_counter"));
            }
        }

        void finish() {
            if (builder != null) {
                items.add(builder.build());
                builder = null;
                currentPath = null;
            }
        }
    }

    private static final class ItemBuilder {

        private final ItemRow row;
        private final Map<NodeId, Long> counters = new LinkedHashMap<>();

        private ItemBuilder(ItemRow row) {
            this.row = row;
        }

        private Item build() {
            VersionVector vector = counters.isEmpty() ? VersionVector.empty() : VersionVector.of(counters);
            return row.toItem(vector);
        }
    }
}
