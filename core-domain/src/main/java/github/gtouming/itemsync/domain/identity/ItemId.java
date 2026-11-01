package github.gtouming.itemsync.domain.identity;

import java.util.Objects;

public record ItemId(String value) {

    public ItemId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("item id must not be blank");
        }
    }

    public static ItemId of(String value) {
        return new ItemId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
