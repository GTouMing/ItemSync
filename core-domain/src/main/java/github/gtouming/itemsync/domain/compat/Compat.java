package github.gtouming.itemsync.domain.compat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Compat {

    private Compat() {
    }

    @SafeVarargs
    public static <T> List<T> list(T... items) {
        Objects.requireNonNull(items, "items");
        List<T> copy = new ArrayList<>(items.length);
        for (T item : items) {
            copy.add(Objects.requireNonNull(item, "item"));
        }
        return Collections.unmodifiableList(copy);
    }

    public static <T> List<T> copyList(Collection<? extends T> items) {
        Objects.requireNonNull(items, "items");
        List<T> copy = new ArrayList<>(items.size());
        for (T item : items) {
            copy.add(Objects.requireNonNull(item, "item"));
        }
        return Collections.unmodifiableList(copy);
    }

    @SafeVarargs
    public static <T> Set<T> set(T... items) {
        Objects.requireNonNull(items, "items");
        Set<T> copy = new LinkedHashSet<>();
        for (T item : items) {
            copy.add(Objects.requireNonNull(item, "item"));
        }
        return Collections.unmodifiableSet(copy);
    }

    public static <T> Set<T> copySet(Collection<? extends T> items) {
        Objects.requireNonNull(items, "items");
        Set<T> copy = new LinkedHashSet<>();
        for (T item : items) {
            copy.add(Objects.requireNonNull(item, "item"));
        }
        return Collections.unmodifiableSet(copy);
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> map(Object... pairs) {
        Objects.requireNonNull(pairs, "pairs");
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("map requires an even number of arguments");
        }
        Map<K, V> copy = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            K key = Objects.requireNonNull((K) pairs[i], "key");
            V value = Objects.requireNonNull((V) pairs[i + 1], "value");
            if (copy.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate key: " + key);
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    public static <K, V> Map<K, V> copyMap(Map<? extends K, ? extends V> items) {
        Objects.requireNonNull(items, "items");
        Map<K, V> copy = new LinkedHashMap<>();
        items.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value")));
        return Collections.unmodifiableMap(copy);
    }

    public static Path path(String first, String... more) {
        return Paths.get(first, more);
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static String hex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            out[i * 2] = Character.forDigit(value >>> 4, 16);
            out[i * 2 + 1] = Character.forDigit(value & 0x0F, 16);
        }
        return new String(out);
    }

    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    public static String readString(Path path) throws IOException {
        return readString(path, StandardCharsets.UTF_8);
    }

    public static String readString(Path path, Charset charset) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new String(readAllBytes(in), charset);
        }
    }

    public static void writeString(Path path, String content) throws IOException {
        writeString(path, content, StandardCharsets.UTF_8);
    }

    public static void writeString(Path path, String content, Charset charset) throws IOException {
        Files.write(path, content.getBytes(charset));
    }

    public static <T> List<T> toList(Stream<T> stream) {
        return Collections.unmodifiableList(stream.collect(Collectors.toList()));
    }
}
