package github.gtouming.itemsync.transport.discovery;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record DiscoveredPeer(NodeId nodeId, DeviceId deviceId, Set<GroupId> groupIds,
                             int protocolVersion, String fingerprint,
                             List<InetAddress> addresses, int port, int pairingPort) {

    public DiscoveredPeer {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(deviceId, "deviceId");
        groupIds = Compat.copySet(Objects.requireNonNull(groupIds, "groupIds"));
        if (groupIds.isEmpty()) {
            throw new IllegalArgumentException("group ids must not be empty");
        }
        if (groupIds.size() > PeerAdvertisement.MAX_GROUPS) {
            throw new IllegalArgumentException("at most " + PeerAdvertisement.MAX_GROUPS
                    + " group ids can be discovered, got " + groupIds.size());
        }
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
