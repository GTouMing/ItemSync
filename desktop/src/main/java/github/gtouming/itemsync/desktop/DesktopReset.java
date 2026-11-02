package github.gtouming.itemsync.desktop;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

final class DesktopReset {

    private DesktopReset() {
    }

    static void reset(Path dataDir, Path syncRoot) throws IOException {
        Path data = real(dataDir);
        Path root = real(syncRoot);
        if (root.equals(data)) {
            throw new IllegalArgumentException("同步根与数据目录相同，无法安全清除本机数据");
        }
        if (!Files.isDirectory(data)) {
            return;
        }

        Files.walkFileTree(data, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.startsWith(root)) {
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                if (dir.equals(data)) {
                    return FileVisitResult.CONTINUE;
                }
                if (dir.startsWith(root) || root.startsWith(dir)) {
                    return FileVisitResult.CONTINUE;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Path real(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        return Files.exists(normalized) ? normalized.toRealPath() : normalized;
    }
}
