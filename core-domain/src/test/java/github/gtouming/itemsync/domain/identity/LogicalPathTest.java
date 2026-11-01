package github.gtouming.itemsync.domain.identity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogicalPathTest {

    @Test
    void splitsIntoSegments() {
        assertEquals(List.of("photos", "2026", "spring.jpg"),
                LogicalPath.of("photos/2026/spring.jpg").segments());
    }

    @Test
    void nameReturnsLastSegment() {
        assertEquals("spring.jpg", LogicalPath.of("photos/2026/spring.jpg").name());
        assertEquals("spring.jpg", LogicalPath.of("spring.jpg").name());
    }

    @Test
    void allowsUnicodeSpacesAndWindowsIllegalCharacters() {
        LogicalPath path = LogicalPath.of("照片/2026 春 (final):v1?.txt");

        assertEquals("照片", path.segments().get(0));
        assertEquals("2026 春 (final):v1?.txt", path.name());
    }

    @Test
    void rejectsAbsoluteAndTrailingSeparators() {
        assertThrows(IllegalArgumentException.class, () -> LogicalPath.of("/photos"));
        assertThrows(IllegalArgumentException.class, () -> LogicalPath.of("photos/"));
    }

    @Test
    void rejectsBackslash() {
        assertThrows(IllegalArgumentException.class, () -> LogicalPath.of("photos\\spring.jpg"));
    }

    @Test
    void rejectsEmptySegments() {
        assertThrows(IllegalArgumentException.class, () -> LogicalPath.of("photos//spring.jpg"));
    }

    @Test
    void rejectsDotSegments() {
        assertThrows(IllegalArgumentException.class, () -> LogicalPath.of("."));
        assertThrows(IllegalArgumentException.class, () -> LogicalPath.of(".."));
        assertThrows(IllegalArgumentException.class, () -> LogicalPath.of("photos/../spring.jpg"));
        assertThrows(IllegalArgumentException.class, () -> LogicalPath.of("photos/./spring.jpg"));
    }

    @Test
    void rejectsEmptyAndTooLongPaths() {
        assertThrows(IllegalArgumentException.class, () -> LogicalPath.of(""));
        assertThrows(IllegalArgumentException.class,
                () -> LogicalPath.of("a".repeat(LogicalPath.MAX_LENGTH + 1)));
    }

    @Test
    void rejectsNulCharacter() {
        assertThrows(IllegalArgumentException.class, () -> LogicalPath.of("photos/\0name"));
    }
}
