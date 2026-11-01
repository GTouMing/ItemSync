package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.identity.NodeId;

import java.util.Objects;

public record Hello(NodeId nodeId, int protocolVersion, String certificateFingerprint) {

    public Hello {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(certificateFingerprint, "certificateFingerprint");
        if (certificateFingerprint.isBlank()) {
            throw new IllegalArgumentException("certificate fingerprint must not be blank");
        }
    }
}
