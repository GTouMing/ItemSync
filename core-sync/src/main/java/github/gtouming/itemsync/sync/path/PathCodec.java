package github.gtouming.itemsync.sync.path;

import github.gtouming.itemsync.domain.identity.LogicalPath;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class PathCodec {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final String ILLEGAL = "<>:\"|?*";
    private static final Set<String> RESERVED = reservedNames();

    private PathCodec() {
    }

    public static List<String> encodeWindowsSegments(LogicalPath path) {
        Objects.requireNonNull(path, "path");
        List<String> encoded = new ArrayList<>();
        for (String segment : path.segments()) {
            encoded.add(encodeWindowsSegment(segment));
        }
        return List.copyOf(encoded);
    }

    public static LogicalPath decodeWindowsSegments(List<String> segments) {
        Objects.requireNonNull(segments, "segments");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("segments must not be empty");
        }
        List<String> decoded = new ArrayList<>();
        for (String segment : segments) {
            decoded.add(decodeWindowsSegment(segment));
        }
        return new LogicalPath(String.join("/", decoded));
    }

    public static String encodeWindowsSegment(String segment) {
        Objects.requireNonNull(segment, "segment");
        if (segment.isEmpty()) {
            throw new IllegalArgumentException("segment must not be empty");
        }
        if (segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("segment must not contain path separators: " + segment);
        }

        int trailingStart = segment.length();
        while (trailingStart > 0 && isTrailingTrimmable(segment.charAt(trailingStart - 1))) {
            trailingStart--;
        }

        int baseEnd = segment.indexOf('.');
        String base = baseEnd < 0 ? segment : segment.substring(0, baseEnd);
        boolean reserved = RESERVED.contains(base.toUpperCase(Locale.ROOT));

        StringBuilder encoded = new StringBuilder(segment.length() + 8);
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean force = (reserved && i < base.length()) || i >= trailingStart;
            if (force || needsEscape(c)) {
                appendEscape(encoded, c);
            } else {
                encoded.append(c);
            }
        }
        return encoded.toString();
    }

    public static String decodeWindowsSegment(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.isEmpty()) {
            throw new IllegalArgumentException("encoded segment must not be empty");
        }

        StringBuilder decoded = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c != '%') {
                decoded.append(c);
                continue;
            }
            if (i + 2 >= encoded.length()) {
                throw new IllegalArgumentException("truncated escape in segment: " + encoded);
            }
            int high = hexValue(encoded.charAt(i + 1));
            int low = hexValue(encoded.charAt(i + 2));
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("invalid escape in segment: " + encoded);
            }
            char decodedChar = (char) ((high << 4) | low);
            if (decodedChar == '/' || decodedChar == '\\' || decodedChar == '\0') {
                throw new IllegalArgumentException("encoded segment escapes a forbidden character: " + encoded);
            }
            decoded.append(decodedChar);
            i += 2;
        }
        return decoded.toString();
    }

    public static String windowsCaseFoldKey(LogicalPath path) {
        Objects.requireNonNull(path, "path");
        List<String> folded = new ArrayList<>();
        for (String segment : path.segments()) {
            folded.add(segment.toLowerCase(Locale.ROOT));
        }
        return String.join("/", folded);
    }

    private static boolean isTrailingTrimmable(char c) {
        return c == ' ' || c == '.';
    }

    private static boolean needsEscape(char c) {
        return c == '%' || c < 0x20 || ILLEGAL.indexOf(c) >= 0;
    }

    private static void appendEscape(StringBuilder out, char c) {
        out.append('%').append(HEX[(c >> 4) & 0x0F]).append(HEX[c & 0x0F]);
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static Set<String> reservedNames() {
        Set<String> names = new HashSet<>(Set.of("CON", "PRN", "AUX", "NUL"));
        for (int i = 1; i <= 9; i++) {
            names.add("COM" + i);
            names.add("LPT" + i);
        }
        return Set.copyOf(names);
    }
}
