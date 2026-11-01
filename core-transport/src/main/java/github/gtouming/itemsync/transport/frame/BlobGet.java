package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.identity.BlobHash;

import java.util.Objects;

public record BlobGet(BlobHash hash, long offset) {

    public BlobGet {
        Objects.requireNonNull(hash, "hash");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, got " + offset);
        }
    }
}
