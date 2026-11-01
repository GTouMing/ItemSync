package github.gtouming.itemsync.domain.identity;

import java.util.Objects;

public record NodeId(String value) {

    public NodeId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("node id must not be blank");
        }
    }

    public static NodeId of(String value) {
        return new NodeId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
