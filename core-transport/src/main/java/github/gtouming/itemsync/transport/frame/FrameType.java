package github.gtouming.itemsync.transport.frame;

public enum FrameType {
    HELLO(1),
    HELLO_ACK(2),
    PING(3),
    PONG(4),
    BLOB_GET(5),
    BLOB_CHUNK(6),
    BLOB_RESULT(7),
    ERROR(8);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static FrameType fromCode(int code) {
        for (FrameType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown frame type code: " + code);
    }
}
