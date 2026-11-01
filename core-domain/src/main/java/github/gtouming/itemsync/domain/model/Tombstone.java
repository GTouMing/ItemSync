package github.gtouming.itemsync.domain.model;

import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.replication.VersionVector;

import java.time.Instant;
import java.util.Objects;

public record Tombstone(ItemId itemId, VersionVector vector, Instant deletedAt, NodeId sourceNodeId) {

    public Tombstone {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(vector, "vector");
        Objects.requireNonNull(deletedAt, "deletedAt");
        Objects.requireNonNull(sourceNodeId, "sourceNodeId");
    }
}
