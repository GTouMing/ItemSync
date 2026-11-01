package github.gtouming.itemsync.transport.transfer;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.transport.frame.BlobGet;
import github.gtouming.itemsync.transport.frame.ChunkPayload;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.net.FrameConnection;
import github.gtouming.itemsync.transport.net.FrameHandler;
import github.gtouming.itemsync.transport.net.TlsTransportClient;
import github.gtouming.itemsync.transport.net.TlsTransportServer;
import github.gtouming.itemsync.transport.tls.TlsIdentity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlobTransferTest {

    private static final int CHUNK = ChunkPayload.CHUNK_SIZE;

    private static TlsIdentity serverIdentity;
    private static TlsIdentity clientIdentity;
    private static byte[] content;
    private static BlobHash hash;

    @BeforeAll
    static void setUp() throws Exception {
        serverIdentity = TlsIdentity.generate("server");
        clientIdentity = TlsIdentity.generate("client");
        content = new byte[CHUNK * 2 + CHUNK / 2];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        hash = BlobHash.ofSha256(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @Test
    void resumesInterruptedDownload(@TempDir Path temp) throws Exception {
        Path target = temp.resolve("blob.bin");
        BlobSource source = source();
        AtomicInteger requests = new AtomicInteger();

        FrameHandler serverHandler = (connection, frame) -> {
            if (frame.type() != FrameType.BLOB_GET) {
                return;
            }
            BlobGet request = FramePayloads.decodeBlobGet(frame.payload());
            if (requests.incrementAndGet() == 1) {
                ChunkedBlobServer.serve(connection, request, source, 1);
                connection.close();
            } else {
                ChunkedBlobServer.serve(connection, request, source);
            }
        };

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, clientIdentity.fingerprint(), 0, serverHandler)) {
            server.start();

            BlobDownload first = new BlobDownload(target, hash);
            AtomicReference<IOException> failure = new AtomicReference<>();
            CountDownLatch closed = new CountDownLatch(1);
            FrameHandler firstHandler = new FrameHandler() {
                @Override
                public void onFrame(FrameConnection connection, Frame frame) {
                    try {
                        first.apply(frame);
                    } catch (IOException e) {
                        failure.compareAndSet(null, e);
                    }
                }

                @Override
                public void onClosed(FrameConnection connection) {
                    closed.countDown();
                }
            };

            try (TlsTransportClient client = TlsTransportClient.connect(
                    "127.0.0.1", server.port(), clientIdentity, serverIdentity.fingerprint(), firstHandler)) {
                client.send(new Frame(FrameType.BLOB_GET, FramePayloads.encodeBlobGet(first.request())));
                assertTrue(closed.await(10, TimeUnit.SECONDS));
            }
            first.close();

            assertNull(failure.get());
            assertEquals((long) CHUNK, first.offset());

            BlobDownload resumed = new BlobDownload(target, hash);
            assertEquals((long) CHUNK, resumed.offset());
            CountDownLatch completed = new CountDownLatch(1);
            FrameHandler resumedHandler = new FrameHandler() {
                @Override
                public void onFrame(FrameConnection connection, Frame frame) {
                    try {
                        resumed.apply(frame);
                        if (resumed.isCompleted()) {
                            completed.countDown();
                        }
                    } catch (IOException e) {
                        failure.compareAndSet(null, e);
                        completed.countDown();
                    }
                }
            };

            try (TlsTransportClient client = TlsTransportClient.connect(
                    "127.0.0.1", server.port(), clientIdentity, serverIdentity.fingerprint(), resumedHandler)) {
                client.send(new Frame(FrameType.BLOB_GET, FramePayloads.encodeBlobGet(resumed.request())));
                assertTrue(completed.await(10, TimeUnit.SECONDS));
            }

            assertNull(failure.get());
            assertEquals(hash, resumed.actualHash());
            assertTrue(resumed.verify());
            resumed.close();
        }
    }

    @Test
    void reportsMissingBlob(@TempDir Path temp) throws Exception {
        BlobHash missing = BlobHash.parse("b".repeat(64));
        BlobSource empty = new BlobSource() {
            @Override
            public boolean exists(BlobHash hash) {
                return false;
            }

            @Override
            public long size(BlobHash hash) {
                throw new UnsupportedOperationException("missing blob has no size");
            }

            @Override
            public InputStream open(BlobHash hash) {
                throw new UnsupportedOperationException("missing blob cannot be opened");
            }
        };

        FrameHandler serverHandler = (connection, frame) -> {
            if (frame.type() == FrameType.BLOB_GET) {
                ChunkedBlobServer.serve(connection, FramePayloads.decodeBlobGet(frame.payload()), empty);
            }
        };

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, clientIdentity.fingerprint(), 0, serverHandler)) {
            server.start();

            Path target = temp.resolve("missing.bin");
            BlobDownload download = new BlobDownload(target, missing);
            AtomicReference<IOException> failure = new AtomicReference<>();
            CountDownLatch result = new CountDownLatch(1);
            FrameHandler handler = (connection, frame) -> {
                try {
                    download.apply(frame);
                } catch (IOException e) {
                    failure.compareAndSet(null, e);
                } finally {
                    if (frame.type() == FrameType.BLOB_RESULT) {
                        result.countDown();
                    }
                }
            };

            try (TlsTransportClient client = TlsTransportClient.connect(
                    "127.0.0.1", server.port(), clientIdentity, serverIdentity.fingerprint(), handler)) {
                client.send(new Frame(FrameType.BLOB_GET, FramePayloads.encodeBlobGet(download.request())));
                assertTrue(result.await(10, TimeUnit.SECONDS));
            }
            download.close();

            assertNotNull(failure.get());
            assertTrue(failure.get().getMessage().contains("BLOB_NOT_FOUND"));
        }
    }

    private static BlobSource source() {
        return new BlobSource() {
            @Override
            public boolean exists(BlobHash candidate) {
                return candidate.equals(hash);
            }

            @Override
            public long size(BlobHash candidate) {
                return content.length;
            }

            @Override
            public InputStream open(BlobHash candidate) {
                return new ByteArrayInputStream(content);
            }
        };
    }
}
