package github.gtouming.itemsync.transport.frame;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameCodecTest {

    @Test
    void writesAndReadsControlFrame() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, new Frame(FrameType.PING, FramePayloads.encodePing(new Ping(42))));

        Frame frame = FrameCodec.read(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(FrameType.PING, frame.type());
        assertEquals(42L, FramePayloads.decodePing(frame.payload()).nonce());
    }

    @Test
    void writesAndReadsChunkFrame() throws Exception {
        byte[] data = {1, 2, 3};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, new Frame(FrameType.BLOB_CHUNK, ChunkPayload.encode(10, data)));

        Frame frame = FrameCodec.read(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(FrameType.BLOB_CHUNK, frame.type());
        Chunk chunk = ChunkPayload.decode(frame.payload());
        assertEquals(10L, chunk.offset());
        assertArrayEquals(data, chunk.data());
    }

    @Test
    void rejectsInvalidMagic() throws Exception {
        byte[] bytes = encodedPing();
        bytes[0] = 0;

        assertThrows(ProtocolException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(bytes)));
    }

    @Test
    void rejectsUnsupportedVersion() throws Exception {
        byte[] bytes = encodedPing();
        bytes[2] = 99;

        assertThrows(ProtocolException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(bytes)));
    }

    @Test
    void rejectsUnknownFrameType() throws Exception {
        byte[] bytes = encodedPing();
        bytes[3] = 99;

        assertThrows(ProtocolException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(bytes)));
    }

    @Test
    void rejectsOversizedPayloadLength() {
        ByteBuffer header = ByteBuffer.allocate(8);
        header.putShort((short) FrameCodec.MAGIC);
        header.put((byte) FrameCodec.PROTOCOL_VERSION);
        header.put((byte) FrameType.PING.code());
        header.putInt(FrameCodec.MAX_PAYLOAD + 1);

        assertThrows(ProtocolException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(header.array())));
    }

    @Test
    void rejectsTruncatedHeader() {
        assertThrows(ProtocolException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(new byte[] {0x49})));
    }

    @Test
    void reportsClosedStream() {
        assertThrows(EOFException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void rejectsTruncatedPayload() throws Exception {
        byte[] bytes = encodedPing();
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 1);

        assertThrows(EOFException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(truncated)));
    }

    private static byte[] encodedPing() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, new Frame(FrameType.PING, FramePayloads.encodePing(new Ping(1))));
        return out.toByteArray();
    }
}
