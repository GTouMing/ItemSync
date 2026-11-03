package github.gtouming.itemsync.sync.engine.transport;

import github.gtouming.itemsync.domain.identity.GroupId;

import java.io.IOException;

@FunctionalInterface
public interface ManifestProvider {

    ManifestSnapshot manifest(GroupId groupId, long sinceSequence) throws IOException;
}
