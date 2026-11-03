package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;

import java.util.Objects;

/**
 * Announces a sync connection. {@code groupId} repeats the group the TLS SNI selected so
 * the shared sync port can reject a connection whose frames name another group (ADR-0036).
 */
public record Hello(NodeId nodeId, int protocolVersion, String certificateFingerprint,
                    GroupId groupId) {

    public Hello {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(certificateFingerprint, "certificateFingerprint");
        Objects.requireNonNull(groupId, "groupId");
        if (certificateFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("certificate fingerprint must not be blank");
        }
    }
}
