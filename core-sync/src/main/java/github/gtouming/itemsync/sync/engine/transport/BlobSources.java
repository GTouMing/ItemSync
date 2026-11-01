package github.gtouming.itemsync.sync.engine.transport;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.store.blob.BlobStore;
import github.gtouming.itemsync.transport.transfer.BlobSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class BlobSources {

    private BlobSources() {
    }

    public static BlobSource of(BlobStore store) {
        Objects.requireNonNull(store, "store");
        return new BlobSource() {
            @Override
            public boolean exists(BlobHash hash) {
                return store.contains(hash);
            }

            @Override
            public long size(BlobHash hash) throws IOException {
                return store.size(hash);
            }

            @Override
            public InputStream open(BlobHash hash) throws IOException {
                return store.open(hash);
            }
        };
    }
}
