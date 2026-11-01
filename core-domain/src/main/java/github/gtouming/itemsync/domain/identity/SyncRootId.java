package github.gtouming.itemsync.domain.identity;

import java.util.Objects;

public record SyncRootId(String value) {

    public SyncRootId {
        Objects.requireNonNull(value, "value");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("sync root id must not be blank");
        }
    }

    public static SyncRootId of(String value) {
        return new SyncRootId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
