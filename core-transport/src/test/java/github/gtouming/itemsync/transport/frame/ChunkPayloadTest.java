package github.gtouming.itemsync.transport.frame;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkPayloadTest {

    @Test
    void roundTripsChunk() throws Exception {
        byte[] data = {1, 2, 3, 4};

        Chunk chunk = ChunkPayload.decode(ChunkPayload.encode(99, data));

        assertEquals(99L, chunk.offset());
        assertArrayEquals(data, chunk.data());
    }

    @Test
    void rejectsEmptyData() {
        assertThrows(IllegalArgumentException.class, () -> ChunkPayload.encode(0, new byte[0]));
    }

    @Test
    void rejectsOversizedData() {
        assertThrows(IllegalArgumentException.class,
                () -> ChunkPayload.encode(0, new byte[ChunkPayload.CHUNK_SIZE + 1]));
    }

    @Test
    void rejectsCorruptedChecksum() {
        byte[] payload = ChunkPayload.encode(0, new byte[] {1, 2, 3});
        payload[12] ^= 0x7F;

        assertThrows(ProtocolException.class, () -> ChunkPayload.decode(payload));
    }

    @Test
    void rejectsTruncatedPayload() {
        byte[] payload = Arrays.copyOf(ChunkPayload.encode(0, new byte[] {1, 2, 3}), 10);

        assertThrows(ProtocolException.class, () -> ChunkPayload.decode(payload));
    }
}
