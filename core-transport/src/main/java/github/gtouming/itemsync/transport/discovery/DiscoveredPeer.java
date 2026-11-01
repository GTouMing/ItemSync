package github.gtouming.itemsync.transport.discovery;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.NodeId;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

public record DiscoveredPeer(NodeId nodeId, DeviceId deviceId, int protocolVersion,
                             String fingerprint, List<InetAddress> addresses, int port) {

    public DiscoveredPeer {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        addresses = List.copyOf(Objects.requireNonNull(addresses, "addresses"));
    }
}
