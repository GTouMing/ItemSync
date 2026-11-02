package github.gtouming.itemsync.domain.security;

import github.gtouming.itemsync.domain.identity.GroupId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairingCodeTest {

    private static final GroupId GROUP = GroupId.parse("group001");
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    @Test
    void isSymmetricAndSixDigits() {
        String first = PairingCode.between(GROUP, A, B);

        assertEquals(first, PairingCode.between(GROUP, B, A));
        assertTrue(first.matches("[0-9]{6}"));
    }

    @Test
    void dependsOnGroupAndFingerprints() {
        String base = PairingCode.between(GROUP, A, B);

        assertNotEquals(base, PairingCode.between(GroupId.parse("group002"), A, B));
        assertNotEquals(base, PairingCode.between(GROUP, A, "c".repeat(64)));
    }
}
