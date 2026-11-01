package github.gtouming.itemsync.sync.engine;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.apply.LocalApplier;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import github.gtouming.itemsync.sync.plan.SyncPlanner;
import github.gtouming.itemsync.sync.state.LocalReconciler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncEnginePullTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final NodeId NODE_A = NodeId.of("node-a");
    private static final NodeId NODE_B = NodeId.of("node-b");
    private static final DeviceId DEVICE_A = DeviceId.of("device-a");
    private static final DeviceId DEVICE_B = DeviceId.of("device-b");
    private static final SyncRootId ROOT = SyncRootId.of("root-1");

    @TempDir
    Path temp;

    private NodeFixture a;
    private NodeFixture b;

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
    void pullsNewFile() throws Exception {
        a.write("docs/a.txt", "hello");
        List<ManifestEntry> manifestA = a.reconcile();

        SyncRunResult result = b.engine.pullOnce(a.remote(manifestA));

        assertEquals(2, result.pulled());
        assertEquals(0, result.deleted());
        assertEquals(0, result.conflicts());
        assertEquals(0, result.pendingPush());
        assertEquals("hello", b.read("docs/a.txt"));
        Item item = b.store.items().find(ROOT, LogicalPath.of("docs/a.txt")).orElseThrow();
        assertEquals(versionOf(manifestA, "docs/a.txt"), item.vector());
    }

    @Test
    void pullsModifiedFile() throws Exception {
        a.write("a.txt", "one");
        b.engine.pullOnce(a.remote(a.reconcile()));

        a.write("a.txt", "two");
        SyncRunResult result = b.engine.pullOnce(a.remote(a.reconcile()));

        assertEquals(1, result.pulled());
        assertEquals("two", b.read("a.txt"));
        Item item = b.store.items().find(ROOT, LogicalPath.of("a.txt")).orElseThrow();
        assertEquals(VersionVector.of(java.util.Map.of(NODE_A, 2L)), item.vector());
    }

    @Test
    void pullsDeletion() throws Exception {
        a.write("a.txt", "bye");
        b.engine.pullOnce(a.remote(a.reconcile()));

        a.delete("a.txt");
        SyncRunResult result = b.engine.pullOnce(a.remote(a.reconcile()));

        assertEquals(1, result.deleted());
        assertFalse(b.exists("a.txt"));
        Item item = b.store.items().find(ROOT, LogicalPath.of("a.txt")).orElseThrow();
        assertNotNull(item.deletedAt());
        assertTrue(b.store.tombstones().find(item.id()).isPresent());
    }

    @Test
    void pullsEmptyDirectory() throws Exception {
        a.mkdir("empty");

        SyncRunResult result = b.engine.pullOnce(a.remote(a.reconcile()));

        assertEquals(1, result.pulled());
        assertTrue(b.isDirectory("empty"));
    }

    @Test
    void conflictKeepsBothVersions() throws Exception {
        a.write("a.txt", "from-a");
        b.write("a.txt", "from-b");

        SyncRunResult result = b.engine.pullOnce(a.remote(a.reconcile()));

        assertEquals(1, result.conflicts());
        assertEquals("from-a", b.read("a.txt"));
        Path copy = b.firstFileContaining("conflict");
        assertEquals("from-b", Files.readString(copy, StandardCharsets.UTF_8));
        assertEquals(1, b.store.conflicts().findOpen(10).size());
    }

    @Test
    void countsLocalOnlyChangesAsPendingPush() throws Exception {
        b.write("local.txt", "local");

        SyncRunResult result = b.engine.pullOnce(a.remote(List.of()));

        assertEquals(1, result.pendingPush());
        assertTrue(b.exists("local.txt"));
    }

    private static VersionVector versionOf(List<ManifestEntry> manifest, String path) {
        return manifest.stream()
                .filter(entry -> entry.path().value().equals(path))
                .findFirst()
                .orElseThrow()
                .vector();
    }

    private static final class NodeFixture implements AutoCloseable {

        private final Path root;
        private final FileSystemBlobStore blobs;
        private final JdbcMetadataStore store;
        private final LocalReconciler reconciler;
        private final LocalApplier applier;
        private final SyncEngine engine;
        private final NodeId nodeId;

        private NodeFixture(Path base, NodeId nodeId, DeviceId deviceId) throws Exception {
            this.nodeId = nodeId;
            Files.createDirectories(base);
            this.root = base.resolve("root");
            Files.createDirectories(root);
            this.blobs = new FileSystemBlobStore(base.resolve("data"));
            this.store = JdbcMetadataStore.open("jdbc:sqlite:" + base.resolve("meta.db").toAbsolutePath());
            this.reconciler = new LocalReconciler(root, store, blobs, ROOT, nodeId,
                    PathResolvers.posix(), () -> T0);
            this.applier = new LocalApplier(root, store, blobs, PathResolvers.posix(), ROOT,
                    nodeId, deviceId, () -> T0);
            this.engine = new SyncEngine(reconciler, applier, new SyncPlanner());
        }

        private void write(String path, String content) throws IOException {
            Path target = root.resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        }

        private void mkdir(String path) throws IOException {
            Files.createDirectories(root.resolve(path));
        }

        private void delete(String path) throws IOException {
            Files.delete(root.resolve(path));
        }

        private String read(String path) throws IOException {
            return Files.readString(root.resolve(path), StandardCharsets.UTF_8);
        }

        private boolean exists(String path) {
            return Files.exists(root.resolve(path));
        }

        private boolean isDirectory(String path) {
            return Files.isDirectory(root.resolve(path));
        }

        private List<ManifestEntry> reconcile() throws IOException {
            return reconciler.reconcile();
        }

        private RemotePeer remote(List<ManifestEntry> manifest) {
            return new RemotePeer() {
                @Override
                public NodeId nodeId() {
                    return nodeId;
                }

                @Override
                public List<ManifestEntry> manifest(SyncRootId rootId, VersionVector since) {
                    return manifest;
                }

                @Override
                public InputStream openBlob(BlobHash hash) throws IOException {
                    return blobs.open(hash);
                }
            };
        }

        private Path firstFileContaining(String fragment) throws IOException {
            try (Stream<Path> files = Files.walk(root)) {
                return files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().contains(fragment))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no file containing " + fragment));
            }
        }

        @Override
        public void close() {
            store.close();
        }
    }
}
