package github.gtouming.itemsync.domain.model;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.platform.DevicePlatform;

import java.time.Instant;
import java.util.Objects;

public record Peer(NodeId nodeId, DeviceId deviceId, String deviceName, DevicePlatform platform,
                   String certFingerprint, Instant lastSeenAt) {

    public Peer {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(certFingerprint, "certFingerprint");
        if (certFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("certificate fingerprint must not be blank");
        }
    }
}
