package github.gtouming.itemsync.domain.identity;

import java.util.Objects;

public record VersionId(String value) {

    public VersionId {
        Objects.requireNonNull(value, "value");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("version id must not be blank");
        }
    }

    public static VersionId of(String value) {
        return new VersionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
