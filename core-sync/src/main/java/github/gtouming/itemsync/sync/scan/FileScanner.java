package github.gtouming.itemsync.sync.scan;

import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.sync.fs.FileHashing;
import github.gtouming.itemsync.sync.ignore.IgnoreRules;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FileScanner {

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
                entries.add(new ScannedEntry(logical, ItemKind.FILE, FileHashing.sha256(file),
                        attributes.size(), attributes.lastModifiedTime().toInstant()));
                return FileVisitResult.CONTINUE;
            }
        });
        return List.copyOf(entries);
    }

    private static LogicalPath toLogicalPath(Path root, Path path) {
        String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
        return LogicalPath.of(relative);
    }
}
