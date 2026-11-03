package github.gtouming.itemsync.sync.engine;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.sync.engine.transport.ManifestSnapshot;

import java.io.IOException;
import java.io.InputStream;

public interface RemotePeer {

    NodeId nodeId();

    ManifestSnapshot manifest(GroupId groupId, long sinceSequence) throws IOException;

    InputStream openBlob(BlobHash hash) throws IOException;
}
