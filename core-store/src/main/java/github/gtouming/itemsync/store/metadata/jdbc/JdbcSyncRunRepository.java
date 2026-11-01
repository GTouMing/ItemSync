package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.domain.error.ErrorCode;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.model.SyncRun;
import github.gtouming.itemsync.domain.model.SyncRunState;
import github.gtouming.itemsync.store.metadata.MetadataStore.SyncRunRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class JdbcSyncRunRepository implements SyncRunRepository {

    private static final String COLUMNS =
            "id, group_id, root_id, trigger_id, source_node_id, state, started_at, ended_at, stats_json, error_code";

    private final Connection connection;

    JdbcSyncRunRepository(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void insert(SyncRun run) {
        String sql = "INSERT OR REPLACE INTO sync_run(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, run);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("insert sync run", e);
        }
    }

    @Override
    public Optional<SyncRun> find(SyncRunId id) {
        String sql = "SELECT " + COLUMNS + " FROM sync_run WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("find sync run", e);
        }
    }

    @Override
    public void update(SyncRun run) {
        String sql = "UPDATE sync_run SET group_id = ?, root_id = ?, trigger_id = ?, source_node_id = ?, "
                + "state = ?, started_at = ?, ended_at = ?, stats_json = ?, error_code = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, run.groupId().value());
            ps.setString(2, run.rootId().value());
            ps.setString(3, run.triggerId() == null ? null : run.triggerId().value());
            ps.setString(4, run.sourceNodeId().value());
            ps.setString(5, run.state().name());
            JdbcSupport.setInstant(ps, 6, run.startedAt());
            JdbcSupport.setInstant(ps, 7, run.endedAt());
            ps.setString(8, run.statsJson());
            JdbcSupport.setEnum(ps, 9, run.errorCode());
            ps.setString(10, run.id().value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("update sync run", e);
        }
    }

    @Override
    public List<SyncRun> recent(SyncRootId rootId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        String sql = "SELECT " + COLUMNS + " FROM sync_run WHERE root_id = ? "
                + "ORDER BY started_at DESC, id DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, rootId.value());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<SyncRun> runs = new ArrayList<>();
                while (rs.next()) {
                    runs.add(map(rs));
                }
                return List.copyOf(runs);
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("list recent sync runs", e);
        }
    }

    private static void bind(PreparedStatement ps, SyncRun run) throws SQLException {
        ps.setString(1, run.id().value());
        ps.setString(2, run.groupId().value());
        ps.setString(3, run.rootId().value());
        ps.setString(4, run.triggerId() == null ? null : run.triggerId().value());
        ps.setString(5, run.sourceNodeId().value());
        ps.setString(6, run.state().name());
        JdbcSupport.setInstant(ps, 7, run.startedAt());
        JdbcSupport.setInstant(ps, 8, run.endedAt());
        ps.setString(9, run.statsJson());
        JdbcSupport.setEnum(ps, 10, run.errorCode());
    }

    private static SyncRun map(ResultSet rs) throws SQLException {
        String triggerId = rs.getString("trigger_id");
        return new SyncRun(
                SyncRunId.of(rs.getString("id")),
                GroupId.parse(rs.getString("group_id")),
                SyncRootId.of(rs.getString("root_id")),
                triggerId == null ? null : TriggerId.of(triggerId),
                NodeId.of(rs.getString("source_node_id")),
                SyncRunState.valueOf(rs.getString("state")),
                JdbcSupport.getInstant(rs, "started_at"),
                JdbcSupport.getInstant(rs, "ended_at"),
                rs.getString("stats_json"),
                JdbcSupport.getEnum(rs, "error_code", ErrorCode.class));
    }
}
