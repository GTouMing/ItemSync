package github.gtouming.itemsync.domain.identity;

import java.util.Objects;

public record SyncRunId(String value) {

    public SyncRunId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("sync run id must not be blank");
        }
    }

    public static SyncRunId of(String value) {
        return new SyncRunId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
