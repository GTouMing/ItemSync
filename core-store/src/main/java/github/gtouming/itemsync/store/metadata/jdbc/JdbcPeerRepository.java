package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.model.Peer;
import github.gtouming.itemsync.domain.platform.DevicePlatform;
import github.gtouming.itemsync.store.metadata.MetadataStore.PeerRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class JdbcPeerRepository implements PeerRepository {

    private static final String COLUMNS =
            "node_id, device_id, device_name, platform, cert_fingerprint, last_seen_at";

    private final Connection connection;

    JdbcPeerRepository(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void upsert(Peer peer) {
        String sql = "INSERT OR REPLACE INTO peer(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, peer.nodeId().value());
            ps.setString(2, peer.deviceId().value());
            ps.setString(3, peer.deviceName());
            JdbcSupport.setEnum(ps, 4, peer.platform());
            ps.setString(5, peer.certFingerprint());
            JdbcSupport.setInstant(ps, 6, peer.lastSeenAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("upsert peer", e);
        }
    }

    @Override
    public Optional<Peer> find(NodeId nodeId) {
        String sql = "SELECT " + COLUMNS + " FROM peer WHERE node_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, nodeId.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw JdbcSupport.fail("find peer", e);
        }
    }

    @Override
    public List<Peer> findAll() {
        String sql = "SELECT " + COLUMNS + " FROM peer ORDER BY node_id";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            List<Peer> peers = new ArrayList<>();
            while (rs.next()) {
                peers.add(map(rs));
            }
            return List.copyOf(peers);
        } catch (SQLException e) {
            throw JdbcSupport.fail("list peers", e);
        }
    }

    @Override
    public void delete(NodeId nodeId) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM peer WHERE node_id = ?")) {
            ps.setString(1, nodeId.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.fail("delete peer", e);
        }
    }

    private static Peer map(ResultSet rs) throws SQLException {
        return new Peer(
                NodeId.of(rs.getString("node_id")),
                DeviceId.of(rs.getString("device_id")),
                rs.getString("device_name"),
                JdbcSupport.getEnum(rs, "platform", DevicePlatform.class),
                rs.getString("cert_fingerprint"),
                JdbcSupport.getInstant(rs, "last_seen_at"));
    }
}
