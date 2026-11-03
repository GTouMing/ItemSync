package github.gtouming.itemsync.sync.engine.node;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.replication.LogicalClock;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import github.gtouming.itemsync.sync.engine.transport.ManifestSnapshot;
import github.gtouming.itemsync.sync.engine.transport.TlsRemotePeer;
import github.gtouming.itemsync.transport.discovery.PeerAdvertisement;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.frame.ManifestEnd;
import github.gtouming.itemsync.transport.frame.Notify;
import github.gtouming.itemsync.transport.frame.NotifyAck;
import github.gtouming.itemsync.transport.net.FrameConnection;
import github.gtouming.itemsync.transport.net.FrameHandler;
import github.gtouming.itemsync.transport.net.TlsTransportClient;
import github.gtouming.itemsync.transport.net.TlsTransportServer;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves that one device serves two groups on one shared sync port: the SNI group decides
 * which runtime answers a frame, an inbound NOTIFY must name the group of its connection,
 * a revoke only closes the connections of its own group and the single mDNS advertisement
 * lists every group (ADR-0036).
 */
class SyncDeviceTest {

    private static final NodeId NODE = NodeId.of("device-node");
    private static final DeviceId DEVICE = DeviceId.of("multi-group-device");
    private static final NodeId PEER_NODE = NodeId.of("peer-node");
    private static final GroupId GROUP_A = GroupId.parse("group001");
    private static final GroupId GROUP_B = GroupId.parse("group002");
    private static final GroupId OTHER_GROUP = GroupId.parse("group009");

    private static TlsIdentity deviceIdentity;
    private static TlsIdentity aliceIdentity;
    private static TlsIdentity bobIdentity;

    @TempDir
    Path temp;

    private Path rootA;
    private Path rootB;
    private JdbcMetadataStore store;
    private SyncDevice device;

    @BeforeAll
    static void createIdentities() {
        deviceIdentity = TlsIdentity.generate("device-node");
        aliceIdentity = TlsIdentity.generate("alice");
        bobIdentity = TlsIdentity.generate("bob");
    }

    @BeforeEach
    void setUp() throws Exception {
        rootA = Files.createDirectories(temp.resolve("a-root"));
        rootB = Files.createDirectories(temp.resolve("b-root"));
        FileSystemBlobStore blobs = new FileSystemBlobStore(temp.resolve("data"));
        store = JdbcMetadataStore.open("jdbc:sqlite:" + temp.resolve("device.db").toAbsolutePath());
        device = new SyncDevice(NODE, DEVICE, deviceIdentity, store, blobs,
                PathResolvers.posix(),
                List.of(new SyncDevice.GroupConfig(GROUP_A, "group-a", rootA,
                                Set.of(aliceIdentity.fingerprint()), null),
                        new SyncDevice.GroupConfig(GROUP_B, "group-b", rootB,
                                Set.of(bobIdentity.fingerprint()), null)),
                0, temp.resolve("transfer"), null);
    }

    @AfterEach
    void tearDown() {
        if (device != null) {
            device.close();
        }
        if (store != null) {
            store.close();
        }
    }

    @Test
    void servesEachGroupsOwnManifestOnTheSharedSyncPort() throws Exception {
        write(rootA, "a.txt", "from-a");
        write(rootB, "b.txt", "from-b");
        // a sync reconciles the group root, which fills the manifest the port serves
        assertTrue(device.group(GROUP_A).syncNow().isEmpty());
        assertTrue(device.group(GROUP_B).syncNow().isEmpty());

        try (TlsRemotePeer peerA = connect(GROUP_A, aliceIdentity);
             TlsRemotePeer peerB = connect(GROUP_B, bobIdentity)) {
            assertEquals(List.of("a.txt"), paths(peerA.manifest(GROUP_A, 0L)),
                    "group A was not served by its own runtime");
            assertEquals(List.of("b.txt"), paths(peerB.manifest(GROUP_B, 0L)),
                    "group B was not served by its own runtime");

            await(() -> device.hasInboundConnection(GROUP_A, aliceIdentity.fingerprint()),
                    "the device registers the group A connection");
            await(() -> device.hasInboundConnection(GROUP_B, bobIdentity.fingerprint()),
                    "the device registers the group B connection");
            assertFalse(device.hasInboundConnection(GROUP_B, aliceIdentity.fingerprint()),
                    "group B reported the inbound connection of group A");
            assertFalse(device.hasInboundConnection(GROUP_A, bobIdentity.fingerprint()),
                    "group A reported the inbound connection of group B");
        }
    }

    @Test
    void rejectsANotifyThatNamesAnotherGroup() throws Exception {
        // a raw client that the device also has an outbound link to, so a NOTIFY of the
        // connection's own group is accepted and a NOTIFY of another group is not
        FrameHandler peerHandler = (connection, frame) -> {
            if (frame.type() == FrameType.MANIFEST_REQUEST) {
                connection.send(new Frame(FrameType.MANIFEST_END,
                        FramePayloads.encodeManifestEnd(new ManifestEnd(0, 0L))));
            }
        };
        NotifyAcks acks = new NotifyAcks();
        try (TlsTransportServer peerServer = startPeerServer(peerHandler);
             TlsTransportClient client = TlsTransportClient.connect("127.0.0.1", device.port(),
                     aliceIdentity, deviceIdentity.fingerprint(), acks, GROUP_A)) {
            device.group(GROUP_A).addPeer(PEER_NODE, "127.0.0.1", peerServer.port(),
                    aliceIdentity.fingerprint());

            SyncRunId foreignNotify = new SyncRunId("notify-for-group-b");
            SyncRunId ownNotify = new SyncRunId("notify-for-group-a");
            client.send(notify(foreignNotify, GROUP_B));
            client.send(notify(ownNotify, GROUP_A));

            await(() -> acks.accepted(ownNotify) != null, "the device answers both NOTIFY frames");
            assertEquals(Boolean.FALSE, acks.accepted(foreignNotify),
                    "a NOTIFY naming group " + GROUP_B.value() + " was accepted on a "
                            + GROUP_A.value() + " connection");
            assertEquals(Boolean.TRUE, acks.accepted(ownNotify),
                    "a NOTIFY naming the connection's own group was rejected");
        }
    }

    @Test
    void revokingOneGroupKeepsTheOtherGroupsConnectionOpen() throws Exception {
        try (TlsRemotePeer peerA = connect(GROUP_A, aliceIdentity);
             TlsRemotePeer peerB = connect(GROUP_B, bobIdentity)) {
            await(() -> device.hasInboundConnection(GROUP_A, aliceIdentity.fingerprint()),
                    "the device registers the group A connection");
            await(() -> device.hasInboundConnection(GROUP_B, bobIdentity.fingerprint()),
                    "the device registers the group B connection");
            assertTrue(peerA.isConnected());
            assertTrue(peerB.isConnected());

            device.revokeClient(GROUP_A, aliceIdentity.fingerprint());

            await(() -> !peerA.isConnected(), "the revoked group A connection closes");
            await(() -> !device.hasInboundConnection(GROUP_A, aliceIdentity.fingerprint()),
                    "the device forgets the revoked group A connection");
            assertTrue(peerB.isConnected(),
                    "revoking group " + GROUP_A.value() + " closed group " + GROUP_B.value());
            assertTrue(device.hasInboundConnection(GROUP_B, bobIdentity.fingerprint()),
                    "revoking group " + GROUP_A.value() + " dropped the group B connection");
        }
    }

    @Test
    void advertisesEveryGroupInTheOneDeviceAdvertisement() {
        PeerAdvertisement advertisement = device.advertisement();

        assertNotNull(advertisement, "the device builds no advertisement");
        assertEquals(Set.of(GROUP_A, GROUP_B), advertisement.groupIds(),
                "the advertisement does not list every group of the device");
        assertEquals(NODE, advertisement.nodeId());
        assertEquals(DEVICE, advertisement.deviceId());
        assertEquals(deviceIdentity.fingerprint(), advertisement.fingerprint());
        assertEquals(device.port(), advertisement.port(),
                "the advertisement does not publish the shared sync port");
        assertEquals(device.pairingPort(), advertisement.pairingPort());
        assertEquals(Set.of(GROUP_A, GROUP_B), device.groups().keySet());
    }

    @Test
    void rejectsConnectionsWithoutOrWithAnUnknownSniGroup() throws Exception {
        TlsIdentity outsider = TlsIdentity.generate("outsider");

        connectIgnoringFailure(outsider, null);
        connectIgnoringFailure(outsider, OTHER_GROUP);

        // positive control: a pinned connection is registered, the rejected ones are not
        try (TlsRemotePeer peerA = connect(GROUP_A, aliceIdentity)) {
            await(() -> device.hasInboundConnection(GROUP_A, aliceIdentity.fingerprint()),
                    "the device registers the pinned connection");
            assertFalse(device.hasInboundConnection(GROUP_A, outsider.fingerprint()),
                    "a connection without a served SNI group reached group " + GROUP_A.value());
            assertFalse(device.hasInboundConnection(GROUP_B, outsider.fingerprint()),
                    "a connection without a served SNI group reached group " + GROUP_B.value());
            assertTrue(peerA.isConnected());
        }
    }

    private void connectIgnoringFailure(TlsIdentity identity, GroupId sniGroup) {
        TlsTransportClient client = null;
        try {
            client = sniGroup == null
                    ? TlsTransportClient.connect("127.0.0.1", device.port(), identity,
                            deviceIdentity.fingerprint(), (connection, frame) -> {
                            })
                    : TlsTransportClient.connect("127.0.0.1", device.port(), identity,
                            deviceIdentity.fingerprint(), (connection, frame) -> {
                            }, sniGroup);
        } catch (IOException expected) {
            // the shared port refused the handshake; TLS 1.3 may also report it right after connect
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private TlsTransportServer startPeerServer(FrameHandler handler) throws IOException {
        // the peer presents the certificate the device pinned for group A and accepts the
        // device certificate the device itself uses
        TlsTransportServer server = new TlsTransportServer(aliceIdentity,
                Set.of(deviceIdentity.fingerprint()), 0, handler);
        server.start();
        return server;
    }

    private TlsRemotePeer connect(GroupId groupId, TlsIdentity identity) throws IOException {
        return TlsRemotePeer.connect("127.0.0.1", device.port(), identity,
                deviceIdentity.fingerprint(), groupId, PEER_NODE,
                temp.resolve("downloads-" + groupId.value()));
    }

    private static Frame notify(SyncRunId runId, GroupId groupId) {
        return new Frame(FrameType.NOTIFY, FramePayloads.encodeNotify(
                new Notify(runId, groupId, LogicalClock.ZERO, VersionVector.empty())));
    }

    private static List<String> paths(ManifestSnapshot snapshot) {
        return snapshot.entries().stream()
                .map(ManifestEntry::path)
                .map(path -> path.value())
                .sorted()
                .toList();
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static void await(BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("timed out waiting until " + description);
    }

    private static final class NotifyAcks implements FrameHandler {

        private final Map<SyncRunId, Boolean> acks = new ConcurrentHashMap<>();

        @Override
        public void onFrame(FrameConnection connection, Frame frame) {
            if (frame.type() != FrameType.NOTIFY_ACK) {
                return;
            }
            NotifyAck ack = FramePayloads.decodeNotifyAck(frame.payload());
            acks.put(ack.runId(), ack.accepted());
        }

        private Boolean accepted(SyncRunId runId) {
            return acks.get(runId);
        }
    }
}
