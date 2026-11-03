package github.gtouming.itemsync.transport.net;

import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.frame.Ping;
import github.gtouming.itemsync.transport.frame.Pong;
import github.gtouming.itemsync.transport.tls.PinnedTrustManager;
import github.gtouming.itemsync.transport.tls.TlsIdentity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatHandlerTest {

    private static final Duration IDLE = Duration.ofMillis(500);

    private static TlsIdentity serverIdentity;
    private static TlsIdentity clientIdentity;

    @BeforeAll
    static void createIdentities() {
        serverIdentity = TlsIdentity.generate("server");
        clientIdentity = TlsIdentity.generate("client");
    }

    @Test
    void keepsAnIdleConnectionAlive() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        FrameHandler serverDelegate = new FrameHandler() {
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

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, new PinnedTrustManager(clientIdentity.fingerprint()), 0,
                new HeartbeatHandler(serverDelegate), null, IDLE)) {
            server.start();

            CountDownLatch clientClosed = new CountDownLatch(1);
            FrameHandler clientDelegate = new FrameHandler() {
                @Override
                public void onFrame(FrameConnection connection, Frame frame) {
                }

                @Override
                public void onClosed(FrameConnection connection) {
                    clientClosed.countDown();
                }

                @Override
                public void onError(FrameConnection connection, IOException error) {
                    clientClosed.countDown();
                }
            };

            try (TlsTransportClient client = TlsTransportClient.connect(
                    "127.0.0.1", server.port(), clientIdentity, serverIdentity.fingerprint(),
                    new HeartbeatHandler(clientDelegate), Duration.ofSeconds(2), IDLE)) {
                assertFalse(clientClosed.await(2, TimeUnit.SECONDS));
                assertFalse(closed.await(0, TimeUnit.SECONDS));
                assertTrue(client.connection() != null);
            }
        }
    }

    @Test
    void answersPingWithoutCallingTheDelegate() throws Exception {
        CountDownLatch pong = new CountDownLatch(1);
        AtomicReference<Long> nonce = new AtomicReference<>();
        AtomicReference<Boolean> pingSent = new AtomicReference<>(false);
        FrameHandler serverDelegate = new FrameHandler() {
            @Override
            public void onFrame(FrameConnection connection, Frame frame) {
                if (frame.type() == FrameType.PING && pingSent.compareAndSet(false, true)) {
                    connection.send(new Frame(FrameType.PING,
                            FramePayloads.encodePing(new Ping(7))));
                } else if (frame.type() == FrameType.PONG) {
                    nonce.set(FramePayloads.decodePong(frame.payload()).nonce());
                    pong.countDown();
                }
            }
        };

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, new PinnedTrustManager(clientIdentity.fingerprint()), 0,
                serverDelegate)) {
            server.start();

            AtomicReference<Frame> delegated = new AtomicReference<>();
            FrameHandler clientDelegate = new FrameHandler() {
                @Override
                public void onFrame(FrameConnection connection, Frame frame) {
                    delegated.set(frame);
                }
            };

            try (TlsTransportClient client = TlsTransportClient.connect(
                    "127.0.0.1", server.port(), clientIdentity, serverIdentity.fingerprint(),
                    new HeartbeatHandler(clientDelegate), Duration.ofSeconds(2), Duration.ZERO)) {
                client.send(new Frame(FrameType.PING, FramePayloads.encodePing(new Ping(7))));

                assertTrue(pong.await(5, TimeUnit.SECONDS));
                assertTrue(nonce.get() == 7L);
                assertTrue(delegated.get() == null);
            }
        }
    }

    @Test
    void closesWhenThePeerStopsAnswering() throws Exception {
        FrameHandler silentServer = new FrameHandler() {
            @Override
            public void onFrame(FrameConnection connection, Frame frame) {
            }
        };

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, new PinnedTrustManager(clientIdentity.fingerprint()), 0,
                silentServer)) {
            server.start();

            CountDownLatch failed = new CountDownLatch(1);
            FrameHandler clientDelegate = new FrameHandler() {
                @Override
                public void onFrame(FrameConnection connection, Frame frame) {
                }

                @Override
                public void onClosed(FrameConnection connection) {
                    failed.countDown();
                }

                @Override
                public void onError(FrameConnection connection, IOException error) {
                    failed.countDown();
                }
            };

            try (TlsTransportClient client = TlsTransportClient.connect(
                    "127.0.0.1", server.port(), clientIdentity, serverIdentity.fingerprint(),
                    new HeartbeatHandler(clientDelegate), Duration.ofSeconds(2), IDLE)) {
                assertTrue(client.connection() != null);
                assertTrue(failed.await(5, TimeUnit.SECONDS));
            }
        }
    }
}
