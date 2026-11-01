package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.domain.identity.ConflictId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.Conflict;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.domain.replication.VersionVectorCodec;
import github.gtouming.itemsync.store.metadata.MetadataStore.ConflictRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

final class SqlConflictRepository implements ConflictRepository {

    private static final String COLUMNS =
            "id, item_id, local_version_id, remote_version_id, remote_vector, copy_path, "
                    + "detected_at, resolved_at";

    private final SqlSession session;

    SqlConflictRepository(SqlSession session) {
        this.session = session;
    }

    @Override
    public void insert(Conflict conflict) {
        session.update("INSERT OR REPLACE INTO conflict(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                conflict.id().value(),
                conflict.itemId().value(),
                conflict.localVersion() == null ? null : conflict.localVersion().value(),
                conflict.remoteVersion() == null ? null : conflict.remoteVersion().value(),
                VersionVectorCodec.encode(conflict.remoteVector()),
                conflict.copyPath() == null ? null : conflict.copyPath().value(),
                conflict.detectedAt(),
                conflict.resolvedAt());
    }

    @Override
    public Optional<Conflict> find(ConflictId id) {
        return session.queryOne("SELECT " + COLUMNS + " FROM conflict WHERE id = ?",
                SqlConflictRepository::map, id.value());
    }

    @Override
    public List<Conflict> findOpen(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        return session.query("SELECT " + COLUMNS + " FROM conflict WHERE resolved_at IS NULL "
                        + "ORDER BY detected_at, id LIMIT ?",
                SqlConflictRepository::map, limit);
    }

    @Override
    public List<Conflict> findByItem(ItemId itemId) {
        return session.query("SELECT " + COLUMNS + " FROM conflict WHERE item_id = ? ORDER BY detected_at, id",
                SqlConflictRepository::map, itemId.value());
    }

    @Override
    public void resolve(ConflictId id, Instant resolvedAt) {
        session.update("UPDATE conflict SET resolved_at = ? WHERE id = ?", resolvedAt, id.value());
    }

    private static Conflict map(RowCursor cursor) {
        String localVersionId = cursor.getString("local_version_id");
        String remoteVersionId = cursor.getString("remote_version_id");
        String copyPath = cursor.getString("copy_path");
        String remoteVector = cursor.getString("remote_vector");
        return new Conflict(
                ConflictId.of(cursor.getString("id")),
                ItemId.of(cursor.getString("item_id")),
                localVersionId == null ? null : VersionId.of(localVersionId),
                remoteVersionId == null ? null : VersionId.of(remoteVersionId),
                remoteVector == null ? VersionVector.empty() : VersionVectorCodec.decode(remoteVector),
                copyPath == null ? null : LogicalPath.of(copyPath),
                SqlValues.instant(cursor, "detected_at"),
                SqlValues.instant(cursor, "resolved_at"));
    }
}
