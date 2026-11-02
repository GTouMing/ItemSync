package github.gtouming.itemsync.sync.engine.node;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import github.gtouming.itemsync.transport.pairing.PairingStatusClient;
import github.gtouming.itemsync.transport.tls.TlsIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncNodePairingTest {

    private static final GroupId GROUP = GroupId.parse("group001");
    private static final NodeId NODE = NodeId.of("pairing-node");
    private static final DeviceId DEVICE = DeviceId.of("pairing-device");
    private static final SyncRootId ROOT_ID = SyncRootId.of("root-1");

    @TempDir
    Path temp;

    @Test
    void startsPairingEndpointWhenAProviderIsConfigured() throws Exception {
        Path root = Files.createDirectories(temp.resolve("root"));
        TlsIdentity identity = TlsIdentity.generate("pairing-node");
        TlsIdentity client = TlsIdentity.generate("pairing-client");
        JdbcMetadataStore store = JdbcMetadataStore.open(
                "jdbc:sqlite:" + temp.resolve("node.db").toAbsolutePath());

        try {
            try (SyncNode node = new SyncNode(NODE, GROUP, ROOT_ID, root, store,
                    new FileSystemBlobStore(temp.resolve("data")), PathResolvers.posix(),
                    identity, DEVICE, Set.of("none"), 0, temp.resolve("transfer"),
                    (group, fingerprint) -> fingerprint.equals(client.fingerprint()))) {
                assertTrue(node.pairingPort() > 0);

                PairingStatusClient.Result result = PairingStatusClient.query(
                        "127.0.0.1", node.pairingPort(), client, identity.fingerprint(),
                        GROUP, NODE, DEVICE, Duration.ofSeconds(5));

                assertTrue(result.response().trustsPeer());
            }
        } finally {
            store.close();
        }
    }
}
