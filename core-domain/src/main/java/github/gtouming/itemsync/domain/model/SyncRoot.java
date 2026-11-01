package github.gtouming.itemsync.domain.model;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.SyncRootId;

import java.time.Instant;
import java.util.Objects;

public record SyncRoot(SyncRootId id, GroupId groupId, String physicalPath,
                       String displayName, String ignoreSnapshot, Instant createdAt) {

    public SyncRoot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(physicalPath, "physicalPath");
        Objects.requireNonNull(createdAt, "createdAt");
        if (physicalPath.isBlank()) {
            throw new IllegalArgumentException("physical path must not be blank");
        }
    }
}
