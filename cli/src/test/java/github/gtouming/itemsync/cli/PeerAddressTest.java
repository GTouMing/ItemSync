package github.gtouming.itemsync.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PeerAddressTest {

    @Test
    void parsesAddress() {
        PeerAddress address = PeerAddress.parse("192.168.1.10:47800:abc123");

        assertEquals("192.168.1.10", address.host());
        assertEquals(47800, address.port());
        assertEquals("abc123", address.fingerprint());
    }

    @Test
    void rejectsMissingParts() {
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.parse("host:47800"));
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.parse("host::fingerprint"));
    }

    @Test
    void rejectsInvalidPort() {
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.parse("host:0:fingerprint"));
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.parse("host:70000:fingerprint"));
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.parse("host:abc:fingerprint"));
    }
}
