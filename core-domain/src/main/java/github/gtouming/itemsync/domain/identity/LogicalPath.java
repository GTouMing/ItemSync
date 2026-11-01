package github.gtouming.itemsync.domain.identity;

import github.gtouming.itemsync.domain.compat.Compat;

import java.util.List;
import java.util.Objects;

public record LogicalPath(String value) implements Comparable<LogicalPath> {

    public static final int MAX_LENGTH = 4096;

    public LogicalPath {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("logical path must not be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "logical path exceeds " + MAX_LENGTH + " characters: " + value.length());
        }
        if (value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("logical path must use '/' as separator: " + value);
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("logical path must not contain NUL");
        }
        if (value.startsWith("/") || value.endsWith("/")) {
            throw new IllegalArgumentException("logical path must not start or end with '/': " + value);
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("logical path must not contain empty segments: " + value);
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("logical path must not contain '.' or '..': " + value);
            }
        }
    }

    public static LogicalPath of(String value) {
        return new LogicalPath(value);
    }

    public List<String> segments() {
        return Compat.list(value.split("/", -1));
    }

    public String name() {
        int separator = value.lastIndexOf('/');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    @Override
    public int compareTo(LogicalPath other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
