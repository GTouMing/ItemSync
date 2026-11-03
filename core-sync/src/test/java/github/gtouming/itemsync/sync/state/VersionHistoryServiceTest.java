package github.gtouming.itemsync.sync.state;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.model.FileVersion;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionHistoryServiceTest {

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
    private VersionHistoryService service;
    private Map<BlobHash, byte[]> remoteContent;

    @BeforeEach
    void setUp() throws Exception {
        syncRoot = temp.resolve("GROUP");
        Files.createDirectories(syncRoot);
        blobs = new FileSystemBlobStore(temp.resolve("data"));
        store = JdbcMetadataStore.open("jdbc:sqlite:" + temp.resolve("metadata.db").toAbsolutePath());
        applier = new LocalApplier(syncRoot, store, blobs, PathResolvers.posix(),
                GROUP, NODE_A, DEVICE, () -> T0);
        service = new VersionHistoryService(syncRoot, store, blobs, PathResolvers.posix(), GROUP,
                NODE_A, () -> T0.plusSeconds(5));
        remoteContent = new HashMap<>();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void listsFilesAndTheirVersions() throws Exception {
        addVersions();

        List<VersionHistoryService.ItemView> items = service.items(10);

        assertEquals(1, items.size());
        assertEquals(PATH, items.get(0).path());
        List<FileVersion> versions = service.versions(items.get(0).id());
        assertEquals(2, versions.size());
        assertEquals(Set.of(hash(bytes("one")), hash(bytes("two"))),
                versions.stream().map(FileVersion::blobHash).collect(Collectors.toSet()));
    }

    @Test
    void restoresAnOlderVersionAsANewVersion() throws Exception {
        addVersions();
        VersionHistoryService.ItemView item = service.items(10).get(0);
        FileVersion one = version(item.id(), "one");

        service.restore(item.id(), one.id());

        assertEquals("one", Files.readString(syncRoot.resolve("docs/a.txt"),
                StandardCharsets.UTF_8));
        Item stored = store.items().find(GROUP, PATH).orElseThrow();
        assertEquals(VersionVector.of(Map.of(NODE_A, 1L, NODE_B, 2L)), stored.vector());
        assertEquals(3, service.versions(item.id()).size());
    }

    @Test
    void rejectsRestoreWhenTheBlobIsMissing() throws Exception {
        addVersions();
        VersionHistoryService.ItemView item = service.items(10).get(0);
        FileVersion one = version(item.id(), "one");
        blobs.delete(hash(bytes("one")));

        assertThrows(IOException.class, () -> service.restore(item.id(), one.id()));

        assertEquals("two", Files.readString(syncRoot.resolve("docs/a.txt"),
                StandardCharsets.UTF_8));
    }

    private void addVersions() throws Exception {
        byte[] one = bytes("one");
        applier.applyRemote(file(PATH, remote(hash(one), one), one.length, T0,
                vector(NODE_B, 1)), fetcher(), NODE_B);
        byte[] two = bytes("two");
        applier.applyRemote(file(PATH, remote(hash(two), two), two.length, T0.plusSeconds(1),
                vector(NODE_B, 2)), fetcher(), NODE_B);
    }

    private FileVersion version(github.gtouming.itemsync.domain.identity.ItemId itemId,
                                String content) {
        return service.versions(itemId).stream()
                .filter(version -> version.blobHash().equals(hash(bytes(content))))
                .findFirst()
                .orElseThrow();
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
