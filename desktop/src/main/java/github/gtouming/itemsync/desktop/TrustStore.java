package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.sync.trust.FileTrustStore;

import java.io.IOException;
import java.nio.file.Path;

public final class TrustStore extends FileTrustStore {

    public TrustStore(Path dataDir) throws IOException {
        super(dataDir);
    }
}
