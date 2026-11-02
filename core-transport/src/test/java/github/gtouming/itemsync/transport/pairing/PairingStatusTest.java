package github.gtouming.itemsync.transport.pairing;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.frame.ManifestRequest;
import github.gtouming.itemsync.transport.frame.PairingStatusResponse;
import github.gtouming.itemsync.transport.net.FrameConnection;
import github.gtouming.itemsync.transport.net.FrameHandler;
import github.gtouming.itemsync.transport.net.TlsTransportClient;
import github.gtouming.itemsync.transport.net.TlsTransportServer;
import github.gtouming.itemsync.transport.tls.TlsIdentity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairingStatusTest {

    private static final GroupId GROUP = GroupId.parse("group001");

    @Test
    void reportsThatTheServerTrustsTheClient() throws Exception {
        TlsIdentity serverIdentity = TlsIdentity.generate("server");
        TlsIdentity clientIdentity = TlsIdentity.generate("client");

        PairingStatusServer handler = server(serverIdentity,
                (group, fingerprint) -> fingerprint.equals(clientIdentity.fingerprint()));

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, new PairingTrustManager(), 0, handler)) {
            server.start();

            PairingStatusClient.Result result = query(server.port(), clientIdentity,
                    serverIdentity.fingerprint(), GROUP);

            assertTrue(result.response().trustsPeer());
            assertEquals(serverIdentity.fingerprint(), result.serverFingerprint());
            assertEquals(NodeId.of("server-node"), result.response().nodeId());
            assertEquals(DeviceId.of("server-device"), result.response().deviceId());
        }
    }

    @Test
    void reportsThatTheServerDoesNotTrustTheClient() throws Exception {
        TlsIdentity serverIdentity = TlsIdentity.generate("server");
        TlsIdentity clientIdentity = TlsIdentity.generate("client");

        PairingStatusServer handler = server(serverIdentity, (group, fingerprint) -> false);

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, new PairingTrustManager(), 0, handler)) {
            server.start();

            PairingStatusClient.Result result = query(server.port(), clientIdentity,
                    serverIdentity.fingerprint(), GROUP);

            assertFalse(result.response().trustsPeer());
        }
    }

    @Test
    void rejectsRequestsForAnotherGroup() throws Exception {
        TlsIdentity serverIdentity = TlsIdentity.generate("server");
        TlsIdentity clientIdentity = TlsIdentity.generate("client");

        PairingStatusServer handler = server(serverIdentity, (group, fingerprint) -> true);

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, new PairingTrustManager(), 0, handler)) {
            server.start();

            assertThrows(IOException.class, () -> query(server.port(), clientIdentity,
                    serverIdentity.fingerprint(), GroupId.parse("group002")));
        }
    }

    @Test
    void rejectsMismatchedServerFingerprint() throws Exception {
        TlsIdentity serverIdentity = TlsIdentity.generate("server");
        TlsIdentity clientIdentity = TlsIdentity.generate("client");

        PairingStatusServer handler = server(serverIdentity, (group, fingerprint) -> true);

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, new PairingTrustManager(), 0, handler)) {
            server.start();

            assertThrows(IOException.class, () -> query(server.port(), clientIdentity,
                    "0".repeat(64), GROUP));
        }
    }

    @Test
    void refusesSyncFramesOnThePairingEndpoint() throws Exception {
        TlsIdentity serverIdentity = TlsIdentity.generate("server");
        TlsIdentity clientIdentity = TlsIdentity.generate("client");

        PairingStatusServer handler = server(serverIdentity, (group, fingerprint) -> true);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Frame> received = new AtomicReference<>();

        FrameHandler clientHandler = new FrameHandler() {
            @Override
            public void onFrame(FrameConnection connection, Frame frame) {
                received.set(frame);
            }

            @Override
            public void onClosed(FrameConnection connection) {
                closed.countDown();
            }

            @Override
            public void onError(FrameConnection connection, IOException error) {
                closed.countDown();
            }
        };

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, new PairingTrustManager(), 0, handler)) {
            server.start();
            try (TlsTransportClient client = TlsTransportClient.connect(
                    "127.0.0.1", server.port(), clientIdentity,
                    serverIdentity.fingerprint(), clientHandler)) {
                client.send(new Frame(FrameType.MANIFEST_REQUEST,
                        FramePayloads.encodeManifestRequest(new ManifestRequest(
                                github.gtouming.itemsync.domain.identity.SyncRootId.of("root-1"),
                                github.gtouming.itemsync.domain.replication.VersionVector.empty(),
                                null))));

                assertTrue(closed.await(5, TimeUnit.SECONDS));
                assertNull(received.get());
            }
        }
    }

    private static PairingStatusServer server(TlsIdentity identity,
                                              PairingTrustProvider provider) {
        return new PairingStatusServer(GROUP, NodeId.of("server-node"),
                DeviceId.of("server-device"), identity.fingerprint(), provider);
    }

    private static PairingStatusClient.Result query(int port, TlsIdentity client,
                                                    String serverFingerprint, GroupId group)
            throws IOException {
        return PairingStatusClient.query("127.0.0.1", port, client, serverFingerprint,
                group, NodeId.of("server-node"), DeviceId.of("server-device"),
                Duration.ofSeconds(5));
    }
}
