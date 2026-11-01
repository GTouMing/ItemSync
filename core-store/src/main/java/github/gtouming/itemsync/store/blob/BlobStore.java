package github.gtouming.itemsync.store.blob;

import github.gtouming.itemsync.domain.identity.BlobHash;

import java.io.IOException;
import java.io.InputStream;

public interface BlobStore {

    BlobHash put(InputStream content) throws IOException;

    InputStream open(BlobHash hash) throws IOException;

    boolean contains(BlobHash hash);

    long size(BlobHash hash) throws IOException;

    void delete(BlobHash hash) throws IOException;
}
