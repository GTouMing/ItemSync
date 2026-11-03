package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.model.GroupDevice;
import github.gtouming.itemsync.store.metadata.MetadataStore.GroupDeviceRepository;

import java.util.List;
import java.util.Optional;

final class SqlGroupDeviceRepository implements GroupDeviceRepository {

    private static final String COLUMNS =
            "group_id, device_id, display_name, cert_fingerprint, last_seen_at, known_at";

    private final SqlSession session;

    SqlGroupDeviceRepository(SqlSession session) {
        this.session = session;
    }

    @Override
    public void upsert(GroupDevice device) {
        session.update("INSERT OR REPLACE INTO group_device(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)",
                device.groupId().value(),
                device.deviceId().value(),
                device.displayName(),
                device.certFingerprint(),
                device.lastSeenAt(),
                device.knownAt());
    }

    @Override
    public Optional<GroupDevice> find(GroupId groupId, DeviceId deviceId) {
        return session.queryOne("SELECT " + COLUMNS + " FROM group_device "
                        + "WHERE group_id = ? AND device_id = ?",
                SqlGroupDeviceRepository::map, groupId.value(), deviceId.value());
    }

    @Override
    public List<GroupDevice> findByGroup(GroupId groupId) {
        return session.query("SELECT " + COLUMNS + " FROM group_device WHERE group_id = ? "
                        + "ORDER BY device_id",
                SqlGroupDeviceRepository::map, groupId.value());
    }

    @Override
    public void delete(GroupId groupId, DeviceId deviceId) {
        session.update("DELETE FROM group_device WHERE group_id = ? AND device_id = ?",
                groupId.value(), deviceId.value());
    }

    private static GroupDevice map(RowCursor cursor) {
        return new GroupDevice(
                GroupId.parse(cursor.getString("group_id")),
                DeviceId.of(cursor.getString("device_id")),
                cursor.getString("display_name"),
                cursor.getString("cert_fingerprint"),
                SqlValues.instant(cursor, "last_seen_at"),
                SqlValues.instant(cursor, "known_at"));
    }
}
