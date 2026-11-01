package github.gtouming.itemsync.domain.model;

import github.gtouming.itemsync.domain.identity.ConflictId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.VersionId;

import java.time.Instant;
import java.util.Objects;

public record Conflict(ConflictId id, ItemId itemId, VersionId localVersion,
                       VersionId remoteVersion, LogicalPath copyPath,
                       Instant detectedAt, Instant resolvedAt) {

    public Conflict {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(detectedAt, "detectedAt");
    }
}
