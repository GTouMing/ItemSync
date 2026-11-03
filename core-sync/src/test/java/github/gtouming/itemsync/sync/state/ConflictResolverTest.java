package github.gtouming.itemsync.sync.state;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.model.Conflict;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.apply.BlobFetcher;
import github.gtouming.itemsync.sync.apply.LocalApplier;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictResolverTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final NodeId NODE_A = NodeId.of("node-a");
    private static final NodeId NODE_B = NodeId.of("node-b");
    private static final DeviceId DEVICE = DeviceId.of("device-a");
    private static final LogicalPath PATH = LogicalPath.of("docs/a.txt");
    private static final GroupId GROUP = GroupId.parse("group001");

    @TempDir
    Path temp;

    private Path syncRoot;
    private FileSystemBlobStore blobs;
    private JdbcMetadataStore store;
    private LocalApplier applier;
    private ConflictResolver resolver;
    private Map<BlobHash, byte[]> remoteContent;

    @BeforeEach
    void setUp() throws Exception {
        syncRoot = temp.resolve("GROUP");
        Files.createDirectories(syncRoot);
        blobs = new FileSystemBlobStore(temp.resolve("data"));
        store = JdbcMetadataStore.open("jdbc:sqlite:" + temp.resolve("metadata.db").toAbsolutePath());
        applier = new LocalApplier(syncRoot, store, blobs, PathResolvers.posix(),
                GROUP, NODE_A, DEVICE, () -> T0);
        resolver = new ConflictResolver(syncRoot, store, PathResolvers.posix(), GROUP, NODE_A,
                () -> T0.plusSeconds(5));
        remoteContent = new HashMap<>();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void listsConflictDetails() throws Exception {
        createConflict();

        List<ConflictResolver.ConflictView> views = resolver.open(10);

        assertEquals(1, views.size());
        ConflictResolver.ConflictView view = views.get(0);
        assertEquals(PATH, view.path());
        assertNotNull(view.local());
        assertNotNull(view.remote());
        assertNotNull(view.copyPath());
    }

    @Test
    void keepLocalCopiesTheConflictCopyBack() throws Exception {
        Conflict conflict = createConflict();

        resolver.resolve(conflict.id(), ConflictResolver.Resolution.KEEP_LOCAL);

        Path target = syncRoot.resolve("docs/a.txt");
        assertEquals("local", Files.readString(target, StandardCharsets.UTF_8));
        Item item = store.items().find(GROUP, PATH).orElseThrow();
        assertEquals(VersionVector.of(Map.of(NODE_A, 2L, NODE_B, 1L)), item.vector());
        assertTrue(Files.exists(syncRoot.resolve(conflict.copyPath().value())));
        assertTrue(store.conflicts().findOpen(10).isEmpty());
    }

    @Test
    void keepRemoteLeavesRemoteContentAndCopy() throws Exception {
        Conflict conflict = createConflict();

        resolver.resolve(conflict.id(), ConflictResolver.Resolution.KEEP_REMOTE);

        assertEquals("remote", Files.readString(syncRoot.resolve("docs/a.txt"),
                StandardCharsets.UTF_8));
        assertTrue(Files.exists(syncRoot.resolve(conflict.copyPath().value())));
        assertTrue(store.conflicts().findOpen(10).isEmpty());
    }

    @Test
    void keepBothMarksResolved() throws Exception {
        Conflict conflict = createConflict();

        resolver.resolve(conflict.id(), ConflictResolver.Resolution.KEEP_BOTH);

        assertEquals("remote", Files.readString(syncRoot.resolve("docs/a.txt"),
                StandardCharsets.UTF_8));
        assertTrue(Files.exists(syncRoot.resolve(conflict.copyPath().value())));
        assertTrue(store.conflicts().findOpen(10).isEmpty());
    }

    @Test
    void keepRemoteAppliesRemoteDeletion() throws Exception {
        Conflict conflict = createRemoteDeletionConflict();

        resolver.resolve(conflict.id(), ConflictResolver.Resolution.KEEP_REMOTE);

        assertFalse(Files.exists(syncRoot.resolve("docs/a.txt")));
        Item item = store.items().find(GROUP, PATH).orElseThrow();
        assertNotNull(item.deletedAt());
        assertTrue(store.tombstones().find(item.id()).isPresent());
        assertTrue(store.conflicts().findOpen(10).isEmpty());
    }

    @Test
    void keepLocalPreservesTheDiskFileAfterRemoteDeletion() throws Exception {
        Conflict conflict = createRemoteDeletionConflict();

        resolver.resolve(conflict.id(), ConflictResolver.Resolution.KEEP_LOCAL);

        assertEquals("local", Files.readString(syncRoot.resolve("docs/a.txt"),
                StandardCharsets.UTF_8));
        Item item = store.items().find(GROUP, PATH).orElseThrow();
        assertEquals(VersionVector.of(Map.of(NODE_A, 2L, NODE_B, 1L)), item.vector());
        assertTrue(store.conflicts().findOpen(10).isEmpty());
    }

    @Test
    void rejectsKeepLocalWhenTheLocalVersionIsGone() throws Exception {
        Conflict conflict = createLocalDeletionConflict();

        assertThrows(IOException.class,
                () -> resolver.resolve(conflict.id(), ConflictResolver.Resolution.KEEP_LOCAL));

        assertFalse(store.conflicts().findOpen(10).isEmpty());
    }

    private Conflict createConflict() throws Exception {
        byte[] base = bytes("base");
        applier.applyRemote(file(PATH, remote(hash(base), base), base.length, T0,
                vector(NODE_A, 1)), fetcher(), NODE_A);
        Path target = syncRoot.resolve("docs/a.txt");
        Files.writeString(target, "local", StandardCharsets.UTF_8);

        byte[] local = bytes("local");
        ManifestEntry localEntry = file(PATH, hash(local), local.length, T0, vector(NODE_A, 1));
        byte[] remote = bytes("remote");
        ManifestEntry remoteEntry = file(PATH, remote(hash(remote), remote), remote.length,
                T0.plusSeconds(1), vector(NODE_B, 1));
        applier.resolveConflict(localEntry, remoteEntry, fetcher(), NODE_B);
        return store.conflicts().findOpen(10).get(0);
    }

    private Conflict createRemoteDeletionConflict() throws Exception {
        byte[] base = bytes("base");
        applier.applyRemote(file(PATH, remote(hash(base), base), base.length, T0,
                vector(NODE_A, 1)), fetcher(), NODE_A);
        Path target = syncRoot.resolve("docs/a.txt");
        Files.writeString(target, "local", StandardCharsets.UTF_8);

        byte[] local = bytes("local");
        ManifestEntry localEntry = file(PATH, hash(local), local.length, T0, vector(NODE_A, 1));
        applier.resolveConflict(localEntry, tombstone(PATH, vector(NODE_B, 1)), fetcher(), NODE_B);
        return store.conflicts().findOpen(10).get(0);
    }

    private Conflict createLocalDeletionConflict() throws Exception {
        byte[] base = bytes("base");
        applier.applyRemote(file(PATH, remote(hash(base), base), base.length, T0,
                vector(NODE_A, 1)), fetcher(), NODE_A);
        applier.applyRemote(tombstone(PATH, vector(NODE_A, 2)), fetcher(), NODE_A);

        byte[] remote = bytes("remote");
        applier.resolveConflict(tombstone(PATH, vector(NODE_A, 2)),
                file(PATH, remote(hash(remote), remote), remote.length,
                        T0.plusSeconds(1), vector(NODE_B, 1)),
                fetcher(), NODE_B);
        return store.conflicts().findOpen(10).get(0);
    }

    private BlobFetcher fetcher() {
        return hash -> {
            byte[] content = remoteContent.get(hash);
            if (content == null) {
                throw new IOException("unknown blob: " + hash);
            }
            return new ByteArrayInputStream(content);
        };
    }

    private BlobHash remote(BlobHash hash, byte[] content) {
        remoteContent.put(hash, content);
        return hash;
    }

    private static ManifestEntry file(LogicalPath path, BlobHash hash, long size,
                                      Instant mtime, VersionVector vector) {
        return new ManifestEntry(path, ItemKind.FILE, hash, size, mtime, vector, false);
    }

    private static ManifestEntry tombstone(LogicalPath path, VersionVector vector) {
        return new ManifestEntry(path, ItemKind.FILE, null, 0, T0, vector, true);
    }

    private static VersionVector vector(NodeId node, long counter) {
        return VersionVector.of(Map.of(node, counter));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static BlobHash hash(byte[] content) {
        try {
            return BlobHash.ofSha256(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
