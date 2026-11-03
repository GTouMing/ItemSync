package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.model.SyncGroup;
import github.gtouming.itemsync.domain.model.SyncRoot;
import github.gtouming.itemsync.store.metadata.MetadataStore.SyncGroupRepository;

import java.util.List;
import java.util.Optional;

final class SqlSyncGroupRepository implements SyncGroupRepository {

    private static final String COLUMNS =
            "id, name, physical_path, display_name, ignore_snapshot, logical_clock, created_at";

    private final SqlSession session;

    SqlSyncGroupRepository(SqlSession session) {
        this.session = session;
    }

    @Override
    public void insert(SyncGroup group) {
        session.update("INSERT OR REPLACE INTO sync_group(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                group.id().value(),
                group.name(),
                group.root().physicalPath(),
                group.root().displayName(),
                group.root().ignoreSnapshot(),
                group.logicalClock(),
                group.createdAt());
    }

    @Override
    public Optional<SyncGroup> find(GroupId id) {
        return session.queryOne("SELECT " + COLUMNS + " FROM sync_group WHERE id = ?",
                SqlSyncGroupRepository::map, id.value());
    }

    @Override
    public List<SyncGroup> findAll() {
        return session.query("SELECT " + COLUMNS + " FROM sync_group ORDER BY created_at, id",
                SqlSyncGroupRepository::map);
    }

    @Override
    public void updateLogicalClock(GroupId id, long logicalClock) {
        session.update("UPDATE sync_group SET logical_clock = ? WHERE id = ?", logicalClock, id.value());
    }

    @Override
    public void delete(GroupId id) {
        session.update("DELETE FROM sync_group WHERE id = ?", id.value());
    }

    private static SyncGroup map(RowCursor cursor) {
        return new SyncGroup(
                GroupId.parse(cursor.getString("id")),
                cursor.getString("name"),
                new SyncRoot(
                        cursor.getString("physical_path"),
                        cursor.getString("display_name"),
                        cursor.getString("ignore_snapshot")),
                cursor.getLong("logical_clock"),
                SqlValues.instant(cursor, "created_at"));
    }
}
