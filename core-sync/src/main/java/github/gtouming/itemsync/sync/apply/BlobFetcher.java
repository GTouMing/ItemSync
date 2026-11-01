package github.gtouming.itemsync.sync.apply;

import github.gtouming.itemsync.domain.identity.BlobHash;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface BlobFetcher {

    InputStream open(BlobHash hash) throws IOException;
}
