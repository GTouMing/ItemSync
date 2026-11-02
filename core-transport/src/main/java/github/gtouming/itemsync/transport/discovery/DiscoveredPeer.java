package github.gtouming.itemsync.transport.discovery;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

public record DiscoveredPeer(NodeId nodeId, DeviceId deviceId, GroupId groupId,
                             int protocolVersion, String fingerprint,
                             List<InetAddress> addresses, int port, int pairingPort) {

    public DiscoveredPeer {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        addresses = Compat.copyList(Objects.requireNonNull(addresses, "addresses"));
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (pairingPort < 0 || pairingPort > 65535) {
            throw new IllegalArgumentException("pairing port out of range: " + pairingPort);
        }
    }
}
