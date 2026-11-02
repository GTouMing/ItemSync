package github.gtouming.itemsync.sync.engine.transport;

import github.gtouming.itemsync.domain.identity.SyncRootId;

import java.io.IOException;

@FunctionalInterface
public interface ManifestProvider {

    ManifestSnapshot manifest(SyncRootId rootId, long sinceSequence) throws IOException;
}
