package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.model.Tombstone;
import github.gtouming.itemsync.domain.replication.VersionVectorCodec;
import github.gtouming.itemsync.store.metadata.MetadataStore.TombstoneRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class SqlTombstoneRepository implements TombstoneRepository {

    private static final String COLUMNS = "item_id, vector_json, deleted_at, source_node_id";

    private final SqlSession session;

    SqlTombstoneRepository(SqlSession session) {
        this.session = session;
    }

    @Override
    public void upsert(Tombstone tombstone) {
        session.update("INSERT OR REPLACE INTO tombstone(" + COLUMNS + ") VALUES (?, ?, ?, ?)",
                tombstone.itemId().value(),
                VersionVectorCodec.encode(tombstone.vector()),
                tombstone.deletedAt(),
                tombstone.sourceNodeId().value());
    }

    @Override
    public Optional<Tombstone> find(ItemId itemId) {
        return session.queryOne("SELECT " + COLUMNS + " FROM tombstone WHERE item_id = ?",
                SqlTombstoneRepository::map, itemId.value());
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
        List<Object> arguments = new ArrayList<>();
        if (hasCursor) {
            arguments.add(cursor);
        }
        arguments.add(limit);
        return session.query(sql, SqlTombstoneRepository::map, arguments.toArray());
    }

    @Override
    public void delete(ItemId itemId) {
        session.update("DELETE FROM tombstone WHERE item_id = ?", itemId.value());
    }

    private static Tombstone map(RowCursor cursor) {
        return new Tombstone(
                ItemId.of(cursor.getString("item_id")),
                VersionVectorCodec.decode(cursor.getString("vector_json")),
                SqlValues.instant(cursor, "deleted_at"),
                NodeId.of(cursor.getString("source_node_id")));
    }
}
