package github.gtouming.itemsync.transport.net;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.tls.GroupPinnedTrustManager;
import github.gtouming.itemsync.transport.tls.PinnedTrustManager;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.SocketException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class TlsTransportServer implements AutoCloseable {

    private final SSLServerSocket serverSocket;
    private final FrameHandler handler;
    private final Consumer<IOException> handshakeFailure;
    private final int readTimeoutMillis;
    private final Set<FrameConnection> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "itemsync-transport-server");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong connectionEpoch = new AtomicLong();
    private Thread acceptThread;

    public TlsTransportServer(TlsIdentity identity, String expectedClientFingerprint,
                              int port, FrameHandler handler) throws IOException {
        this(identity, Compat.set(expectedClientFingerprint), port, handler);
    }

    public TlsTransportServer(TlsIdentity identity, Set<String> expectedClientFingerprints,
                              int port, FrameHandler handler) throws IOException {
        this(identity, new PinnedTrustManager(expectedClientFingerprints), port, handler);
    }

    public TlsTransportServer(TlsIdentity identity, PinnedTrustManager trustManager,
                              int port, FrameHandler handler) throws IOException {
        this(identity, (X509TrustManager) trustManager, port, handler);
    }

    /**
     * Serves inbound connections with per-group certificate pins selected by the
     * handshake SNI (ADR-0036).
     */
    public TlsTransportServer(TlsIdentity identity, GroupPinnedTrustManager trustManager,
                              int port, FrameHandler handler) throws IOException {
        this(identity, (X509TrustManager) trustManager, port, handler);
    }

    public TlsTransportServer(TlsIdentity identity, GroupPinnedTrustManager trustManager,
                              int port, FrameHandler handler,
                              Consumer<IOException> handshakeFailure,
                              Duration readTimeout) throws IOException {
        this(identity, (X509TrustManager) trustManager, port, handler, handshakeFailure, readTimeout);
    }

    public TlsTransportServer(TlsIdentity identity, X509TrustManager trustManager,
                              int port, FrameHandler handler) throws IOException {
        this(identity, trustManager, port, handler, null);
    }

    public TlsTransportServer(TlsIdentity identity, X509TrustManager trustManager,
                              int port, FrameHandler handler,
                              Consumer<IOException> handshakeFailure) throws IOException {
        this(identity, trustManager, port, handler, handshakeFailure, Duration.ZERO);
    }

    public TlsTransportServer(TlsIdentity identity, X509TrustManager trustManager,
                              int port, FrameHandler handler,
                              Consumer<IOException> handshakeFailure,
                              Duration readTimeout) throws IOException {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(trustManager, "trustManager");
        Objects.requireNonNull(readTimeout, "readTimeout");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.handshakeFailure = handshakeFailure;
        this.readTimeoutMillis = millis(readTimeout);
        SSLServerSocket socket = (SSLServerSocket) identity
                .sslContext(trustManager)
                .getServerSocketFactory()
                .createServerSocket(port);
        socket.setNeedClientAuth(true);
        socket.setEnabledProtocols(new String[] {TlsIdentity.PROTOCOL});
        socket.setReuseAddress(true);
        this.serverSocket = socket;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("server already started");
        }
        acceptThread = new Thread(this::acceptLoop, "itemsync-transport-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void closeConnections() {
        connectionEpoch.incrementAndGet();
        for (FrameConnection connection : connections) {
            connection.close();
        }
        connections.clear();
    }

    public boolean hasConnection(String fingerprint) {
        if (fingerprint == null) {
            return false;
        }
        String key = fingerprint.trim();
        for (FrameConnection connection : connections) {
            if (connection.peerFingerprint().equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        connectionEpoch.incrementAndGet();
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // closing is best effort
        }
        for (FrameConnection connection : connections) {
            connection.close();
        }
        connections.clear();
        workers.shutdownNow();
    }

    private static int millis(Duration value) {
        if (value.isZero() || value.isNegative()) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, value.toMillis()));
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                SSLSocket socket = (SSLSocket) serverSocket.accept();
                workers.execute(() -> serve(socket));
            } catch (SocketException e) {
                break;
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
            }
        }
    }

    private void serve(SSLSocket socket) {
        long startedEpoch = connectionEpoch.get();
        try {
            socket.setSoTimeout(readTimeoutMillis);
            socket.setUseClientMode(false);
            socket.setEnabledProtocols(new String[] {TlsIdentity.PROTOCOL});
            socket.startHandshake();
            FrameConnection connection = new FrameConnection(socket, trackingHandler());
            connections.add(connection);
            if (connectionEpoch.get() != startedEpoch) {
                connections.remove(connection);
                connection.close();
                return;
            }
            connection.start();
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing is best effort
            }
            if (handshakeFailure != null
                    && PinnedTrustManager.findFingerprintMismatch(e) != null) {
                handshakeFailure.accept(new IOException(
                        "inbound peer certificate does not match the pinned fingerprint", e));
            }
        }
    }

    private FrameHandler trackingHandler() {
        return new FrameHandler() {
            @Override
            public void onFrame(FrameConnection connection, Frame frame) {
                handler.onFrame(connection, frame);
            }

            @Override
            public void onIdle(FrameConnection connection) {
                handler.onIdle(connection);
            }

            @Override
            public void onClosed(FrameConnection connection) {
                connections.remove(connection);
                handler.onClosed(connection);
            }

            @Override
            public void onError(FrameConnection connection, IOException error) {
                handler.onError(connection, error);
            }
        };
    }
}
