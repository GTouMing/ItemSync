package github.gtouming.itemsync.sync.engine.transport;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.apply.LocalApplier;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import github.gtouming.itemsync.sync.engine.SyncEngine;
import github.gtouming.itemsync.sync.engine.SyncRunResult;
import github.gtouming.itemsync.sync.plan.SyncPlanner;
import github.gtouming.itemsync.sync.state.LocalReconciler;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.frame.ManifestEnd;
import github.gtouming.itemsync.transport.frame.ManifestPage;
import github.gtouming.itemsync.transport.net.FrameConnection;
import github.gtouming.itemsync.transport.net.FrameHandler;
import github.gtouming.itemsync.transport.net.TlsTransportServer;
import github.gtouming.itemsync.transport.tls.TlsIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsRemotePeerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final NodeId NODE_A = NodeId.of("node-a");
    private static final NodeId NODE_B = NodeId.of("node-b");
    private static final DeviceId DEVICE_A = DeviceId.of("device-a");
    private static final DeviceId DEVICE_B = DeviceId.of("device-b");
    private static final SyncRootId ROOT = SyncRootId.of("root-1");

    private static TlsIdentity serverIdentity;
    private static TlsIdentity clientIdentity;

    @TempDir
    Path temp;

    private NodeFixture a;
    private NodeFixture b;

    @BeforeAll
    static void createIdentities() {
        serverIdentity = TlsIdentity.generate("server");
        clientIdentity = TlsIdentity.generate("client");
    }

    @BeforeEach
    void setUp() throws Exception {
        a = new NodeFixture(temp.resolve("a"), NODE_A, DEVICE_A);
        b = new NodeFixture(temp.resolve("b"), NODE_B, DEVICE_B);
    }

    @AfterEach
    void tearDown() {
        a.close();
        b.close();
    }

    @Test
    void reportsDisconnectedAfterTheServerClosesTheConnection() throws Exception {
        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, clientIdentity.fingerprint(), 0, (connection, frame) -> {
                })) {
            server.start();
            TlsRemotePeer peer = TlsRemotePeer.connect("127.0.0.1", server.port(),
                    clientIdentity, serverIdentity.fingerprint(), NODE_A,
                    temp.resolve("transfer"));
            try {
                assertTrue(peer.isConnected());
                Thread.sleep(100);
                server.closeConnections();
                long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (peer.isConnected() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertFalse(peer.isConnected());
            } finally {
                peer.close();
            }
        }
    }

    @Test
    void fetchesManifestAndBlobOverTls() throws Exception {
        byte[] content = "hello over tls".getBytes(StandardCharsets.UTF_8);
        a.write("docs/a.txt", "hello over tls");
        List<ManifestEntry> manifest = a.reconcile();
        BlobHash hash = manifest.stream()
                .filter(entry -> entry.path().value().equals("docs/a.txt"))
                .findFirst()
                .orElseThrow()
                .hash();

        try (TlsTransportServer server = startServer(manifest);
             TlsRemotePeer peer = connect(server)) {
            ManifestSnapshot fetched = peer.manifest(ROOT, 0L);

            assertEquals(paths(manifest), paths(fetched.entries()));
            try (InputStream in = peer.openBlob(hash)) {
                assertArrayEquals(content, in.readAllBytes());
            }
        }
    }

    @Test
    void manifestProgressResetsTheTimeout() throws Exception {
        a.write("a.txt", "a");
        a.write("b.txt", "b");
        List<ManifestEntry> manifest = a.reconcile();
        FrameHandler handler = new FrameHandler() {
            @Override
            public void onFrame(FrameConnection connection, Frame frame) {
                if (frame.type() != FrameType.MANIFEST_REQUEST) {
                    return;
                }
                int first = Math.max(1, manifest.size() / 3);
                int second = Math.max(first, manifest.size() * 2 / 3);
                connection.send(page(manifest.subList(0, first)));
                sleep(500);
                connection.send(page(manifest.subList(first, second)));
                sleep(500);
                connection.send(page(manifest.subList(second, manifest.size())));
                connection.send(new Frame(FrameType.MANIFEST_END,
                        FramePayloads.encodeManifestEnd(
                                new ManifestEnd(manifest.size(), 0L))));
            }
        };

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, clientIdentity.fingerprint(), 0, handler)) {
            server.start();
            try (TlsRemotePeer peer = TlsRemotePeer.connect(
                    "127.0.0.1", server.port(), clientIdentity,
                    serverIdentity.fingerprint(), NODE_A, temp.resolve("downloads"),
                    Duration.ofMillis(800), Duration.ZERO)) {
                ManifestSnapshot snapshot = peer.manifest(ROOT, 0L);

                assertEquals(manifest.size(), snapshot.entries().size());
            }
        }
    }

    @Test
    void pullsThroughTlsRemotePeer() throws Exception {
        a.write("docs/a.txt", "from-a");
        List<ManifestEntry> manifest = a.reconcile();

        try (TlsTransportServer server = startServer(manifest);
             TlsRemotePeer peer = connect(server)) {
            SyncRunResult result = b.engine.pullOnce(peer);

            assertEquals(2, result.pulled());
            assertEquals("from-a", b.read("docs/a.txt"));
        }
    }

    private static Frame page(List<ManifestEntry> entries) {
        return new Frame(FrameType.MANIFEST_PAGE,
                FramePayloads.encodeManifestPage(new ManifestPage(entries, null)));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private TlsTransportServer startServer(List<ManifestEntry> manifest) throws IOException {
        RemoteSyncServer handler = new RemoteSyncServer(ROOT,
                (rootId, since) -> new ManifestSnapshot(manifest, 0L), BlobSources.of(a.blobs));
        TlsTransportServer server = new TlsTransportServer(
                serverIdentity, clientIdentity.fingerprint(), 0, handler);
        server.start();
        return server;
    }

    private TlsRemotePeer connect(TlsTransportServer server) throws IOException {
        return TlsRemotePeer.connect("127.0.0.1", server.port(), clientIdentity,
                serverIdentity.fingerprint(), NODE_A, temp.resolve("downloads"),
                Duration.ofSeconds(10));
    }

    private static List<String> paths(List<ManifestEntry> manifest) {
        return manifest.stream()
                .map(entry -> entry.path().value())
                .sorted()
                .toList();
    }

    private static final class NodeFixture implements AutoCloseable {

        private final Path root;
        private final FileSystemBlobStore blobs;
        private final JdbcMetadataStore store;
        private final LocalReconciler reconciler;
        private final SyncEngine engine;

        private NodeFixture(Path base, NodeId nodeId, DeviceId deviceId) throws Exception {
            Files.createDirectories(base);
            this.root = base.resolve("root");
            Files.createDirectories(root);
            this.blobs = new FileSystemBlobStore(base.resolve("data"));
            this.store = JdbcMetadataStore.open("jdbc:sqlite:" + base.resolve("meta.db").toAbsolutePath());
            this.reconciler = new LocalReconciler(root, store, blobs, ROOT, nodeId,
                    PathResolvers.posix(), () -> T0);
            LocalApplier applier = new LocalApplier(root, store, blobs, PathResolvers.posix(), ROOT,
                    nodeId, deviceId, () -> T0);
            this.engine = new SyncEngine(reconciler, applier, new SyncPlanner());
        }

        private void write(String path, String content) throws IOException {
            Path target = root.resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        }

        private String read(String path) throws IOException {
            return Files.readString(root.resolve(path), StandardCharsets.UTF_8);
        }

        private List<ManifestEntry> reconcile() throws IOException {
            return reconciler.reconcile();
        }

        @Override
        public void close() {
            store.close();
        }
    }
}
