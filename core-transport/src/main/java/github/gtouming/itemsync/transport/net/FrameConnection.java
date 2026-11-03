package github.gtouming.itemsync.transport.net;

import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FrameCodec;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FrameConnection implements AutoCloseable {

    private final SSLSocket socket;
    private final InputStream input;
    private final OutputStream output;
    private final FrameHandler handler;
    private final String peerFingerprint;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread reader;

    FrameConnection(SSLSocket socket, FrameHandler handler) throws IOException {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.input = socket.getInputStream();
        this.output = socket.getOutputStream();
        this.peerFingerprint = peerFingerprint(socket);
        this.reader = new Thread(this::readLoop, "itemsync-transport-reader");
        this.reader.setDaemon(true);
    }

    public String peerFingerprint() {
        return peerFingerprint;
    }

    public void start() {
        reader.start();
    }

    public void send(Frame frame) {
        synchronized (output) {
            try {
                FrameCodec.write(output, frame);
            } catch (IOException e) {
                close();
                throw new UncheckedIOException("send frame failed", e);
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing is best effort
            }
        }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                try {
                    handler.onFrame(this, FrameCodec.read(input));
                } catch (SocketTimeoutException e) {
                    if (!closed.get()) {
                        handler.onIdle(this);
                    }
                }
            }
        } catch (EOFException | SocketException e) {
            // peer closed the connection
        } catch (IOException e) {
            if (!closed.get()) {
                handler.onError(this, e);
            }
        } catch (RuntimeException e) {
            if (!closed.get()) {
                handler.onError(this, new IOException("frame handler failed", e));
            }
        } finally {
            close();
            handler.onClosed(this);
        }
    }

    private static String peerFingerprint(SSLSocket socket) throws IOException {
        try {
            X509Certificate certificate =
                    (X509Certificate) socket.getSession().getPeerCertificates()[0];
            return TlsIdentity.fingerprint(certificate);
        } catch (SSLPeerUnverifiedException e) {
            throw new IOException("peer certificate is not verified", e);
        }
    }
}
