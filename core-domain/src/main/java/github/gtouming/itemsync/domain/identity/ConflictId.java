package github.gtouming.itemsync.domain.identity;

import java.util.Objects;

public record ConflictId(String value) {

    public ConflictId {
        Objects.requireNonNull(value, "value");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("conflict id must not be blank");
        }
    }

    public static ConflictId of(String value) {
        return new ConflictId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
