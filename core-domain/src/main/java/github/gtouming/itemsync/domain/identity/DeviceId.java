package github.gtouming.itemsync.domain.identity;

import java.util.Objects;
import java.util.UUID;

public record DeviceId(String value) implements Comparable<DeviceId> {

    public DeviceId {
        Objects.requireNonNull(value, "value");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("device id must not be blank");
        }
    }

    public static DeviceId of(String value) {
        return new DeviceId(value);
    }

    public static DeviceId random() {
        return new DeviceId(UUID.randomUUID().toString());
    }

    @Override
    public int compareTo(DeviceId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
