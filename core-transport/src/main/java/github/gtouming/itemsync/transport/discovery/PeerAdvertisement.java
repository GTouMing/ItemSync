package github.gtouming.itemsync.transport.discovery;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;

import java.util.Objects;

public record PeerAdvertisement(NodeId nodeId, DeviceId deviceId, GroupId groupId,
                                int protocolVersion, String fingerprint, int port) {

    public PeerAdvertisement {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (fingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
        if (protocolVersion <= 0) {
            throw new IllegalArgumentException("protocol version must be positive, got " + protocolVersion);
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }
}
