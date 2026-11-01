package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.model.SyncRoot;
import github.gtouming.itemsync.store.metadata.MetadataStore.SyncRootRepository;

import java.util.List;
import java.util.Optional;

final class SqlSyncRootRepository implements SyncRootRepository {

    private static final String COLUMNS =
            "id, group_id, physical_path, display_name, ignore_snapshot, created_at";

    private final SqlSession session;

    SqlSyncRootRepository(SqlSession session) {
        this.session = session;
    }

    @Override
    public void insert(SyncRoot root) {
        session.update("INSERT OR REPLACE INTO sync_root(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)",
                root.id().value(), root.groupId().value(), root.physicalPath(),
                root.displayName(), root.ignoreSnapshot(), root.createdAt());
    }

    @Override
    public Optional<SyncRoot> find(SyncRootId id) {
        return session.queryOne("SELECT " + COLUMNS + " FROM sync_root WHERE id = ?",
                SqlSyncRootRepository::map, id.value());
    }

    @Override
    public List<SyncRoot> findByGroup(GroupId groupId) {
        return session.query("SELECT " + COLUMNS + " FROM sync_root WHERE group_id = ? ORDER BY id",
                SqlSyncRootRepository::map, groupId.value());
    }

    @Override
    public void updateIgnoreSnapshot(SyncRootId id, String ignoreSnapshot) {
        session.update("UPDATE sync_root SET ignore_snapshot = ? WHERE id = ?", ignoreSnapshot, id.value());
    }

    @Override
    public void delete(SyncRootId id) {
        session.update("DELETE FROM sync_root WHERE id = ?", id.value());
    }

    private static SyncRoot map(RowCursor cursor) {
        return new SyncRoot(
                SyncRootId.of(cursor.getString("id")),
                GroupId.parse(cursor.getString("group_id")),
                cursor.getString("physical_path"),
                cursor.getString("display_name"),
                cursor.getString("ignore_snapshot"),
                SqlValues.instant(cursor, "created_at"));
    }
}
