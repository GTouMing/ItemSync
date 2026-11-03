package github.gtouming.itemsync.sync.engine.node;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import github.gtouming.itemsync.transport.net.TlsTransportClient;
import github.gtouming.itemsync.transport.tls.PinnedTrustManager;
import github.gtouming.itemsync.transport.tls.TlsIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SyncNodeTest {

    private static final NodeId NODE_A = NodeId.of("node-a");
    private static final NodeId NODE_B = NodeId.of("node-b");
    private static final DeviceId DEVICE_A = DeviceId.of("device-a");
    private static final DeviceId DEVICE_B = DeviceId.of("device-b");
    private static final GroupId GROUP = GroupId.parse("group001");

    private static TlsIdentity identityA;
    private static TlsIdentity identityB;

    @TempDir
    Path temp;

    private Path rootA;
    private Path rootB;
    private JdbcMetadataStore storeA;
    private JdbcMetadataStore storeB;
    private SyncNode a;
    private SyncNode b;

    @BeforeAll
    static void createIdentities() {
        identityA = TlsIdentity.generate("node-a");
        identityB = TlsIdentity.generate("node-b");
    }

    @BeforeEach
    void setUp() throws Exception {
        rootA = Files.createDirectories(temp.resolve("a-GROUP"));
        rootB = Files.createDirectories(temp.resolve("b-GROUP"));
        FileSystemBlobStore blobsA = new FileSystemBlobStore(temp.resolve("a-data"));
        FileSystemBlobStore blobsB = new FileSystemBlobStore(temp.resolve("b-data"));
        storeA = JdbcMetadataStore.open("jdbc:sqlite:" + temp.resolve("a.db").toAbsolutePath());
        storeB = JdbcMetadataStore.open("jdbc:sqlite:" + temp.resolve("b.db").toAbsolutePath());

        a = new SyncNode(NODE_A, GROUP, rootA, storeA, blobsA, PathResolvers.posix(),
                identityA, DEVICE_A, identityB.fingerprint(), 0, temp.resolve("a-transfer"));
        b = new SyncNode(NODE_B, GROUP, rootB, storeB, blobsB, PathResolvers.posix(),
                identityB, DEVICE_B, identityA.fingerprint(), 0, temp.resolve("b-transfer"));

        a.addPeer(NODE_B, "127.0.0.1", b.port(), identityB.fingerprint());
        b.addPeer(NODE_A, "127.0.0.1", a.port(), identityA.fingerprint());
    }

    @AfterEach
    void tearDown() {
        if (a != null) {
            a.close();
        }
        if (b != null) {
            b.close();
        }
        if (storeA != null) {
            storeA.close();
        }
        if (storeB != null) {
            storeB.close();
        }
    }

    @Test
    void bidirectionalChangesConverge() throws Exception {
        Files.writeString(rootA.resolve("from-a.txt"), "A", StandardCharsets.UTF_8);
        a.syncNow();
        await(() -> hasContent(rootB, "from-a.txt", "A"), "B receives from-a");

        Files.writeString(rootB.resolve("from-b.txt"), "B", StandardCharsets.UTF_8);
        b.syncNow();
        await(() -> hasContent(rootA, "from-b.txt", "B"), "A receives from-b");
    }

    @Test
    void secondSyncAdvancesThePullCursorByTheChangedItemsOnly() throws Exception {
        Files.writeString(rootA.resolve("one.txt"), "1", StandardCharsets.UTF_8);
        Files.writeString(rootA.resolve("two.txt"), "2", StandardCharsets.UTF_8);
        a.syncNow();
        await(() -> hasContent(rootB, "two.txt", "2"), "B receives initial files");
        await(() -> storeB.cursors().pullSequence(GROUP, NODE_A) > 0,
                "B initializes the pull cursor");

        long before = storeB.cursors().pullSequence(GROUP, NODE_A);

        Files.writeString(rootA.resolve("one.txt"), "1-updated", StandardCharsets.UTF_8);
        a.syncNow();

        await(() -> hasContent(rootB, "one.txt", "1-updated"), "B receives the update");
        await(() -> storeB.cursors().pullSequence(GROUP, NODE_A) == before + 1,
                "B advances the pull cursor by one");
    }

    @Test
    void deletionConverges() throws Exception {
        Files.writeString(rootA.resolve("gone.txt"), "x", StandardCharsets.UTF_8);
        a.syncNow();
        await(() -> hasContent(rootB, "gone.txt", "x"), "B receives gone");

        Files.delete(rootA.resolve("gone.txt"));
        a.syncNow();
        await(() -> !Files.exists(rootB.resolve("gone.txt")), "B deletes gone");
    }

    @Test
    void inboundConnectionCountsWithoutAnOutboundLink() throws Exception {
        a.removePeer(NODE_B);

        await(() -> !a.isConnected(NODE_B), "A drops its outbound link");
        assertTrue(a.hasInboundConnection(identityB.fingerprint()));
    }

    @Test
    void inboundFingerprintMismatchClearsAfterTheNewFingerprintIsAllowed() throws Exception {
        TlsIdentity other = TlsIdentity.generate("other");

        assertThrows(IOException.class, () -> TlsTransportClient.connect(
                "127.0.0.1", a.port(), other, identityA.fingerprint(),
                (connection, frame) -> {
                }));

        await(() -> a.lastError() != null, "A records the inbound mismatch");
        PinnedTrustManager.FingerprintMismatchException mismatch =
                PinnedTrustManager.findFingerprintMismatch(a.lastError());
        assertNotNull(mismatch);
        assertTrue(mismatch.actualFingerprint().equalsIgnoreCase(other.fingerprint()));

        a.allowClient(other.fingerprint());

        assertNull(a.lastError());
    }

    @Test
    void revocationClosesTheOtherSidesConnection() throws Exception {
        assertTrue(a.isConnected(NODE_B));
        assertTrue(b.isConnected(NODE_A));

        a.revokeClient(identityB.fingerprint());
        await(() -> !b.isConnected(NODE_A), "B notices that A revoked it");

        a.removePeer(NODE_B);
        await(() -> !a.isConnected(NODE_B), "A drops its own link");
    }

    private static void await(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("timed out waiting for " + description);
    }

    private static boolean hasContent(Path root, String path, String content) {
        try {
            Path file = root.resolve(path);
            return Files.isRegularFile(file)
                    && Files.readString(file, StandardCharsets.UTF_8).equals(content);
        } catch (IOException e) {
            return false;
        }
    }
}
