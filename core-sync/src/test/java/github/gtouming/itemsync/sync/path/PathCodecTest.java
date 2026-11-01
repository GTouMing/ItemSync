package github.gtouming.itemsync.sync.path;

import github.gtouming.itemsync.domain.identity.LogicalPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathCodecTest {

    @Test
    void escapesWindowsIllegalCharacters() {
        assertEquals("a%3Ab", PathCodec.encodeWindowsSegment("a:b"));
        assertEquals("a%2Ab", PathCodec.encodeWindowsSegment("a*b"));
        assertEquals("a%3Fb", PathCodec.encodeWindowsSegment("a?b"));
        assertEquals("a%22b", PathCodec.encodeWindowsSegment("a\"b"));
        assertEquals("a%3Cb%3E", PathCodec.encodeWindowsSegment("a<b>"));
        assertEquals("a%7Cb", PathCodec.encodeWindowsSegment("a|b"));
        assertEquals("a%01b", PathCodec.encodeWindowsSegment("a\u0001b"));
    }

    @Test
    void escapesPercentSign() {
        assertEquals("100%25.txt", PathCodec.encodeWindowsSegment("100%.txt"));
    }

    @Test
    void escapesTrailingSpacesAndDots() {
        assertEquals("name%20", PathCodec.encodeWindowsSegment("name "));
        assertEquals("name%2E", PathCodec.encodeWindowsSegment("name."));
        assertEquals("name%2E%2E", PathCodec.encodeWindowsSegment("name.."));
        assertEquals("a%20%2E", PathCodec.encodeWindowsSegment("a ."));
        assertEquals("%2E%2E%2E", PathCodec.encodeWindowsSegment("..."));
    }

    @Test
    void escapesWindowsReservedNames() {
        assertEquals("%43%4F%4E", PathCodec.encodeWindowsSegment("CON"));
        assertEquals("%63%6F%6E.txt", PathCodec.encodeWindowsSegment("con.txt"));
        assertEquals("%43%4F%4D%31.log", PathCodec.encodeWindowsSegment("COM1.log"));
        assertEquals("CONSOLE", PathCodec.encodeWindowsSegment("CONSOLE"));
        assertEquals("LPT10", PathCodec.encodeWindowsSegment("LPT10"));
    }

    @Test
    void leavesOrdinaryNamesAlone() {
        assertEquals("report.txt", PathCodec.encodeWindowsSegment("report.txt"));
        assertEquals("照片 2026.jpg", PathCodec.encodeWindowsSegment("照片 2026.jpg"));
        assertEquals("a-b_c(1).txt", PathCodec.encodeWindowsSegment("a-b_c(1).txt"));
    }

    @Test
    void roundTripsLogicalPaths() {
        LogicalPath path = LogicalPath.of("照片 2026/con:notes?/CON/name../100%.txt");

        List<String> encoded = PathCodec.encodeWindowsSegments(path);

        assertEquals(path, PathCodec.decodeWindowsSegments(encoded));
    }

    @Test
    void decodeReversesSegmentEncoding() {
        assertEquals("a:b", PathCodec.decodeWindowsSegment("a%3Ab"));
        assertEquals("con.txt", PathCodec.decodeWindowsSegment("%63%6F%6E.txt"));
        assertEquals("name..", PathCodec.decodeWindowsSegment("name%2E%2E"));
        assertEquals("100%.txt", PathCodec.decodeWindowsSegment("100%25.txt"));
    }

    @Test
    void rejectsPathSeparatorsInSegment() {
        assertThrows(IllegalArgumentException.class, () -> PathCodec.encodeWindowsSegment("a/b"));
        assertThrows(IllegalArgumentException.class, () -> PathCodec.encodeWindowsSegment("a\\b"));
    }

    @Test
    void rejectsInvalidEscapes() {
        assertThrows(IllegalArgumentException.class, () -> PathCodec.decodeWindowsSegment("%"));
        assertThrows(IllegalArgumentException.class, () -> PathCodec.decodeWindowsSegment("%2"));
        assertThrows(IllegalArgumentException.class, () -> PathCodec.decodeWindowsSegment("%2Z"));
        assertThrows(IllegalArgumentException.class, () -> PathCodec.decodeWindowsSegment("%2F"));
        assertThrows(IllegalArgumentException.class, () -> PathCodec.decodeWindowsSegment("%5C"));
        assertThrows(IllegalArgumentException.class, () -> PathCodec.decodeWindowsSegment("%00"));
        assertThrows(IllegalArgumentException.class, () -> PathCodec.decodeWindowsSegment(""));
    }

    @Test
    void rejectsEmptySegmentList() {
        assertThrows(IllegalArgumentException.class, () -> PathCodec.decodeWindowsSegments(List.of()));
    }

    @Test
    void caseFoldKeyDetectsWindowsCollisions() {
        String first = PathCodec.windowsCaseFoldKey(LogicalPath.of("Docs/Readme.TXT"));
        String second = PathCodec.windowsCaseFoldKey(LogicalPath.of("docs/readme.txt"));

        assertEquals(first, second);
        assertNotEquals(first, PathCodec.windowsCaseFoldKey(LogicalPath.of("docs/other.txt")));
    }

    @Test
    void encodedSegmentsAreImmutable() {
        List<String> encoded = PathCodec.encodeWindowsSegments(LogicalPath.of("a/b"));

        assertThrows(UnsupportedOperationException.class, () -> encoded.add("c"));
    }
}
