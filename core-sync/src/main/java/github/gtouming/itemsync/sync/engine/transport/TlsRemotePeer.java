package github.gtouming.itemsync.sync.engine.transport;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.sync.engine.RemotePeer;
import github.gtouming.itemsync.transport.frame.BlobGet;
import github.gtouming.itemsync.transport.frame.ErrorMessage;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.frame.ManifestEnd;
import github.gtouming.itemsync.transport.frame.ManifestPage;
import github.gtouming.itemsync.transport.frame.ManifestRequest;
import github.gtouming.itemsync.transport.net.FrameConnection;
import github.gtouming.itemsync.transport.net.FrameHandler;
import github.gtouming.itemsync.transport.net.HeartbeatHandler;
import github.gtouming.itemsync.transport.net.TlsTransportClient;
import github.gtouming.itemsync.transport.tls.TlsIdentity;
import github.gtouming.itemsync.transport.transfer.BlobDownload;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class TlsRemotePeer implements RemotePeer, FrameHandler, AutoCloseable {

    private static final Duration DEFAULT_PROGRESS_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

    private final NodeId remoteNodeId;
    private final Path tempDir;
    private final Duration timeout;
    private final Object manifestLock = new Object();
    private final Object downloadLock = new Object();

    private volatile ManifestCollector manifestCollector;
    private volatile BlobCollector activeDownload;
    private volatile TlsTransportClient client;
    private volatile FrameConnection connection;
    private volatile boolean closed;

    private TlsRemotePeer(NodeId remoteNodeId, Path tempDir, Duration timeout) throws IOException {
        this.remoteNodeId = Objects.requireNonNull(remoteNodeId, "remoteNodeId");
        this.tempDir = Objects.requireNonNull(tempDir, "tempDir");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        Files.createDirectories(tempDir);
    }

    public static TlsRemotePeer connect(String host, int port, TlsIdentity identity,
                                        String expectedServerFingerprint, NodeId remoteNodeId,
                                        Path tempDir) throws IOException {
        return connect(host, port, identity, expectedServerFingerprint, remoteNodeId,
                tempDir, DEFAULT_PROGRESS_TIMEOUT, HEARTBEAT_INTERVAL);
    }

    public static TlsRemotePeer connect(String host, int port, TlsIdentity identity,
                                        String expectedServerFingerprint, NodeId remoteNodeId,
                                        Path tempDir, Duration timeout) throws IOException {
        return connect(host, port, identity, expectedServerFingerprint, remoteNodeId,
                tempDir, timeout, HEARTBEAT_INTERVAL);
    }

    public static TlsRemotePeer connect(String host, int port, TlsIdentity identity,
                                        String expectedServerFingerprint, NodeId remoteNodeId,
                                        Path tempDir, Duration timeout, Duration heartbeat)
            throws IOException {
        TlsRemotePeer peer = new TlsRemotePeer(remoteNodeId, tempDir, timeout);
        TlsTransportClient client = TlsTransportClient.connect(
                host, port, identity, expectedServerFingerprint,
                new HeartbeatHandler(peer), CONNECT_TIMEOUT, heartbeat);
        peer.bind(client);
        return peer;
    }

    @Override
    public NodeId nodeId() {
        return remoteNodeId;
    }

    public void send(Frame frame) throws IOException {
        connection().send(frame);
    }

    @Override
    public ManifestSnapshot manifest(GroupId groupId, long sinceSequence) throws IOException {
        ManifestCollector collector = new ManifestCollector(timeout);
        synchronized (manifestLock) {
            if (manifestCollector != null) {
                throw new IOException("manifest request already in flight");
            }
            manifestCollector = collector;
        }
        try {
            connection().send(new Frame(FrameType.MANIFEST_REQUEST,
                    FramePayloads.encodeManifestRequest(new ManifestRequest(
                            groupId, VersionVector.empty(), sinceSequence, null))));
            return collector.await();
        } finally {
            synchronized (manifestLock) {
                manifestCollector = null;
            }
        }
    }

    @Override
    public InputStream openBlob(BlobHash hash) throws IOException {
        BlobCollector collector = new BlobCollector(tempDir, hash, timeout);
        synchronized (downloadLock) {
            if (activeDownload != null) {
                collector.fail(new IOException("blob download already in flight"));
                throw new IOException("blob download already in flight");
            }
            activeDownload = collector;
        }
        try {
            connection().send(new Frame(FrameType.BLOB_GET,
                    FramePayloads.encodeBlobGet(new BlobGet(hash, 0))));
            collector.await();
            return collector.openStream();
        } finally {
            synchronized (downloadLock) {
                activeDownload = null;
            }
        }
    }

    @Override
    public void onFrame(FrameConnection connection, Frame frame) {
        try {
            switch (frame.type()) {
                case MANIFEST_PAGE -> {
                    ManifestCollector collector = manifestCollector;
                    if (collector != null) {
                        collector.page(FramePayloads.decodeManifestPage(frame.payload()));
                    }
                }
                case MANIFEST_END -> {
                    ManifestCollector collector = manifestCollector;
                    if (collector != null) {
                        collector.end(FramePayloads.decodeManifestEnd(frame.payload()));
                    }
                }
                case BLOB_CHUNK, BLOB_RESULT -> {
                    BlobCollector collector = activeDownload;
                    if (collector != null) {
                        collector.frame(frame);
                    }
                }
                case ERROR -> {
                    ErrorMessage error = FramePayloads.decodeError(frame.payload());
                    failAll(new IOException("peer error: " + error.code() + " " + error.message()));
                }
                default -> {
                    // unrelated frames are handled by the orchestrator
                }
            }
        } catch (RuntimeException e) {
            failAll(new IOException("invalid frame from peer", e));
        }
    }

    @Override
    public void onClosed(FrameConnection connection) {
        closed = true;
        failAll(new IOException("connection closed"));
    }

    @Override
    public void onError(FrameConnection connection, IOException error) {
        closed = true;
        failAll(error);
    }

    @Override
    public void close() {
        closed = true;
        failAll(new IOException("remote peer closed"));
        TlsTransportClient current = client;
        if (current != null) {
            current.close();
        }
    }

    public boolean isConnected() {
        return !closed && connection != null;
    }

    private void bind(TlsTransportClient client) {
        this.client = client;
        this.connection = client.connection();
        this.closed = false;
    }

    private FrameConnection connection() throws IOException {
        FrameConnection current = connection;
        if (current == null) {
            throw new IOException("remote peer is not connected");
        }
        return current;
    }

    private void failAll(IOException failure) {
        ManifestCollector manifest = manifestCollector;
        if (manifest != null) {
            manifest.fail(failure);
        }
        BlobCollector download = activeDownload;
        if (download != null) {
            download.fail(failure);
        }
    }

    private static final class ManifestCollector {

        private static final long POLL_MILLIS = 200;

        private final List<ManifestEntry> entries = new ArrayList<>();
        private final CompletableFuture<ManifestSnapshot> future = new CompletableFuture<>();
        private final long timeoutMillis;
        private volatile long lastActivityNanos = System.nanoTime();

        private ManifestCollector(Duration timeout) {
            this.timeoutMillis = timeout.toMillis();
        }

        private void page(ManifestPage page) {
            lastActivityNanos = System.nanoTime();
            entries.addAll(page.entries());
        }

        private void end(ManifestEnd end) {
            if (end.totalEntries() != entries.size()) {
                fail(new IOException("manifest size mismatch: declared " + end.totalEntries()
                        + ", received " + entries.size()));
                return;
            }
            future.complete(new ManifestSnapshot(Compat.copyList(entries), end.sequence()));
        }

        private void fail(IOException failure) {
            future.completeExceptionally(failure);
        }

        private ManifestSnapshot await() throws IOException {
            long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (true) {
                try {
                    return future.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (System.nanoTime() - lastActivityNanos > timeoutNanos) {
                        future.cancel(true);
                        throw new IOException("manifest request timed out", e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("manifest request interrupted", e);
                } catch (ExecutionException e) {
                    throw asIOException("manifest request failed", e.getCause());
                }
            }
        }
    }

    private static final class BlobCollector {

        private static final long POLL_MILLIS = 200;

        private final BlobHash hash;
        private final Path tempFile;
        private final BlobDownload download;
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private final long timeoutMillis;
        private volatile long lastActivityNanos = System.nanoTime();
        private boolean finished;

        private BlobCollector(Path tempDir, BlobHash hash, Duration timeout) throws IOException {
            this.hash = hash;
            this.timeoutMillis = timeout.toMillis();
            this.tempFile = Files.createTempFile(tempDir, "blob-", ".tmp");
            this.download = new BlobDownload(tempFile, hash);
        }

        private synchronized void frame(Frame frame) {
            if (finished) {
                return;
            }
            lastActivityNanos = System.nanoTime();
            try {
                download.apply(frame);
                if (download.isCompleted()) {
                    BlobHash actual = download.actualHash();
                    if (!actual.equals(hash)) {
                        throw new IOException("blob hash mismatch: expected " + hash + ", got " + actual);
                    }
                    download.close();
                    finished = true;
                    future.complete(null);
                }
            } catch (IOException e) {
                fail(e);
            }
        }

        private synchronized void fail(IOException failure) {
            if (finished) {
                return;
            }
            finished = true;
            try {
                download.close();
            } catch (IOException ignored) {
                // closing is best effort
            }
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // cleanup is best effort
            }
            future.completeExceptionally(failure);
        }

        private void await() throws IOException {
            long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (true) {
                try {
                    future.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException e) {
                    if (System.nanoTime() - lastActivityNanos > timeoutNanos) {
                        IOException failure = new IOException("blob download timed out", e);
                        fail(failure);
                        throw failure;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    IOException failure = new IOException("blob download interrupted", e);
                    fail(failure);
                    throw failure;
                } catch (ExecutionException e) {
                    throw asIOException("blob download failed", e.getCause());
                }
            }
        }

        private InputStream openStream() throws IOException {
            return new DeleteOnCloseInputStream(Files.newInputStream(tempFile), tempFile);
        }
    }

    private static final class DeleteOnCloseInputStream extends FilterInputStream {

        private final Path file;

        private DeleteOnCloseInputStream(InputStream in, Path file) {
            super(in);
            this.file = file;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    private static IOException asIOException(String message, Throwable cause) {
        if (cause instanceof IOException io) {
            return io;
        }
        return new IOException(message, cause);
    }
}
