package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.domain.model.TriggerType;
import github.gtouming.itemsync.store.metadata.MetadataStore.TriggerRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class JdbcTriggerRepository implements TriggerRepository {

    private static final String COLUMNS =
            "id, group_id, root_id, type, config_json, enabled, next_fire_at";

    private final Connection connection;

    JdbcTriggerRepository(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void upsert(Trigger trigger) {
        String sql = "INSERT OR REPLACE INTO trigger(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, trigger.id().value());
            ps.setString(2, trigger.groupId().value());
            ps.setString(3, trigger.rootId().value());
            ps.setString(4, trigger.type().name());
            ps.setString(5, trigger.configJson());
            ps.setInt(6, trigger.enabled() ? 1 : 0);
            JdbcSupport.setInstant(ps, 7, trigger.nextFireAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("upsert trigger", e);
        }
    }

    @Override
    public Optional<Trigger> find(TriggerId id) {
        String sql = "SELECT " + COLUMNS + " FROM trigger WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("find trigger", e);
        }
    }

    @Override
    public List<Trigger> findByGroup(GroupId groupId) {
        String sql = "SELECT " + COLUMNS + " FROM trigger WHERE group_id = ? ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, groupId.value());
            try (ResultSet rs = ps.executeQuery()) {
                List<Trigger> triggers = new ArrayList<>();
                while (rs.next()) {
                    triggers.add(map(rs));
                }
                return List.copyOf(triggers);
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("list triggers", e);
        }
    }

    @Override
    public List<Trigger> due(Instant now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        String sql = "SELECT " + COLUMNS + " FROM trigger "
                + "WHERE enabled = 1 AND next_fire_at IS NOT NULL AND next_fire_at <= ? "
                + "ORDER BY next_fire_at, id LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            JdbcSupport.setInstant(ps, 1, now);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Trigger> triggers = new ArrayList<>();
                while (rs.next()) {
                    triggers.add(map(rs));
                }
                return List.copyOf(triggers);
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("list due triggers", e);
        }
    }

    @Override
    public void delete(TriggerId id) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM trigger WHERE id = ?")) {
            ps.setString(1, id.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("delete trigger", e);
        }
    }

    private static Trigger map(ResultSet rs) throws SQLException {
        return new Trigger(
                TriggerId.of(rs.getString("id")),
                GroupId.parse(rs.getString("group_id")),
                SyncRootId.of(rs.getString("root_id")),
                TriggerType.valueOf(rs.getString("type")),
                rs.getString("config_json"),
                rs.getInt("enabled") != 0,
                JdbcSupport.getInstant(rs, "next_fire_at"));
    }
}
