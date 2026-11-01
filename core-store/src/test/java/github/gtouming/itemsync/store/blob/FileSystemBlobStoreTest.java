package github.gtouming.itemsync.store.blob;

import github.gtouming.itemsync.domain.identity.BlobHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemBlobStoreTest {

    @TempDir
    Path root;

    @Test
    void storesContentUnderHashPrefix() throws IOException {
        FileSystemBlobStore store = new FileSystemBlobStore(root);
        byte[] content = "hello blob".getBytes(StandardCharsets.UTF_8);

        BlobHash hash = store.put(new ByteArrayInputStream(content));

        assertEquals(sha256(content), hash);
        assertTrue(store.contains(hash));
        assertEquals(content.length, store.size(hash));
        assertArrayEquals(content, store.open(hash).readAllBytes());
        Path expected = root.resolve("blobs")
                .resolve(hash.value().substring(0, 2))
                .resolve(hash.value());
        assertTrue(Files.isRegularFile(expected));
    }

    @Test
    void storesEmptyContent() throws IOException {
        FileSystemBlobStore store = new FileSystemBlobStore(root);

        BlobHash hash = store.put(new ByteArrayInputStream(new byte[0]));

        assertEquals(BlobHash.parse("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), hash);
        assertEquals(0, store.size(hash));
    }

    @Test
    void deduplicatesIdenticalContent() throws IOException {
        FileSystemBlobStore store = new FileSystemBlobStore(root);
        byte[] content = "same".getBytes(StandardCharsets.UTF_8);

        BlobHash first = store.put(new ByteArrayInputStream(content));
        BlobHash second = store.put(new ByteArrayInputStream(content));

        assertEquals(first, second);
        try (Stream<Path> files = Files.walk(root.resolve("blobs"))) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void reportsMissingBlob() throws IOException {
        FileSystemBlobStore store = new FileSystemBlobStore(root);
        BlobHash missing = BlobHash.parse("0".repeat(64));

        assertFalse(store.contains(missing));
        assertThrows(NoSuchFileException.class, () -> store.open(missing));
        assertThrows(NoSuchFileException.class, () -> store.size(missing));
    }

    @Test
    void deleteRemovesBlob() throws IOException {
        FileSystemBlobStore store = new FileSystemBlobStore(root);
        BlobHash hash = store.put(new ByteArrayInputStream("bye".getBytes(StandardCharsets.UTF_8)));

        store.delete(hash);

        assertFalse(store.contains(hash));
    }

    @Test
    void failingStreamLeavesNoTempFile() throws IOException {
        FileSystemBlobStore store = new FileSystemBlobStore(root);
        InputStream failing = new InputStream() {
            private int remaining = 3;

            @Override
            public int read() throws IOException {
                if (remaining-- > 0) {
                    return 'a';
                }
                throw new IOException("boom");
            }
        };

        assertThrows(IOException.class, () -> store.put(failing));
        try (Stream<Path> tmp = Files.list(root.resolve("tmp"))) {
            assertEquals(0, tmp.count());
        }
        try (Stream<Path> blobs = Files.walk(root.resolve("blobs"))) {
            assertEquals(0, blobs.filter(Files::isRegularFile).count());
        }
    }

    private static BlobHash sha256(byte[] content) {
        try {
            return BlobHash.ofSha256(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
