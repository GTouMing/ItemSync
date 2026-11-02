package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.replication.VersionVector;

import java.util.Objects;

public record ManifestRequest(SyncRootId rootId, VersionVector since, long sinceSequence,
                              String ignoreHash) {

    public ManifestRequest {
        Objects.requireNonNull(rootId, "rootId");
        Objects.requireNonNull(since, "since");
        if (sinceSequence < 0) {
            throw new IllegalArgumentException("sinceSequence must not be negative");
        }
    }

    public ManifestRequest(SyncRootId rootId, VersionVector since, String ignoreHash) {
        this(rootId, since, 0L, ignoreHash);
    }
}
