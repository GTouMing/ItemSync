package github.gtouming.itemsync.transport.net;

import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.tls.PinnedTrustManager;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;

public final class TlsTransportClient implements AutoCloseable {

    private final FrameConnection connection;

    private TlsTransportClient(FrameConnection connection) {
        this.connection = connection;
    }

    public static TlsTransportClient connect(String host, int port, TlsIdentity identity,
                                             String expectedServerFingerprint, FrameHandler handler)
            throws IOException {
        return connect(host, port, identity, expectedServerFingerprint, handler, null);
    }

    public static TlsTransportClient connect(String host, int port, TlsIdentity identity,
                                             String expectedServerFingerprint, FrameHandler handler,
                                             Duration timeout) throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(identity, "identity");
        int timeoutMillis = timeout == null
                ? 0
                : (int) Math.min(Integer.MAX_VALUE, Math.max(1, timeout.toMillis()));
        SSLSocket socket = (SSLSocket) identity
                .sslContext(expectedServerFingerprint)
                .getSocketFactory()
                .createSocket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            socket.setUseClientMode(true);
            socket.setEnabledProtocols(new String[] {TlsIdentity.PROTOCOL});
            socket.startHandshake();
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing is best effort
            }
            PinnedTrustManager.FingerprintMismatchException mismatch =
                    PinnedTrustManager.findFingerprintMismatch(e);
            if (mismatch != null) {
                throw new PeerCertificateMismatchException(mismatch.actualFingerprint(), e);
            }
            throw e;
        }
        FrameConnection connection = new FrameConnection(socket, handler);
        connection.start();
        return new TlsTransportClient(connection);
    }

    public FrameConnection connection() {
        return connection;
    }

    public void send(Frame frame) {
        connection.send(frame);
    }

    @Override
    public void close() {
        connection.close();
    }
}
