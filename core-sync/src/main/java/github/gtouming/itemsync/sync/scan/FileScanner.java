package github.gtouming.itemsync.sync.scan;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.sync.ignore.IgnoreRules;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FileScanner {

    private static final int BUFFER_SIZE = 8192;

    public List<ScannedEntry> scan(Path root, IgnoreRules rules) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(rules, "rules");
        Path absoluteRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absoluteRoot)) {
            throw new IOException("sync root is not a directory: " + absoluteRoot);
        }

        List<ScannedEntry> entries = new ArrayList<>();
        Files.walkFileTree(absoluteRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (directory.equals(absoluteRoot)) {
                    return FileVisitResult.CONTINUE;
                }
                LogicalPath logical = toLogicalPath(absoluteRoot, directory);
                if (rules.isIgnored(logical, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                entries.add(new ScannedEntry(logical, ItemKind.DIRECTORY, null, 0,
                        attributes.lastModifiedTime().toInstant()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    return FileVisitResult.CONTINUE;
                }
                LogicalPath logical = toLogicalPath(absoluteRoot, file);
                if (rules.isIgnored(logical, false)) {
                    return FileVisitResult.CONTINUE;
                }
                entries.add(new ScannedEntry(logical, ItemKind.FILE, hash(file), attributes.size(),
                        attributes.lastModifiedTime().toInstant()));
                return FileVisitResult.CONTINUE;
            }
        });
        return List.copyOf(entries);
    }

    private static LogicalPath toLogicalPath(Path root, Path path) {
        String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
        return LogicalPath.of(relative);
    }

    private static BlobHash hash(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return BlobHash.ofSha256(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
