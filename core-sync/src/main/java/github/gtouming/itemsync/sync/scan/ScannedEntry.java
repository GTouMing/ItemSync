package github.gtouming.itemsync.sync.scan;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.model.ItemKind;

import java.time.Instant;
import java.util.Objects;

public record ScannedEntry(LogicalPath path, ItemKind kind, BlobHash hash, long size, Instant mtime) {

    public ScannedEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(mtime, "mtime");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative, got " + size);
        }
        if (kind == ItemKind.FILE && hash == null) {
            throw new IllegalArgumentException("file entry must carry a blob hash: " + path);
        }
    }
}
