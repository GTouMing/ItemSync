package github.gtouming.itemsync.domain.model;

import github.gtouming.itemsync.domain.identity.GroupId;

import java.time.Instant;
import java.util.Objects;

public record SyncGroup(GroupId id, String name, SyncRoot root, long logicalClock,
                        Instant createdAt) {

    public SyncGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(createdAt, "createdAt");
        if (logicalClock < 0) {
            throw new IllegalArgumentException("logical clock must not be negative");
        }
    }
}
