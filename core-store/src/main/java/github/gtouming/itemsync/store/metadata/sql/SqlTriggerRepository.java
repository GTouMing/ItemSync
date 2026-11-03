package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.domain.model.TriggerType;
import github.gtouming.itemsync.store.metadata.MetadataStore.TriggerRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

final class SqlTriggerRepository implements TriggerRepository {

    private static final String COLUMNS =
            "id, group_id, type, config_json, enabled, next_fire_at";

    private final SqlSession session;

    SqlTriggerRepository(SqlSession session) {
        this.session = session;
    }

    @Override
    public void upsert(Trigger trigger) {
        session.update("INSERT OR REPLACE INTO trigger(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)",
                trigger.id().value(),
                trigger.groupId().value(),
                trigger.type().name(),
                trigger.configJson(),
                trigger.enabled(),
                trigger.nextFireAt());
    }

    @Override
    public Optional<Trigger> find(TriggerId id) {
        return session.queryOne("SELECT " + COLUMNS + " FROM trigger WHERE id = ?",
                SqlTriggerRepository::map, id.value());
    }

    @Override
    public List<Trigger> findByGroup(GroupId groupId) {
        return session.query("SELECT " + COLUMNS + " FROM trigger WHERE group_id = ? ORDER BY id",
                SqlTriggerRepository::map, groupId.value());
    }

    @Override
    public List<Trigger> due(Instant now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        return session.query("SELECT " + COLUMNS + " FROM trigger "
                        + "WHERE enabled = 1 AND next_fire_at IS NOT NULL AND next_fire_at <= ? "
                        + "ORDER BY next_fire_at, id LIMIT ?",
                SqlTriggerRepository::map, now, limit);
    }

    @Override
    public void delete(TriggerId id) {
        session.update("DELETE FROM trigger WHERE id = ?", id.value());
    }

    private static Trigger map(RowCursor cursor) {
        return new Trigger(
                TriggerId.of(cursor.getString("id")),
                GroupId.parse(cursor.getString("group_id")),
                SqlValues.enumValue(cursor, "type", TriggerType.class),
                cursor.getString("config_json"),
                cursor.getInt("enabled") != 0,
                SqlValues.instant(cursor, "next_fire_at"));
    }
}
