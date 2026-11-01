package github.gtouming.itemsync.transport.net;

import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FrameCodec;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.frame.Hello;
import github.gtouming.itemsync.transport.frame.Ping;
import github.gtouming.itemsync.transport.frame.Pong;
import github.gtouming.itemsync.transport.tls.TlsIdentity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TlsTransportTest {

    private static TlsIdentity serverIdentity;
    private static TlsIdentity clientIdentity;

    @BeforeAll
    static void createIdentities() {
        serverIdentity = TlsIdentity.generate("server");
        clientIdentity = TlsIdentity.generate("client");
    }

    @Test
    void exchangesFramesOverMutualTls() throws Exception {
        CountDownLatch helloAck = new CountDownLatch(1);
        CountDownLatch pong = new CountDownLatch(1);
        CountDownLatch serverClosed = new CountDownLatch(1);
        CountDownLatch clientClosed = new CountDownLatch(1);
        AtomicLong pongNonce = new AtomicLong();
        AtomicInteger serverFrames = new AtomicInteger();
        AtomicReference<IOException> serverError = new AtomicReference<>();
        AtomicReference<IOException> clientError = new AtomicReference<>();

        FrameHandler serverHandler = new FrameHandler() {
            @Override
            public void onFrame(FrameConnection connection, Frame frame) {
                serverFrames.incrementAndGet();
                if (frame.type() == FrameType.HELLO) {
                    connection.send(new Frame(FrameType.HELLO_ACK, FramePayloads.encodeHello(new Hello(
                            NodeId.of("server"), FrameCodec.PROTOCOL_VERSION, serverIdentity.fingerprint()))));
                } else if (frame.type() == FrameType.PING) {
                    long nonce = FramePayloads.decodePing(frame.payload()).nonce();
                    connection.send(new Frame(FrameType.PONG, FramePayloads.encodePong(new Pong(nonce))));
                }
            }

            @Override
            public void onClosed(FrameConnection connection) {
                serverClosed.countDown();
            }

            @Override
            public void onError(FrameConnection connection, IOException error) {
                serverError.compareAndSet(null, error);
            }
        };

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, clientIdentity.fingerprint(), 0, serverHandler)) {
            server.start();

            FrameHandler clientHandler = new FrameHandler() {
                @Override
                public void onFrame(FrameConnection connection, Frame frame) {
                    if (frame.type() == FrameType.HELLO_ACK) {
                        helloAck.countDown();
                    } else if (frame.type() == FrameType.PONG) {
                        pongNonce.set(FramePayloads.decodePong(frame.payload()).nonce());
                        pong.countDown();
                    }
                }

                @Override
                public void onClosed(FrameConnection connection) {
                    clientClosed.countDown();
                }

                @Override
                public void onError(FrameConnection connection, IOException error) {
                    clientError.compareAndSet(null, error);
                }
            };

            try (TlsTransportClient client = TlsTransportClient.connect(
                    "127.0.0.1", server.port(), clientIdentity, serverIdentity.fingerprint(), clientHandler)) {
                client.send(new Frame(FrameType.HELLO, FramePayloads.encodeHello(new Hello(
                        NodeId.of("client"), FrameCodec.PROTOCOL_VERSION, clientIdentity.fingerprint()))));

                if (!helloAck.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("no HELLO_ACK"
                            + ", clientError=" + clientError.get()
                            + ", serverError=" + serverError.get()
                            + ", clientClosed=" + (clientClosed.getCount() == 0)
                            + ", serverClosed=" + (serverClosed.getCount() == 0));
                }
                client.send(new Frame(FrameType.PING, FramePayloads.encodePing(new Ping(7))));
                if (!pong.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("no PONG"
                            + ", serverFrames=" + serverFrames.get()
                            + ", clientError=" + clientError.get()
                            + ", serverError=" + serverError.get()
                            + ", clientClosed=" + (clientClosed.getCount() == 0)
                            + ", serverClosed=" + (serverClosed.getCount() == 0));
                }
                assertEquals(7L, pongNonce.get());
            }
        }
    }

    @Test
    void rejectsUnpinnedServerCertificate() throws Exception {
        FrameHandler noop = (connection, frame) -> {
        };

        try (TlsTransportServer server = new TlsTransportServer(
                serverIdentity, clientIdentity.fingerprint(), 0, noop)) {
            server.start();

            assertThrows(SSLException.class, () -> TlsTransportClient.connect(
                    "127.0.0.1", server.port(), clientIdentity, "0".repeat(64), noop));
        }
    }
}
