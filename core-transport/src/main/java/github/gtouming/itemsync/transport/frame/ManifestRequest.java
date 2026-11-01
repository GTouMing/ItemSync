package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.replication.VersionVector;

import java.util.Objects;

public record ManifestRequest(SyncRootId rootId, VersionVector since, String ignoreHash) {

    public ManifestRequest {
        Objects.requireNonNull(rootId, "rootId");
        Objects.requireNonNull(since, "since");
    }
}
