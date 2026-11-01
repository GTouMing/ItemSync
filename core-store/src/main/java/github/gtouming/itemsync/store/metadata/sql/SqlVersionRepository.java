package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.replication.VersionVectorCodec;
import github.gtouming.itemsync.store.metadata.MetadataStore.VersionRepository;

import java.util.List;
import java.util.Optional;

final class SqlVersionRepository implements VersionRepository {

    private static final String COLUMNS =
            "id, item_id, blob_hash, size, mtime, source_node_id, vector_json, run_id, created_at";

    private final SqlSession session;

    SqlVersionRepository(SqlSession session) {
        this.session = session;
    }

    @Override
    public void insert(FileVersion version) {
        session.update("INSERT OR REPLACE INTO version(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                version.id().value(),
                version.itemId().value(),
                version.blobHash().value(),
                version.size(),
                version.mtime(),
                version.sourceNodeId().value(),
                VersionVectorCodec.encode(version.vector()),
                version.producedBy() == null ? null : version.producedBy().value(),
                version.createdAt());
    }

    @Override
    public Optional<FileVersion> find(VersionId id) {
        return session.queryOne("SELECT " + COLUMNS + " FROM version WHERE id = ?",
                SqlVersionRepository::map, id.value());
    }

    @Override
    public List<FileVersion> recent(ItemId itemId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        return session.query("SELECT " + COLUMNS + " FROM version WHERE item_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?",
                SqlVersionRepository::map, itemId.value(), limit);
    }

    @Override
    public int deleteOlderThan(ItemId itemId, int keepNewest) {
        if (keepNewest < 0) {
            throw new IllegalArgumentException("keepNewest must not be negative, got " + keepNewest);
        }
        return session.update("DELETE FROM version WHERE item_id = ? AND id NOT IN ("
                        + "SELECT id FROM version WHERE item_id = ? ORDER BY created_at DESC, id DESC LIMIT ?)",
                itemId.value(), itemId.value(), keepNewest);
    }

    private static FileVersion map(RowCursor cursor) {
        String runId = cursor.getString("run_id");
        return new FileVersion(
                VersionId.of(cursor.getString("id")),
                ItemId.of(cursor.getString("item_id")),
                BlobHash.parse(cursor.getString("blob_hash")),
                cursor.getLong("size"),
                SqlValues.instant(cursor, "mtime"),
                NodeId.of(cursor.getString("source_node_id")),
                VersionVectorCodec.decode(cursor.getString("vector_json")),
                runId == null ? null : SyncRunId.of(runId),
                SqlValues.instant(cursor, "created_at"));
    }
}
