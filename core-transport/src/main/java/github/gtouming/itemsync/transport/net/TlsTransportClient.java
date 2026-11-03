package github.gtouming.itemsync.transport.net;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.tls.PinnedTrustManager;
import github.gtouming.itemsync.transport.tls.ServerNameIndication;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;

public final class TlsTransportClient implements AutoCloseable {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final FrameConnection connection;

    private TlsTransportClient(FrameConnection connection) {
        this.connection = connection;
    }

    public static TlsTransportClient connect(String host, int port, TlsIdentity identity,
                                             String expectedServerFingerprint, FrameHandler handler)
            throws IOException {
        return connect(host, port, identity, expectedServerFingerprint, handler,
                DEFAULT_CONNECT_TIMEOUT, Duration.ZERO);
    }

    public static TlsTransportClient connect(String host, int port, TlsIdentity identity,
                                             String expectedServerFingerprint, FrameHandler handler,
                                             Duration connectTimeout, Duration readTimeout)
            throws IOException {
        return connect(host, port, identity, expectedServerFingerprint, handler, null,
                connectTimeout, readTimeout);
    }

    /**
     * Connects with the group id as the TLS SNI server name so the shared sync port can
     * select the group's pins during the handshake (ADR-0036).
     */
    public static TlsTransportClient connect(String host, int port, TlsIdentity identity,
                                             String expectedServerFingerprint, FrameHandler handler,
                                             GroupId sniGroup) throws IOException {
        return connect(host, port, identity, expectedServerFingerprint, handler, sniGroup,
                DEFAULT_CONNECT_TIMEOUT, Duration.ZERO);
    }

    public static TlsTransportClient connect(String host, int port, TlsIdentity identity,
                                             String expectedServerFingerprint, FrameHandler handler,
                                             GroupId sniGroup,
                                             Duration connectTimeout, Duration readTimeout)
            throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
        SSLSocket socket = (SSLSocket) identity
                .sslContext(expectedServerFingerprint)
                .getSocketFactory()
                .createSocket();
        try {
            if (sniGroup != null) {
                SSLParameters parameters = socket.getSSLParameters();
                ServerNameIndication.apply(parameters, sniGroup);
                socket.setSSLParameters(parameters);
            }
            socket.connect(new InetSocketAddress(host, port), millis(connectTimeout));
            socket.setSoTimeout(millis(readTimeout));
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

    private static int millis(Duration value) {
        if (value.isZero() || value.isNegative()) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, value.toMillis()));
    }
}
