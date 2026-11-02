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
