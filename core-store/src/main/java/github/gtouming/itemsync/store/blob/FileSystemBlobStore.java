package github.gtouming.itemsync.store.blob;

import github.gtouming.itemsync.domain.identity.BlobHash;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

public final class FileSystemBlobStore implements BlobStore {

    private static final int BUFFER_SIZE = 8192;
    private static final String HASH_ALGORITHM = "SHA-256";

    private final Path blobsDir;
    private final Path tmpDir;

    public FileSystemBlobStore(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        this.blobsDir = root.resolve("blobs");
        this.tmpDir = root.resolve("tmp");
        Files.createDirectories(blobsDir);
        Files.createDirectories(tmpDir);
    }

    @Override
    public BlobHash put(InputStream content) throws IOException {
        Objects.requireNonNull(content, "content");
        Path temp = tmpDir.resolve("blob-" + UUID.randomUUID());
        try {
            BlobHash hash = writeAndHash(content, temp);
            Path target = pathOf(hash);
            Files.createDirectories(target.getParent());
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException alreadyStored) {
                // identical content is already stored
            }
            return hash;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public InputStream open(BlobHash hash) throws IOException {
        return Files.newInputStream(pathOf(Objects.requireNonNull(hash, "hash")));
    }

    @Override
    public boolean contains(BlobHash hash) {
        return Files.isRegularFile(pathOf(Objects.requireNonNull(hash, "hash")));
    }

    @Override
    public long size(BlobHash hash) throws IOException {
        return Files.size(pathOf(Objects.requireNonNull(hash, "hash")));
    }

    @Override
    public void delete(BlobHash hash) throws IOException {
        Files.deleteIfExists(pathOf(Objects.requireNonNull(hash, "hash")));
    }

    private Path pathOf(BlobHash hash) {
        String value = hash.value();
        return blobsDir.resolve(value.substring(0, 2)).resolve(value);
    }

    private static BlobHash writeAndHash(InputStream content, Path temp) throws IOException {
        MessageDigest digest = sha256();
        try (OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = content.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        return BlobHash.ofSha256(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " is not available", e);
        }
    }
}
