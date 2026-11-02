package github.gtouming.itemsync.domain.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPasswordTest {

    @Test
    void hashesAndVerifies() {
        String encoded = LocalPassword.hash("correct horse".toCharArray());

        assertTrue(LocalPassword.verify("correct horse".toCharArray(), encoded));
        assertFalse(LocalPassword.verify("wrong horse".toCharArray(), encoded));
    }

    @Test
    void usesAFreshSalt() {
        assertNotEquals(
                LocalPassword.hash("same password".toCharArray()),
                LocalPassword.hash("same password".toCharArray()));
    }

    @Test
    void rejectsEmptyPassword() {
        assertThrows(IllegalArgumentException.class, () -> LocalPassword.hash(new char[0]));
    }

    @Test
    void rejectsMalformedHashes() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalPassword.verify("x".toCharArray(), ""));
        assertThrows(IllegalArgumentException.class,
                () -> LocalPassword.verify("x".toCharArray(), "argon2id$v=19$m=65536,t=3,p=1$bad$bad"));
        assertThrows(IllegalArgumentException.class,
                () -> LocalPassword.verify("x".toCharArray(),
                        "argon2id$v=19$m=1,t=3,p=1$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
    }
}
