package github.gtouming.itemsync.transport.transfer;

import github.gtouming.itemsync.domain.identity.BlobHash;

import java.io.IOException;
import java.io.InputStream;

public interface BlobSource {

    boolean exists(BlobHash hash);

    long size(BlobHash hash) throws IOException;

    InputStream open(BlobHash hash) throws IOException;
}
