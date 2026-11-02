package github.gtouming.itemsync.domain.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeUnlockKeyTest {

    @Test
    void generatesAndRoundTrips() {
        NodeUnlockKey key = NodeUnlockKey.generate();

        assertEquals(32, key.bytes().length);
        assertEquals(key, NodeUnlockKey.fromBase64(key.base64()));
        assertArrayEquals(key.bytes(), NodeUnlockKey.of(key.bytes()).bytes());
        assertEquals(key.base64(), new String(key.keystorePassword()));
    }

    @Test
    void generatesDistinctKeys() {
        assertNotEquals(NodeUnlockKey.generate(), NodeUnlockKey.generate());
    }

    @Test
    void rejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> NodeUnlockKey.of(new byte[31]));
    }

    @Test
    void redactsToString() {
        assertEquals("NodeUnlockKey[redacted]", NodeUnlockKey.generate().toString());
    }
}
