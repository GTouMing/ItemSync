package github.gtouming.itemsync.transport.discovery;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;

import java.util.Objects;
import java.util.Set;

/**
 * One mDNS advertisement per device; {@code groupIds} lists the groups the device
 * currently has enabled on the shared sync port (ADR-0036).
 */
public record PeerAdvertisement(NodeId nodeId, DeviceId deviceId, Set<GroupId> groupIds,
                                int protocolVersion, String fingerprint, int port,
                                int pairingPort) {

    /** ADR-0035: at most five active groups per device. */
    public static final int MAX_GROUPS = 5;

    public PeerAdvertisement {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(deviceId, "deviceId");
        groupIds = Compat.copySet(Objects.requireNonNull(groupIds, "groupIds"));
        if (groupIds.isEmpty()) {
            throw new IllegalArgumentException("group ids must not be empty");
        }
        if (groupIds.size() > MAX_GROUPS) {
            throw new IllegalArgumentException("at most " + MAX_GROUPS
                    + " group ids can be advertised, got " + groupIds.size());
        }
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
        if (pairingPort < 0 || pairingPort > 65535) {
            throw new IllegalArgumentException("pairing port out of range: " + pairingPort);
        }
    }
}
