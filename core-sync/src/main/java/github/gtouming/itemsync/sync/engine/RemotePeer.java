package github.gtouming.itemsync.sync.engine;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.domain.replication.VersionVector;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface RemotePeer {

    NodeId nodeId();

    List<ManifestEntry> manifest(SyncRootId rootId, VersionVector since) throws IOException;

    InputStream openBlob(BlobHash hash) throws IOException;
}
