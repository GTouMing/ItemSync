package github.gtouming.itemsync.domain.replication;

import github.gtouming.itemsync.domain.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionVectorCodecTest {

    private static final NodeId A = NodeId.of("node-a");
    private static final NodeId B = NodeId.of("node-b");

    @Test
    void encodesEmptyVectorAsEmptyObject() {
        assertEquals("{}", VersionVectorCodec.encode(VersionVector.empty()));
    }

    @Test
    void encodesCountersInNodeOrderWithoutWhitespace() {
        VersionVector vector = VersionVector.of(Map.of(B, 3L, A, 1L));

        assertEquals("{\"node-a\":1,\"node-b\":3}", VersionVectorCodec.encode(vector));
    }

    @Test
    void escapesSpecialCharactersInNodeIds() {
        VersionVector vector = VersionVector.of(Map.of(NodeId.of("a\"b\\c"), 2L));

        assertEquals("{\"a\\\"b\\\\c\":2}", VersionVectorCodec.encode(vector));
    }

    @Test
    void decodesEmptyObject() {
        assertEquals(VersionVector.empty(), VersionVectorCodec.decode("{}"));
    }

    @Test
    void decodesCountersRegardlessOfKeyOrder() {
        VersionVector expected = VersionVector.of(Map.of(A, 1L, B, 3L));

        assertEquals(expected, VersionVectorCodec.decode("{\"node-b\":3,\"node-a\":1}"));
    }

    @Test
    void roundTripsUnicodeAndEscapedNodeIds() {
        VersionVector vector = VersionVector.of(Map.of(
                NodeId.of("节点-一"), 2L,
                NodeId.of("a\"b\\c"), 1L));

        assertEquals(vector, VersionVectorCodec.decode(VersionVectorCodec.encode(vector)));
    }

    @Test
    void rejectsNonPositiveCounters() {
        assertThrows(IllegalArgumentException.class, () -> VersionVectorCodec.decode("{\"node-a\":0}"));
        assertThrows(IllegalArgumentException.class, () -> VersionVectorCodec.decode("{\"node-a\":-1}"));
    }

    @Test
    void rejectsNonIntegralCounters() {
        assertThrows(IllegalArgumentException.class, () -> VersionVectorCodec.decode("{\"node-a\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> VersionVectorCodec.decode("{\"node-a\":1e3}"));
    }

    @Test
    void rejectsNonNumericCounters() {
        assertThrows(IllegalArgumentException.class, () -> VersionVectorCodec.decode("{\"node-a\":\"1\"}"));
        assertThrows(IllegalArgumentException.class, () -> VersionVectorCodec.decode("{\"node-a\":true}"));
        assertThrows(IllegalArgumentException.class, () -> VersionVectorCodec.decode("{\"node-a\":null}"));
    }

    @Test
    void rejectsOutOfRangeCounters() {
        assertThrows(IllegalArgumentException.class,
                () -> VersionVectorCodec.decode("{\"node-a\":99999999999999999999}"));
    }

    @Test
    void rejectsMalformedOrNonObjectJson() {
        assertThrows(IllegalArgumentException.class, () -> VersionVectorCodec.decode("[]"));
        assertThrows(IllegalArgumentException.class, () -> VersionVectorCodec.decode("null"));
        assertThrows(IllegalArgumentException.class, () -> VersionVectorCodec.decode("\"text\""));
        assertThrows(IllegalArgumentException.class, () -> VersionVectorCodec.decode("{"));
        assertThrows(IllegalArgumentException.class, () -> VersionVectorCodec.decode(""));
    }

    @Test
    void rejectsBlankNodeIds() {
        assertThrows(IllegalArgumentException.class, () -> VersionVectorCodec.decode("{\"\":1}"));
    }
}
