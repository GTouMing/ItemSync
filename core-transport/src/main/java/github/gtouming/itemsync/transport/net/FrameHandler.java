package github.gtouming.itemsync.transport.net;

import github.gtouming.itemsync.transport.frame.Frame;

import java.io.IOException;

public interface FrameHandler {

    void onFrame(FrameConnection connection, Frame frame);

    default void onIdle(FrameConnection connection) {
        connection.close();
    }

    default void onClosed(FrameConnection connection) {
    }

    default void onError(FrameConnection connection, IOException error) {
    }
}
