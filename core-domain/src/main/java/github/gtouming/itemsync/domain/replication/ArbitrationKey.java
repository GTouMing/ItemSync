package github.gtouming.itemsync.domain.replication;

import github.gtouming.itemsync.domain.identity.DeviceId;

import java.util.Objects;

public record ArbitrationKey(LogicalClock logicalTime, DeviceId deviceId) implements Comparable<ArbitrationKey> {

    public ArbitrationKey {
        Objects.requireNonNull(logicalTime, "logicalTime");
        Objects.requireNonNull(deviceId, "deviceId");
    }

    @Override
    public int compareTo(ArbitrationKey other) {
        int byTime = logicalTime.compareTo(other.logicalTime);
        return byTime != 0 ? byTime : deviceId.compareTo(other.deviceId);
    }
}
