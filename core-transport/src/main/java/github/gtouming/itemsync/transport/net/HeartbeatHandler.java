package github.gtouming.itemsync.transport.net;

import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.frame.Ping;
import github.gtouming.itemsync.transport.frame.Pong;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class HeartbeatHandler implements FrameHandler {

    private static final int MAX_MISSED = 2;

    private final FrameHandler delegate;
    private final AtomicInteger missed = new AtomicInteger();
    private final AtomicLong nonce = new AtomicLong();

    public HeartbeatHandler(FrameHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void onFrame(FrameConnection connection, Frame frame) {
        missed.set(0);
        if (frame.type() == FrameType.PING) {
            sendPong(connection, FramePayloads.decodePing(frame.payload()).nonce());
            return;
        }
        if (frame.type() == FrameType.PONG) {
            return;
        }
        delegate.onFrame(connection, frame);
    }

    @Override
    public void onIdle(FrameConnection connection) {
        if (missed.incrementAndGet() > MAX_MISSED) {
            delegate.onError(connection, new IOException("peer did not answer heartbeat"));
            connection.close();
            return;
        }
        try {
            connection.send(new Frame(FrameType.PING,
                    FramePayloads.encodePing(new Ping(nonce.incrementAndGet()))));
        } catch (RuntimeException e) {
            connection.close();
        }
    }

    @Override
    public void onClosed(FrameConnection connection) {
        delegate.onClosed(connection);
    }

    @Override
    public void onError(FrameConnection connection, IOException error) {
        delegate.onError(connection, error);
    }

    private static void sendPong(FrameConnection connection, long nonce) {
        try {
            connection.send(new Frame(FrameType.PONG,
                    FramePayloads.encodePong(new Pong(nonce))));
        } catch (RuntimeException e) {
            connection.close();
        }
    }
}
