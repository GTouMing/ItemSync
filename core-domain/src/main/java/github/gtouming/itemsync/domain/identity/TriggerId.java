package github.gtouming.itemsync.domain.identity;

import java.util.Objects;

public record TriggerId(String value) {

    public TriggerId {
        Objects.requireNonNull(value, "value");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("trigger id must not be blank");
        }
    }

    public static TriggerId of(String value) {
        return new TriggerId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
