package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.error.ErrorCode;
import github.gtouming.itemsync.domain.identity.BlobHash;

import java.util.Objects;

public record BlobResult(BlobHash hash, boolean ok, long size, ErrorCode errorCode) {

    public BlobResult {
        Objects.requireNonNull(hash, "hash");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative, got " + size);
        }
        if (ok && errorCode != null) {
            throw new IllegalArgumentException("successful result must not carry an error code");
        }
        if (!ok && errorCode == null) {
            throw new IllegalArgumentException("failed result must carry an error code");
        }
    }
}
