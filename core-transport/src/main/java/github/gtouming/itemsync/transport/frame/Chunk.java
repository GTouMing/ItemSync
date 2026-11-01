package github.gtouming.itemsync.transport.frame;

import java.util.Objects;

public record Chunk(long offset, byte[] data) {

    public Chunk {
        Objects.requireNonNull(data, "data");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, got " + offset);
        }
    }
}
