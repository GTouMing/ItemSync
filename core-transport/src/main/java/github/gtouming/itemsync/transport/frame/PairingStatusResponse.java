package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;

import java.util.Objects;

public record PairingStatusResponse(GroupId groupId, NodeId nodeId, DeviceId deviceId,
                                    String fingerprint, boolean trustsPeer) {

    public PairingStatusResponse {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (fingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
    }
}
