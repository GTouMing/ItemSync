package github.gtouming.itemsync.transport.frame;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public final class FrameCodec {

    public static final int PROTOCOL_VERSION = 1;
    public static final int MAGIC = 0x4953;
    public static final int MAX_PAYLOAD = 8 * 1024 * 1024;

    private static final int HEADER_LENGTH = 8;

    private FrameCodec() {
    }

    public static void write(OutputStream out, Frame frame) throws IOException {
        byte[] payload = frame.payload();
        if (payload.length > MAX_PAYLOAD) {
            throw new ProtocolException("frame payload exceeds " + MAX_PAYLOAD + " bytes: " + payload.length);
        }
        ByteBuffer header = ByteBuffer.allocate(HEADER_LENGTH);
        header.putShort((short) MAGIC);
        header.put((byte) PROTOCOL_VERSION);
        header.put((byte) frame.type().code());
        header.putInt(payload.length);
        out.write(header.array());
        out.write(payload);
        out.flush();
    }

    public static Frame read(InputStream in) throws IOException {
        byte[] header = in.readNBytes(HEADER_LENGTH);
        if (header.length == 0) {
            throw new EOFException("connection closed");
        }
        if (header.length < HEADER_LENGTH) {
            throw new ProtocolException("truncated frame header: " + header.length + " bytes");
        }

        ByteBuffer buffer = ByteBuffer.wrap(header);
        int magic = buffer.getShort() & 0xFFFF;
        int version = buffer.get() & 0xFF;
        int typeCode = buffer.get() & 0xFF;
        int length = buffer.getInt();

        if (magic != MAGIC) {
            throw new ProtocolException("invalid frame magic: 0x" + Integer.toHexString(magic));
        }
        if (version != PROTOCOL_VERSION) {
            throw new ProtocolException("unsupported protocol version: " + version);
        }
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new ProtocolException("invalid frame payload length: " + length);
        }
        FrameType type;
        try {
            type = FrameType.fromCode(typeCode);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(e.getMessage(), e);
        }

        byte[] payload = in.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException("truncated frame payload: " + payload.length + "/" + length);
        }
        return new Frame(type, payload);
    }
}
