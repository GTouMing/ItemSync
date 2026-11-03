package github.gtouming.itemsync.transport.net;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.frame.Ping;
import github.gtouming.itemsync.transport.tls.GroupPinnedTrustManager;
import github.gtouming.itemsync.transport.tls.TlsIdentity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves that one shared sync port tracks inbound connections per group, so closing one
 * group's connections leaves the other groups online (ADR-0036 §6).
 */
class GroupConnectionTrackingTest {

    private static final GroupId GROUP_A = GroupId.parse("group001");
    private static final GroupId GROUP_B = GroupId.parse("group002");

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
    void groupScopedCloseLeavesTheOtherGroupsConnectionOpen() throws Exception {
        ServerFrames serverFrames = new ServerFrames();
        CountDownLatch aliceClosed = new CountDownLatch(1);
        CountDownLatch bobClosed = new CountDownLatch(1);

        try (TlsTransportServer server = startServer(serverFrames);
             TlsTransportClient alice = connect(server, aliceIdentity, GROUP_A, aliceClosed);
             TlsTransportClient bob = connect(server, bobIdentity, GROUP_B, bobClosed)) {
            awaitRegistered(server);
            assertEquals(GROUP_A, alice.connection().requestedGroup().orElse(null),
                    "the client did not name its group in the SNI");

            assertFalse(server.hasConnection(GROUP_A, bobIdentity.fingerprint()),
                    "group A reported a connection of another group");
            assertFalse(server.hasConnection(GROUP_B, aliceIdentity.fingerprint()),
                    "group B reported a connection of another group");

            server.closeConnections(GROUP_A, aliceIdentity.fingerprint());

            assertTrue(aliceClosed.await(5, TimeUnit.SECONDS),
                    "the revoked connection of " + GROUP_A.value() + " stayed open");
            await(() -> !server.hasConnection(GROUP_A, aliceIdentity.fingerprint()),
                    "the server forgets the closed " + GROUP_A.value() + " connection");
            assertTrue(server.hasConnection(GROUP_B, bobIdentity.fingerprint()),
                    "closing " + GROUP_A.value() + " closed the connection of " + GROUP_B.value());

            bob.send(new Frame(FrameType.PING, FramePayloads.encodePing(new Ping(1))));
            await(() -> serverFrames.countOf(bobIdentity) > 0,
                    "the server still serves frames of " + GROUP_B.value());
            assertEquals(1, bobClosed.getCount(), "the connection of " + GROUP_B.value() + " was closed");
        }
    }

    @Test
    void groupCloseLeavesOtherGroupsConnectionsOpen() throws Exception {
        ServerFrames serverFrames = new ServerFrames();
        CountDownLatch aliceClosed = new CountDownLatch(1);
        CountDownLatch bobClosed = new CountDownLatch(1);

        try (TlsTransportServer server = startServer(serverFrames);
             TlsTransportClient alice = connect(server, aliceIdentity, GROUP_A, aliceClosed);
             TlsTransportClient bob = connect(server, bobIdentity, GROUP_B, bobClosed)) {
            awaitRegistered(server);
            assertEquals(GROUP_B, bob.connection().requestedGroup().orElse(null),
                    "the client did not name its group in the SNI");

            server.closeConnections(GROUP_B);

            assertTrue(bobClosed.await(5, TimeUnit.SECONDS),
                    "every connection of " + GROUP_B.value() + " stayed open");
            await(() -> !server.hasConnection(GROUP_B, bobIdentity.fingerprint()),
                    "the server forgets the closed " + GROUP_B.value() + " connection");
            assertTrue(server.hasConnection(GROUP_A, aliceIdentity.fingerprint()),
                    "closing " + GROUP_B.value() + " closed the connection of " + GROUP_A.value());
            assertEquals(1, aliceClosed.getCount(),
                    "the connection of " + GROUP_A.value() + " was closed");

            alice.send(new Frame(FrameType.PING, FramePayloads.encodePing(new Ping(2))));
            await(() -> serverFrames.countOf(aliceIdentity) > 0,
                    "the server still serves frames of " + GROUP_A.value());
        }
    }

    @Test
    void closeConnectionsStillClosesEveryGroup() throws Exception {
        ServerFrames serverFrames = new ServerFrames();
        CountDownLatch aliceClosed = new CountDownLatch(1);
        CountDownLatch bobClosed = new CountDownLatch(1);

        try (TlsTransportServer server = startServer(serverFrames);
             TlsTransportClient alice = connect(server, aliceIdentity, GROUP_A, aliceClosed);
             TlsTransportClient bob = connect(server, bobIdentity, GROUP_B, bobClosed)) {
            awaitRegistered(server);
            assertEquals(GROUP_A, alice.connection().requestedGroup().orElse(null));
            assertEquals(GROUP_B, bob.connection().requestedGroup().orElse(null));

            assertTrue(server.hasConnection(aliceIdentity.fingerprint()));
            assertTrue(server.hasConnection(bobIdentity.fingerprint()));

            server.closeConnections();

            assertTrue(aliceClosed.await(5, TimeUnit.SECONDS),
                    "the all group close left the " + GROUP_A.value() + " connection open");
            assertTrue(bobClosed.await(5, TimeUnit.SECONDS),
                    "the all group close left the " + GROUP_B.value() + " connection open");
            await(() -> !server.hasConnection(GROUP_A, aliceIdentity.fingerprint())
                            && !server.hasConnection(GROUP_B, bobIdentity.fingerprint()),
                    "the server forgets both closed connections");
            assertFalse(server.hasConnection(aliceIdentity.fingerprint()));
            assertFalse(server.hasConnection(bobIdentity.fingerprint()));
        }
    }

    private static void awaitRegistered(TlsTransportServer server) throws InterruptedException {
        await(() -> server.hasConnection(GROUP_A, aliceIdentity.fingerprint()),
                "the server registers the " + GROUP_A.value() + " connection");
        await(() -> server.hasConnection(GROUP_B, bobIdentity.fingerprint()),
                "the server registers the " + GROUP_B.value() + " connection");
    }

    private static TlsTransportServer startServer(FrameHandler handler) throws IOException {
        GroupPinnedTrustManager trustManager = new GroupPinnedTrustManager(Map.of(
                GROUP_A, Set.of(aliceIdentity.fingerprint()),
                GROUP_B, Set.of(bobIdentity.fingerprint())));
        TlsTransportServer server = new TlsTransportServer(serverIdentity, trustManager, 0, handler);
        server.start();
        return server;
    }

    private static TlsTransportClient connect(TlsTransportServer server, TlsIdentity identity,
                                              GroupId group, CountDownLatch closed)
            throws IOException {
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
        return TlsTransportClient.connect("127.0.0.1", server.port(), identity,
                serverIdentity.fingerprint(), handler, group);
    }

    private static void await(BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("timed out waiting until " + description);
    }

    private static final class ServerFrames implements FrameHandler {

        private final Map<String, AtomicInteger> framesByFingerprint = new ConcurrentHashMap<>();

        @Override
        public void onFrame(FrameConnection connection, Frame frame) {
            framesByFingerprint.computeIfAbsent(
                    connection.peerFingerprint().toLowerCase(Locale.ROOT),
                    key -> new AtomicInteger()).incrementAndGet();
        }

        private int countOf(TlsIdentity identity) {
            AtomicInteger count = framesByFingerprint.get(
                    identity.fingerprint().toLowerCase(Locale.ROOT));
            return count == null ? 0 : count.get();
        }
    }
}
