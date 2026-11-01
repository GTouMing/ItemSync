package github.gtouming.itemsync.domain.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlobHashTest {

    private static final String VALID = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    void parseAcceptsLowercaseHex() {
        assertEquals(VALID, BlobHash.parse(VALID).value());
    }

    @Test
    void parseNormalizesUppercaseAndWhitespace() {
        assertEquals(VALID, BlobHash.parse("  " + VALID.toUpperCase() + "  ").value());
    }

    @Test
    void rejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> BlobHash.parse(VALID.substring(1)));
        assertThrows(IllegalArgumentException.class, () -> BlobHash.parse(VALID + "0"));
    }

    @Test
    void rejectsNonHexCharacters() {
        assertThrows(IllegalArgumentException.class, () -> BlobHash.parse("g".repeat(64)));
    }

    @Test
    void ofSha256FormatsBytesAsHex() {
        byte[] digest = new byte[32];
        for (int i = 0; i < digest.length; i++) {
            digest[i] = (byte) i;
        }

        assertEquals(VALID, BlobHash.ofSha256(digest).value());
    }

    @Test
    void ofSha256RejectsWrongDigestLength() {
        assertThrows(IllegalArgumentException.class, () -> BlobHash.ofSha256(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> BlobHash.ofSha256(new byte[33]));
    }
}
