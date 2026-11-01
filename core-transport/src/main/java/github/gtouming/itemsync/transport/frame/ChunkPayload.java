package github.gtouming.itemsync.transport.frame;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.CRC32;

public final class ChunkPayload {

    public static final int CHUNK_SIZE = 1024 * 1024;

    private static final int HEADER_LENGTH = 12;
    private static final int TRAILER_LENGTH = 4;

    private ChunkPayload() {
    }

    public static byte[] encode(long offset, byte[] data) {
        Objects.requireNonNull(data, "data");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, got " + offset);
        }
        if (data.length == 0 || data.length > CHUNK_SIZE) {
            throw new IllegalArgumentException("chunk length must be 1.." + CHUNK_SIZE + ", got " + data.length);
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + data.length + TRAILER_LENGTH);
        buffer.putLong(offset);
        buffer.putInt(data.length);
        buffer.put(data);
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 0, HEADER_LENGTH + data.length);
        buffer.putInt((int) crc.getValue());
        return buffer.array();
    }

    public static Chunk decode(byte[] payload) throws ProtocolException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length < HEADER_LENGTH + TRAILER_LENGTH + 1) {
            throw new ProtocolException("chunk payload too short: " + payload.length + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        long offset = buffer.getLong();
        int length = buffer.getInt();

        if (offset < 0) {
            throw new ProtocolException("negative chunk offset: " + offset);
        }
        if (length <= 0 || length > CHUNK_SIZE) {
            throw new ProtocolException("invalid chunk length: " + length);
        }
        if (payload.length != HEADER_LENGTH + length + TRAILER_LENGTH) {
            throw new ProtocolException("chunk length mismatch: payload=" + payload.length + ", declared=" + length);
        }

        int expectedChecksum = buffer.getInt(HEADER_LENGTH + length);
        CRC32 crc = new CRC32();
        crc.update(payload, 0, HEADER_LENGTH + length);
        if ((int) crc.getValue() != expectedChecksum) {
            throw new ProtocolException("chunk checksum mismatch at offset " + offset);
        }

        byte[] data = new byte[length];
        buffer.position(HEADER_LENGTH);
        buffer.get(data);
        return new Chunk(offset, data);
    }
}
