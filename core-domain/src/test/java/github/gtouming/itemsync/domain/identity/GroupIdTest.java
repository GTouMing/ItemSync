package github.gtouming.itemsync.domain.identity;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroupIdTest {

    @Test
    void parseNormalizesCaseAndWhitespace() {
        assertEquals("abcd1234", GroupId.parse("  ABCD1234  ").value());
    }

    @Test
    void rejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> GroupId.parse("1234567"));
        assertThrows(IllegalArgumentException.class, () -> GroupId.parse("123456789"));
    }

    @Test
    void rejectsIllegalCharacters() {
        assertThrows(IllegalArgumentException.class, () -> GroupId.parse("abcd-123"));
        assertThrows(IllegalArgumentException.class, () -> GroupId.parse("abcd_123"));
        assertThrows(IllegalArgumentException.class, () -> GroupId.parse("abcd 123"));
        assertThrows(IllegalArgumentException.class, () -> GroupId.parse("abcd123\u00e9"));
    }

    @Test
    void requiresLowercaseWhenConstructingDirectly() {
        assertThrows(IllegalArgumentException.class, () -> new GroupId("ABCD1234"));
    }

    @Test
    void randomProducesEightLowercaseAlphanumerics() {
        for (int i = 0; i < 200; i++) {
            String value = GroupId.random().value();
            assertEquals(GroupId.LENGTH, value.length());
            assertEquals(value.toLowerCase(Locale.ROOT), value);
            for (int c = 0; c < value.length(); c++) {
                char ch = value.charAt(c);
                boolean valid = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z');
                if (!valid) {
                    throw new AssertionError("unexpected character: " + ch);
                }
            }
        }
    }
}
