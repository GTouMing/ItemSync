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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairingStatusTest {

    private static final GroupId GROUP = GroupId.parse("group001");

    @Test
    void routesEachRequestToTheGroupOfTheSharedEndpoint() throws Exception {
        TlsIdentity serverIdentity = TlsIdentity.generate("server");
        TlsIdentity clientIdentity = TlsIdentity.generate("client");
        GroupId other = GroupId.parse("group002");

        PairingStatusServer handler = new PairingStatusServer(
                Map.of(GROUP, byFingerprint(fingerprint -> true),
                        other, byFingerprint(fingerprint -> false)),
                NodeId.of("server-node"), DeviceId.of("server-device"),
                serverIdentity.fingerprint());

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, new PairingTrustManager(), 0, handler)) {
            server.start();

            PairingStatusClient.Result first = query(server.port(), clientIdentity,
                    serverIdentity.fingerprint(), GROUP);
            PairingStatusClient.Result second = query(server.port(), clientIdentity,
                    serverIdentity.fingerprint(), other);

            assertEquals(GROUP, first.response().groupId());
            assertTrue(first.response().trustsPeer(), "the shared endpoint answered for another group");
            assertEquals(other, second.response().groupId());
            assertFalse(second.response().trustsPeer(),
                    "the shared endpoint answered for another group");
        }
    }

    @Test
    void doesNotAnswerForAGroupTheSharedEndpointDoesNotServe() throws Exception {
        TlsIdentity serverIdentity = TlsIdentity.generate("server");
        TlsIdentity clientIdentity = TlsIdentity.generate("client");

        PairingStatusServer handler = new PairingStatusServer(
                Map.of(GROUP, byFingerprint(fingerprint -> true),
                        GroupId.parse("group002"), byFingerprint(fingerprint -> true)),
                NodeId.of("server-node"), DeviceId.of("server-device"),
                serverIdentity.fingerprint());

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, new PairingTrustManager(), 0, handler)) {
            server.start();

            assertThrows(IOException.class, () -> query(server.port(), clientIdentity,
                    serverIdentity.fingerprint(), GroupId.parse("group003")));
        }
    }

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
                                GROUP,
                                github.gtouming.itemsync.domain.replication.VersionVector.empty(),
                                null))));

                assertTrue(closed.await(5, TimeUnit.SECONDS));
                assertNull(received.get());
            }
        }
    }

    @Test
    void timesOutWithinTheConfiguredBudget() throws Exception {
        TlsIdentity serverIdentity = TlsIdentity.generate("server");
        TlsIdentity clientIdentity = TlsIdentity.generate("client");

        FrameHandler silent = new FrameHandler() {
            @Override
            public void onFrame(FrameConnection connection, Frame frame) {
            }
        };

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, new PairingTrustManager(), 0, silent)) {
            server.start();

            long started = System.nanoTime();
            PairingStatusException failure = assertThrows(PairingStatusException.class,
                    () -> PairingStatusClient.query("127.0.0.1", server.port(), clientIdentity,
                            serverIdentity.fingerprint(), GROUP, NodeId.of("server-node"),
                            DeviceId.of("server-device"), Duration.ofMillis(300)));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertEquals(PairingStatusException.Reason.TIMEOUT, failure.reason());
            assertTrue(elapsedMillis < 2000, "query took " + elapsedMillis + " ms");
        }
    }

    private static PairingStatusServer server(TlsIdentity identity,
                                              PairingTrustProvider provider) {
        return new PairingStatusServer(GROUP, NodeId.of("server-node"),
                DeviceId.of("server-device"), identity.fingerprint(), provider);
    }

    private static PairingTrustProvider byFingerprint(Predicate<String> trusts) {
        return (group, fingerprint) -> trusts.test(fingerprint);
    }

    private static PairingStatusClient.Result query(int port, TlsIdentity client,
                                                    String serverFingerprint, GroupId group)
            throws IOException {
        return PairingStatusClient.query("127.0.0.1", port, client, serverFingerprint,
                group, NodeId.of("server-node"), DeviceId.of("server-device"),
                Duration.ofSeconds(5));
    }
}
