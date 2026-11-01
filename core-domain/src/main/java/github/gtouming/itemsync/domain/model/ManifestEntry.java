package github.gtouming.itemsync.domain.model;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.replication.VersionVector;

import java.time.Instant;
import java.util.Objects;

public record ManifestEntry(LogicalPath path, ItemKind kind, BlobHash hash, long size,
                            Instant mtime, VersionVector vector, boolean deleted) {

    public ManifestEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(mtime, "mtime");
        Objects.requireNonNull(vector, "vector");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative, got " + size);
        }
        if (!deleted && kind == ItemKind.FILE && hash == null) {
            throw new IllegalArgumentException("live file entry must carry a blob hash: " + path);
        }
        if (kind == ItemKind.DIRECTORY && hash != null) {
            throw new IllegalArgumentException("directory entry must not carry a blob hash: " + path);
        }
    }
}
