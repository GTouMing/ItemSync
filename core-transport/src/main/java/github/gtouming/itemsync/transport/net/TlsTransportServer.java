package github.gtouming.itemsync.transport.net;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.tls.PinnedTrustManager;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.SocketException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TlsTransportServer implements AutoCloseable {

    private final SSLServerSocket serverSocket;
    private final FrameHandler handler;
    private final Set<FrameConnection> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "itemsync-transport-server");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
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

    public TlsTransportServer(TlsIdentity identity, X509TrustManager trustManager,
                              int port, FrameHandler handler) throws IOException {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(trustManager, "trustManager");
        this.handler = Objects.requireNonNull(handler, "handler");
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
        for (FrameConnection connection : connections) {
            connection.close();
        }
        connections.clear();
    }

    @Override
    public void close() {
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
        try {
            socket.setUseClientMode(false);
            socket.setEnabledProtocols(new String[] {TlsIdentity.PROTOCOL});
            socket.startHandshake();
            FrameConnection connection = new FrameConnection(socket, trackingHandler());
            connections.add(connection);
            connection.start();
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing is best effort
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
