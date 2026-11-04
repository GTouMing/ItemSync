package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.domain.error.ErrorCode;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.model.SyncRun;
import github.gtouming.itemsync.domain.model.SyncRunState;
import github.gtouming.itemsync.store.metadata.MetadataStore.SyncRunRepository;

import java.util.List;
import java.util.Optional;

final class SqlSyncRunRepository implements SyncRunRepository {

    private static final String COLUMNS =
            "id, group_id, trigger_id, source_node_id, state, started_at, ended_at, stats_json, error_code";

    private final SqlSession session;

    SqlSyncRunRepository(SqlSession session) {
        this.session = session;
    }

    @Override
    public void insert(SyncRun run) {
        session.update("INSERT OR REPLACE INTO sync_run(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                run.id().value(),
                run.groupId().value(),
                run.triggerId() == null ? null : run.triggerId().value(),
                run.sourceNodeId().value(),
                run.state().name(),
                run.startedAt(),
                run.endedAt(),
                run.statsJson(),
                run.errorCode() == null ? null : run.errorCode().name());
    }

    @Override
    public Optional<SyncRun> find(SyncRunId id) {
        return session.queryOne("SELECT " + COLUMNS + " FROM sync_run WHERE id = ?",
                SqlSyncRunRepository::map, id.value());
    }

    @Override
    public void update(SyncRun run) {
        session.update("UPDATE sync_run SET group_id = ?, trigger_id = ?, source_node_id = ?, "
                        + "state = ?, started_at = ?, ended_at = ?, stats_json = ?, error_code = ? WHERE id = ?",
                run.groupId().value(),
                run.triggerId() == null ? null : run.triggerId().value(),
                run.sourceNodeId().value(),
                run.state().name(),
                run.startedAt(),
                run.endedAt(),
                run.statsJson(),
                run.errorCode() == null ? null : run.errorCode().name(),
                run.id().value());
    }

    @Override
    public List<SyncRun> recent(GroupId groupId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        return session.query("SELECT " + COLUMNS + " FROM sync_run WHERE group_id = ? "
                        + "ORDER BY started_at DESC, id DESC LIMIT ?",
                SqlSyncRunRepository::map, groupId.value(), limit);
    }

    @Override
    public void deleteByGroup(GroupId groupId) {
        session.update("DELETE FROM sync_run WHERE group_id = ?", groupId.value());
    }

    private static SyncRun map(RowCursor cursor) {
        String triggerId = cursor.getString("trigger_id");
        return new SyncRun(
                SyncRunId.of(cursor.getString("id")),
                GroupId.parse(cursor.getString("group_id")),
                triggerId == null ? null : TriggerId.of(triggerId),
                NodeId.of(cursor.getString("source_node_id")),
                SqlValues.enumValue(cursor, "state", SyncRunState.class),
                SqlValues.instant(cursor, "started_at"),
                SqlValues.instant(cursor, "ended_at"),
                cursor.getString("stats_json"),
                SqlValues.enumValue(cursor, "error_code", ErrorCode.class));
    }
}
