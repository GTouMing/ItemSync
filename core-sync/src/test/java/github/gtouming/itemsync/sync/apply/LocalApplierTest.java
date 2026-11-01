package github.gtouming.itemsync.sync.apply;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalApplierTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final NodeId NODE_A = NodeId.of("node-a");
    private static final NodeId NODE_B = NodeId.of("node-b");
    private static final DeviceId DEVICE = DeviceId.of("device-a");
    private static final SyncRootId ROOT = SyncRootId.of("root-1");
    private static final LogicalPath PATH = LogicalPath.of("docs/a.txt");

    @TempDir
    Path temp;

    private Path syncRoot;
    private FileSystemBlobStore blobs;
    private JdbcMetadataStore store;
    private LocalApplier applier;
    private Map<BlobHash, byte[]> remoteContent;

    @BeforeEach
    void setUp() throws Exception {
        syncRoot = temp.resolve("root");
        Files.createDirectories(syncRoot);
        blobs = new FileSystemBlobStore(temp.resolve("data"));
        store = JdbcMetadataStore.open("jdbc:sqlite:" + temp.resolve("metadata.db").toAbsolutePath());
        applier = new LocalApplier(syncRoot, store, blobs, PathResolvers.posix(),
                ROOT, NODE_A, DEVICE, () -> T0);
        remoteContent = new HashMap<>();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void pullsFileIntoRoot() throws Exception {
        byte[] content = bytes("hello");
        BlobHash hash = remote(hash(content), content);

        applier.applyRemote(file(PATH, hash, content.length, T0, vector(NODE_B, 1)), fetcher(), NODE_B);

        Path target = syncRoot.resolve("docs/a.txt");
        assertEquals("hello", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(T0.toEpochMilli(), Files.getLastModifiedTime(target).toMillis());

        Item item = store.items().find(ROOT, PATH).orElseThrow();
        assertEquals(ItemKind.FILE, item.kind());
        assertEquals(vector(NODE_B, 1), item.vector());
        assertNull(item.deletedAt());

        List<FileVersion> versions = store.versions().recent(item.id(), 10);
        assertEquals(1, versions.size());
        assertEquals(hash, versions.get(0).blobHash());
        assertEquals(NODE_B, versions.get(0).sourceNodeId());
    }

    @Test
    void replacesExistingFile() throws Exception {
        byte[] first = bytes("one");
        applier.applyRemote(file(PATH, remote(hash(first), first), first.length, T0, vector(NODE_B, 1)),
                fetcher(), NODE_B);

        byte[] second = bytes("two");
        applier.applyRemote(file(PATH, remote(hash(second), second), second.length, T0.plusSeconds(1),
                vector(NODE_B, 2)), fetcher(), NODE_B);

        Path target = syncRoot.resolve("docs/a.txt");
        assertEquals("two", Files.readString(target, StandardCharsets.UTF_8));

        Item item = store.items().find(ROOT, PATH).orElseThrow();
        assertEquals(vector(NODE_B, 2), item.vector());
        assertEquals(2, store.versions().recent(item.id(), 10).size());
    }

    @Test
    void createsDirectories() throws Exception {
        LogicalPath directory = LogicalPath.of("docs");
        applier.applyRemote(directory(directory, vector(NODE_B, 1)), fetcher(), NODE_B);

        assertTrue(Files.isDirectory(syncRoot.resolve("docs")));
        Item item = store.items().find(ROOT, directory).orElseThrow();
        assertEquals(ItemKind.DIRECTORY, item.kind());
        assertTrue(store.versions().recent(item.id(), 10).isEmpty());
    }

    @Test
    void appliesTombstone() throws Exception {
        byte[] content = bytes("gone");
        applier.applyRemote(file(PATH, remote(hash(content), content), content.length, T0, vector(NODE_B, 1)),
                fetcher(), NODE_B);

        applier.applyRemote(tombstone(PATH, vector(NODE_B, 2)), fetcher(), NODE_B);

        assertFalse(Files.exists(syncRoot.resolve("docs/a.txt")));
        Item item = store.items().find(ROOT, PATH).orElseThrow();
        assertNotNull(item.deletedAt());
        assertTrue(store.tombstones().find(item.id()).isPresent());
    }

    @Test
    void conflictKeepsBothFiles() throws Exception {
        seedLocalFile("base");

        Path target = syncRoot.resolve("docs/a.txt");
        Files.writeString(target, "local", StandardCharsets.UTF_8);
        ManifestEntry localEntry = file(PATH, hash(bytes("local")), 5, T0, vector(NODE_A, 1));

        byte[] remoteBytes = bytes("remote");
        ManifestEntry remoteEntry = file(PATH, remote(hash(remoteBytes), remoteBytes), remoteBytes.length,
                T0.plusSeconds(1), vector(NODE_B, 1));

        applier.resolveConflict(localEntry, remoteEntry, fetcher(), NODE_B);

        assertEquals("remote", Files.readString(target, StandardCharsets.UTF_8));
        Path copy = onlyConflictCopy();
        assertEquals("local", Files.readString(copy, StandardCharsets.UTF_8));

        Item copyItem = store.items().find(ROOT, LogicalPath.of("docs/" + copy.getFileName())).orElseThrow();
        assertEquals(1, store.versions().recent(copyItem.id(), 10).size());
        assertEquals(1, store.conflicts().findOpen(10).size());
        assertNotNull(store.conflicts().findOpen(10).get(0).copyPath());
    }

    @Test
    void deleteVersusModifyKeepsLocalFile() throws Exception {
        seedLocalFile("base");

        Path target = syncRoot.resolve("docs/a.txt");
        Files.writeString(target, "local", StandardCharsets.UTF_8);
        ManifestEntry localEntry = file(PATH, hash(bytes("local")), 5, T0, vector(NODE_A, 1));

        applier.resolveConflict(localEntry, tombstone(PATH, vector(NODE_B, 1)), fetcher(), NODE_B);

        assertTrue(Files.exists(target));
        assertEquals("local", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(1, store.conflicts().findOpen(10).size());
        assertNull(store.conflicts().findOpen(10).get(0).copyPath());
    }

    @Test
    void localDeleteVersusRemoteModifyRestoresRemote() throws Exception {
        seedLocalFile("base");
        applier.applyRemote(tombstone(PATH, vector(NODE_A, 1)), fetcher(), NODE_A);
        assertFalse(Files.exists(syncRoot.resolve("docs/a.txt")));

        byte[] remoteBytes = bytes("remote");
        ManifestEntry remoteEntry = file(PATH, remote(hash(remoteBytes), remoteBytes), remoteBytes.length,
                T0.plusSeconds(1), vector(NODE_B, 1));

        applier.resolveConflict(tombstone(PATH, vector(NODE_A, 1)), remoteEntry, fetcher(), NODE_B);

        Path target = syncRoot.resolve("docs/a.txt");
        assertTrue(Files.exists(target));
        assertEquals("remote", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(1, store.conflicts().findOpen(10).size());
    }

    @Test
    void rejectsBlobHashMismatch() throws Exception {
        BlobHash expected = remote(hash(bytes("expected")), bytes("expected"));
        ManifestEntry remoteEntry = file(PATH, expected, 8, T0, vector(NODE_B, 1));
        BlobFetcher broken = hash -> new ByteArrayInputStream(bytes("wrong"));

        assertThrows(IOException.class, () -> applier.applyRemote(remoteEntry, broken, NODE_B));

        assertFalse(Files.exists(syncRoot.resolve("docs/a.txt")));
        assertTrue(store.items().find(ROOT, PATH).isEmpty());
    }

    private void seedLocalFile(String content) throws Exception {
        byte[] data = bytes(content);
        applier.applyRemote(file(PATH, remote(hash(data), data), data.length, T0, vector(NODE_A, 1)),
                fetcher(), NODE_A);
    }

    private Path onlyConflictCopy() throws IOException {
        try (Stream<Path> files = Files.list(syncRoot.resolve("docs"))) {
            List<Path> copies = files
                    .filter(path -> path.getFileName().toString().contains("conflict"))
                    .toList();
            assertEquals(1, copies.size());
            return copies.get(0);
        }
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

    private static ManifestEntry directory(LogicalPath path, VersionVector vector) {
        return new ManifestEntry(path, ItemKind.DIRECTORY, null, 0, T0, vector, false);
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
