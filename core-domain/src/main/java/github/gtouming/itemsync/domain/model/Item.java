package github.gtouming.itemsync.domain.model;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.replication.VersionVector;

import java.time.Instant;
import java.util.Objects;

public record Item(ItemId id, GroupId groupId, LogicalPath logicalPath, ItemKind kind,
                   VersionId currentVersion, VersionVector vector, Instant deletedAt) {

    public Item {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(logicalPath, "logicalPath");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(vector, "vector");
    }
}
