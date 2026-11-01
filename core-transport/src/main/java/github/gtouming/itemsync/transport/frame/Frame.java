package github.gtouming.itemsync.transport.frame;

import java.util.Objects;

public record Frame(FrameType type, byte[] payload) {

    public Frame {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
    }
}
