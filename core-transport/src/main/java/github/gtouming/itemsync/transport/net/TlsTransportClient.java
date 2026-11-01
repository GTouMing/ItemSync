package github.gtouming.itemsync.transport.net;

import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.util.Objects;

public final class TlsTransportClient implements AutoCloseable {

    private final FrameConnection connection;

    private TlsTransportClient(FrameConnection connection) {
        this.connection = connection;
    }

    public static TlsTransportClient connect(String host, int port, TlsIdentity identity,
                                             String expectedServerFingerprint, FrameHandler handler)
            throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(identity, "identity");
        SSLSocket socket = (SSLSocket) identity
                .sslContext(expectedServerFingerprint)
                .getSocketFactory()
                .createSocket(host, port);
        try {
            socket.setUseClientMode(true);
            socket.setEnabledProtocols(new String[] {TlsIdentity.PROTOCOL});
            socket.startHandshake();
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing is best effort
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
