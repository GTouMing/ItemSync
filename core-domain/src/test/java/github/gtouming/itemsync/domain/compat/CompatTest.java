package github.gtouming.itemsync.domain.compat;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatTest {

    @Test
    void createsUnmodifiableCollections() {
        List<String> list = Compat.list("a", "b");
        Set<String> set = Compat.set("a", "b");
        Map<String, Integer> map = Compat.map("a", 1, "b", 2);

        assertEquals(List.of("a", "b"), list);
        assertEquals(Set.of("a", "b"), set);
        assertEquals(Map.of("a", 1, "b", 2), map);
        assertThrows(UnsupportedOperationException.class, () -> list.add("c"));
        assertThrows(UnsupportedOperationException.class, () -> set.add("c"));
        assertThrows(UnsupportedOperationException.class, () -> map.put("c", 3));
        assertThrows(IllegalArgumentException.class, () -> Compat.map("a", 1, "a", 2));
    }

    @Test
    void copiesCollections() {
        assertEquals(List.of("a", "b"), Compat.copyList(List.of("a", "b")));
        assertEquals(Set.of("a", "b"), Compat.copySet(List.of("a", "b")));
        assertEquals(Map.of("a", 1), Compat.copyMap(Map.of("a", 1)));
    }

    @Test
    void handlesPathsAndText() {
        assertEquals(Path.of("a", "b"), Compat.path("a", "b"));
        assertTrue(Compat.isBlank("  "));
        assertEquals("68656c6c6f", Compat.hex("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsAndCopiesStreams() throws Exception {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        assertEquals("data", new String(Compat.readAllBytes(
                new ByteArrayInputStream(content)), StandardCharsets.UTF_8));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Compat.copy(new ByteArrayInputStream(content), out);
        assertEquals("data", new String(out.toByteArray(), StandardCharsets.UTF_8));
        assertEquals("68656c6c6f", Compat.hex("hello".getBytes(StandardCharsets.UTF_8)));
    }
}
