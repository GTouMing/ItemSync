package github.gtouming.itemsync.domain.model;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.replication.VersionVector;

import java.time.Instant;
import java.util.Objects;

public record FileVersion(VersionId id, ItemId itemId, BlobHash blobHash, long size,
                          Instant mtime, NodeId sourceNodeId, VersionVector vector,
                          SyncRunId producedBy, Instant createdAt) {

    public FileVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(blobHash, "blobHash");
        Objects.requireNonNull(mtime, "mtime");
        Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        Objects.requireNonNull(vector, "vector");
        Objects.requireNonNull(createdAt, "createdAt");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative, got " + size);
        }
    }
}
