package github.gtouming.itemsync.transport.net;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.frame.Ping;
import github.gtouming.itemsync.transport.tls.GroupPinnedTrustManager;
import github.gtouming.itemsync.transport.tls.PinnedTrustManager;
import github.gtouming.itemsync.transport.tls.TlsIdentity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that one shared TLS port selects the group's pins from the handshake SNI and
 * rejects connections that do not name a routed group (ADR-0036).
 */
class GroupPinnedTlsTest {

    private static final GroupId GROUP_A = GroupId.parse("group001");
    private static final GroupId GROUP_B = GroupId.parse("group002");
    private static final GroupId UNROUTED = GroupId.parse("group009");

    private static final FrameHandler NOOP_HANDLER = (connection, frame) -> {
    };

    private static TlsIdentity serverIdentity;
    private static TlsIdentity aliceIdentity;
    private static TlsIdentity bobIdentity;

    @BeforeAll
    static void createIdentities() {
        serverIdentity = TlsIdentity.generate("server");
        aliceIdentity = TlsIdentity.generate("alice");
        bobIdentity = TlsIdentity.generate("bob");
    }

    @Test
    void selectsTheGroupFromTheHandshakeSni() throws Exception {
        AtomicReference<GroupId> serverSideGroup = new AtomicReference<>();
        CountDownLatch frameArrived = new CountDownLatch(1);

        try (TlsTransportServer server = startServer((connection, frame) -> {
            serverSideGroup.compareAndSet(null, connection.requestedGroup().orElse(null));
            frameArrived.countDown();
        }, null)) {
            try (TlsTransportClient client = connect(server, aliceIdentity, GROUP_A)) {
                client.send(new Frame(FrameType.PING, FramePayloads.encodePing(new Ping(1))));

                assertTrue(frameArrived.await(5, TimeUnit.SECONDS),
                        "server did not receive the frame from the " + GROUP_A.value() + " client");
                assertNotNull(serverSideGroup.get(),
                        "the server could not read the SNI group from the TLS session");
                assertEquals(GROUP_A, serverSideGroup.get());
                assertEquals(GROUP_A, client.connection().requestedGroup().orElse(null));
            }
        }
    }

    @Test
    void rejectsAClientPinnedForAnotherGroup() throws Exception {
        CountDownLatch rejected = new CountDownLatch(1);
        AtomicReference<IOException> handshakeFailure = new AtomicReference<>();
        AtomicInteger frames = new AtomicInteger();

        try (TlsTransportServer server = startServer((connection, frame) -> frames.incrementAndGet(),
                failure -> {
                    handshakeFailure.compareAndSet(null, failure);
                    rejected.countDown();
                })) {
            assertConnectionRejected(server, aliceIdentity, GROUP_B);

            assertTrue(rejected.await(5, TimeUnit.SECONDS),
                    "the server did not report the cross group handshake failure");
            PinnedTrustManager.FingerprintMismatchException mismatch =
                    PinnedTrustManager.findFingerprintMismatch(handshakeFailure.get());
            assertNotNull(mismatch, "the group pins were not applied: " + handshakeFailure.get());
            assertTrue(mismatch.actualFingerprint().equalsIgnoreCase(aliceIdentity.fingerprint()));
            assertEquals(0, frames.get(), "a rejected connection delivered frames");
        }
    }

    @Test
    void rejectsAConnectionWithoutSni() throws Exception {
        CountDownLatch rejected = new CountDownLatch(1);
        AtomicReference<IOException> handshakeFailure = new AtomicReference<>();

        try (TlsTransportServer server = startServer(NOOP_HANDLER, failure -> {
            handshakeFailure.compareAndSet(null, failure);
            rejected.countDown();
        })) {
            assertConnectionRejected(server, aliceIdentity, null);

            // a missing SNI is rejected before the certificate is compared with any group
            assertFalse(rejected.await(500, TimeUnit.MILLISECONDS),
                    "a connection without SNI reached the fingerprint comparison");
            assertNull(handshakeFailure.get());
        }
    }

    @Test
    void rejectsAnUnroutedSniGroup() throws Exception {
        CountDownLatch rejected = new CountDownLatch(1);
        AtomicReference<IOException> handshakeFailure = new AtomicReference<>();

        try (TlsTransportServer server = startServer(NOOP_HANDLER, failure -> {
            handshakeFailure.compareAndSet(null, failure);
            rejected.countDown();
        })) {
            assertConnectionRejected(server, aliceIdentity, UNROUTED);

            assertFalse(rejected.await(500, TimeUnit.MILLISECONDS),
                    "an unrouted SNI group reached the fingerprint comparison");
            assertNull(handshakeFailure.get());
        }
    }

    /**
     * Asserts that the server refused the handshake. With TLS 1.3 the client finishes its own
     * handshake before the server verifies the client certificate, so the refusal is either an
     * exception from {@code startHandshake} or the immediate close of the connection.
     */
    private static void assertConnectionRejected(TlsTransportServer server, TlsIdentity identity,
                                                 GroupId sniGroup) throws InterruptedException {
        CountDownLatch closed = new CountDownLatch(1);
        FrameHandler handler = new FrameHandler() {
            @Override
            public void onFrame(FrameConnection connection, Frame frame) {
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
        TlsTransportClient client;
        try {
            client = sniGroup == null
                    ? TlsTransportClient.connect("127.0.0.1", server.port(), identity,
                            serverIdentity.fingerprint(), handler)
                    : TlsTransportClient.connect("127.0.0.1", server.port(), identity,
                            serverIdentity.fingerprint(), handler, sniGroup);
        } catch (IOException rejectedDuringHandshake) {
            return;
        }
        try {
            assertTrue(closed.await(5, TimeUnit.SECONDS),
                    "the server did not close the rejected connection");
        } finally {
            client.close();
        }
    }

    private static TlsTransportServer startServer(FrameHandler handler,
                                                  Consumer<IOException> handshakeFailure)
            throws IOException {
        GroupPinnedTrustManager trustManager = new GroupPinnedTrustManager(Map.of(
                GROUP_A, Set.of(aliceIdentity.fingerprint()),
                GROUP_B, Set.of(bobIdentity.fingerprint())));
        TlsTransportServer server = handshakeFailure == null
                ? new TlsTransportServer(serverIdentity, trustManager, 0, handler)
                : new TlsTransportServer(serverIdentity, trustManager, 0, handler,
                        handshakeFailure, Duration.ZERO);
        server.start();
        return server;
    }

    private static TlsTransportClient connect(TlsTransportServer server, TlsIdentity identity,
                                              GroupId sniGroup) throws IOException {
        if (sniGroup == null) {
            return TlsTransportClient.connect("127.0.0.1", server.port(), identity,
                    serverIdentity.fingerprint(), NOOP_HANDLER);
        }
        return TlsTransportClient.connect("127.0.0.1", server.port(), identity,
                serverIdentity.fingerprint(), NOOP_HANDLER, sniGroup);
    }
}
