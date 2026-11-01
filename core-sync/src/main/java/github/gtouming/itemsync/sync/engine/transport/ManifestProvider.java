package github.gtouming.itemsync.sync.engine.transport;

import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.domain.replication.VersionVector;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
public interface ManifestProvider {

    List<ManifestEntry> manifest(SyncRootId rootId, VersionVector since) throws IOException;
}
