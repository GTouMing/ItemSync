package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.model.Peer;
import github.gtouming.itemsync.domain.platform.DevicePlatform;
import github.gtouming.itemsync.store.metadata.MetadataStore.PeerRepository;

import java.util.List;
import java.util.Optional;

final class SqlPeerRepository implements PeerRepository {

    private static final String COLUMNS =
            "node_id, device_id, device_name, platform, cert_fingerprint, last_seen_at";

    private final SqlSession session;

    SqlPeerRepository(SqlSession session) {
        this.session = session;
    }

    @Override
    public void upsert(Peer peer) {
        session.update("INSERT OR REPLACE INTO peer(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)",
                peer.nodeId().value(),
                peer.deviceId().value(),
                peer.deviceName(),
                peer.platform() == null ? null : peer.platform().name(),
                peer.certFingerprint(),
                peer.lastSeenAt());
    }

    @Override
    public Optional<Peer> find(NodeId nodeId) {
        return session.queryOne("SELECT " + COLUMNS + " FROM peer WHERE node_id = ?",
                SqlPeerRepository::map, nodeId.value());
    }

    @Override
    public List<Peer> findAll() {
        return session.query("SELECT " + COLUMNS + " FROM peer ORDER BY node_id", SqlPeerRepository::map);
    }

    @Override
    public void delete(NodeId nodeId) {
        session.update("DELETE FROM peer WHERE node_id = ?", nodeId.value());
    }

    private static Peer map(RowCursor cursor) {
        return new Peer(
                NodeId.of(cursor.getString("node_id")),
                DeviceId.of(cursor.getString("device_id")),
                cursor.getString("device_name"),
                SqlValues.enumValue(cursor, "platform", DevicePlatform.class),
                cursor.getString("cert_fingerprint"),
                SqlValues.instant(cursor, "last_seen_at"));
    }
}
