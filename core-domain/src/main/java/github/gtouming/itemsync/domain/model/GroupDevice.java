package github.gtouming.itemsync.domain.model;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;

import java.time.Instant;
import java.util.Objects;

/**
 * A device known inside one sync group. Trust itself lives in the trust store, keyed by
 * group and certificate fingerprint; this record only persists the device list.
 */
public record GroupDevice(GroupId groupId, DeviceId deviceId, String displayName,
                          String certFingerprint, Instant lastSeenAt, Instant knownAt) {

    public GroupDevice {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(certFingerprint, "certFingerprint");
        Objects.requireNonNull(knownAt, "knownAt");
        if (certFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("certificate fingerprint must not be blank");
        }
    }
}
