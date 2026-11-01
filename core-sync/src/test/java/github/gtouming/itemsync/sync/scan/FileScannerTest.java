package github.gtouming.itemsync.sync.scan;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.sync.ignore.IgnoreFileLoader;
import github.gtouming.itemsync.sync.ignore.IgnoreRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileScannerTest {

    @TempDir
    Path root;

    @Test
    void scansFilesAndDirectories() throws Exception {
        Files.writeString(root.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("sub/empty"));
        Files.writeString(root.resolve("sub/b.txt"), "world", StandardCharsets.UTF_8);

        Map<String, ScannedEntry> entries = byPath(new FileScanner().scan(root, IgnoreRules.empty()));

        assertEquals(Set.of("a.txt", "sub", "sub/empty", "sub/b.txt"), entries.keySet());
        assertEquals(ItemKind.FILE, entries.get("a.txt").kind());
        assertEquals(sha256("hello"), entries.get("a.txt").hash());
        assertEquals(5, entries.get("a.txt").size());
        assertEquals(ItemKind.DIRECTORY, entries.get("sub").kind());
        assertEquals(0, entries.get("sub").size());
    }

    @Test
    void appliesIgnoreRulesAndSkipsIgnoredSubtrees() throws Exception {
        Files.writeString(root.resolve(".isignore"), "*.log\nbuild/\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("keep.txt"), "keep", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("drop.log"), "drop", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("build"));
        Files.writeString(root.resolve("build/out.bin"), "x", StandardCharsets.UTF_8);

        IgnoreRules rules = IgnoreFileLoader.load(root);
        Map<String, ScannedEntry> entries = byPath(new FileScanner().scan(root, rules));

        assertTrue(entries.containsKey("keep.txt"));
        assertTrue(entries.containsKey(".isignore"));
        assertFalse(entries.containsKey("drop.log"));
        assertFalse(entries.containsKey("build"));
        assertFalse(entries.containsKey("build/out.bin"));
    }

    @Test
    void scansUnicodeAndSpaceInNames() throws Exception {
        Files.writeString(root.resolve("照片 2026.txt"), "x", StandardCharsets.UTF_8);

        Map<String, ScannedEntry> entries = byPath(new FileScanner().scan(root, IgnoreRules.empty()));

        assertTrue(entries.containsKey("照片 2026.txt"));
    }

    private static Map<String, ScannedEntry> byPath(List<ScannedEntry> entries) {
        return entries.stream().collect(Collectors.toMap(
                entry -> entry.path().value(),
                entry -> entry));
    }

    private static BlobHash sha256(String content) {
        try {
            return BlobHash.ofSha256(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
