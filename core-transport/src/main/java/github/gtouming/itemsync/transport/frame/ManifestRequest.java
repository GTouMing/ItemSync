package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.replication.VersionVector;

import java.util.Objects;

public record ManifestRequest(GroupId groupId, VersionVector since, long sinceSequence,
                              String ignoreHash) {

    public ManifestRequest {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(since, "since");
        if (sinceSequence < 0) {
            throw new IllegalArgumentException("sinceSequence must not be negative");
        }
    }

    public ManifestRequest(GroupId groupId, VersionVector since, String ignoreHash) {
        this(groupId, since, 0L, ignoreHash);
    }
}
