package github.gtouming.itemsync.cli;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import github.gtouming.itemsync.sync.engine.node.SyncNode;
import github.gtouming.itemsync.transport.tls.TlsIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliIncrementalSyncTest {

    private static final GroupId GROUP = GroupId.parse("group001");
    private static final String PASSWORD = "test";

    @TempDir
    Path temp;

    @Test
    void secondCliSyncAdvancesThePullCursorByOneChangedItem() throws Exception {
        Path aData = Files.createDirectories(temp.resolve("a-data"));
        Path aRoot = Files.createDirectories(temp.resolve("a-GROUP"));
        Path bData = Files.createDirectories(temp.resolve("b-data"));
        Path bRoot = Files.createDirectories(temp.resolve("b-GROUP"));
        Files.writeString(aRoot.resolve("one.txt"), "1", StandardCharsets.UTF_8);
        Files.writeString(aRoot.resolve("two.txt"), "2", StandardCharsets.UTF_8);

        ItemSyncCli.run(new String[] {"init", "--data", bData.toString(),
                "--password", PASSWORD});
        TlsIdentity bIdentity = TlsIdentity.load(
                bData.resolve("identity.p12"), PASSWORD.toCharArray());
        TlsIdentity aIdentity = TlsIdentity.generate("cli-source");

        Files.createDirectories(aData.resolve("db"));
        JdbcMetadataStore aStore = JdbcMetadataStore.open(
                "jdbc:sqlite:" + aData.resolve("db/itemsync.sqlite").toAbsolutePath());
        NodeId aNode = NodeId.of("cli-source-node");
        NodeId cliPeer = NodeId.of("peer-"
                + aIdentity.fingerprint().substring(0, 8));
        try (SyncNode server = new SyncNode(aNode, GROUP, aRoot, aStore,
                new FileSystemBlobStore(aData), PathResolvers.posix(), aIdentity,
                DeviceId.of("cli-source-device"), Set.of(bIdentity.fingerprint()), 0,
                aData.resolve("transfer"))) {
            sync(bData, bRoot, server.port(), aIdentity.fingerprint());
            long first = pullSequence(bData, cliPeer);
            assertTrue(first > 0);
            assertEquals("1", Files.readString(bRoot.resolve("one.txt"), StandardCharsets.UTF_8));
            assertEquals("2", Files.readString(bRoot.resolve("two.txt"), StandardCharsets.UTF_8));

            Files.writeString(aRoot.resolve("one.txt"), "updated", StandardCharsets.UTF_8);
            server.syncNow();

            sync(bData, bRoot, server.port(), aIdentity.fingerprint());
            long second = pullSequence(bData, cliPeer);

            assertEquals(first + 1, second);
            assertEquals("updated",
                    Files.readString(bRoot.resolve("one.txt"), StandardCharsets.UTF_8));
        } finally {
            aStore.close();
        }
    }

    private static void sync(Path data, Path root, int port, String fingerprint)
            throws Exception {
        ItemSyncCli.run(new String[] {"sync", "--data", data.toString(),
                "--password", PASSWORD, "--root", root.toString(),
                "--group", GROUP.value(),
                "--peer", "127.0.0.1:" + port + ":" + fingerprint,
                "--wait", "0"});
    }

    private static long pullSequence(Path data, NodeId peer) throws Exception {
        try (JdbcMetadataStore store = JdbcMetadataStore.open(
                "jdbc:sqlite:" + data.resolve("db/itemsync.sqlite").toAbsolutePath())) {
            return store.cursors().pullSequence(GROUP, peer);
        }
    }
}
