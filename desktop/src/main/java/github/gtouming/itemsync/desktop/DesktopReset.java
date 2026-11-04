package github.gtouming.itemsync.desktop;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

final class DesktopReset {

    private DesktopReset() {
    }

    static void reset(Path dataDir, Path syncRoot) throws IOException {
        reset(dataDir, syncRoot == null ? List.of() : List.of(syncRoot));
    }

    /**
     * Deletes the local data of this installation but keeps every configured sync root, so a
     * device that serves several groups never loses user files of another group.
     */
    static void reset(Path dataDir, List<Path> syncRoots) throws IOException {
        Path data = real(dataDir);
        List<Path> roots = new ArrayList<>(syncRoots.size());
        for (Path syncRoot : syncRoots) {
            Path root = real(syncRoot);
            if (root.equals(data)) {
                throw new IllegalArgumentException("同步根与数据目录相同，无法安全清除本机数据");
            }
            roots.add(root);
        }
        if (!Files.isDirectory(data)) {
            return;
        }

        Files.walkFileTree(data, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!insideAny(file, roots)) {
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
                if (insideAny(dir, roots) || containsAny(dir, roots)) {
                    return FileVisitResult.CONTINUE;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean insideAny(Path path, List<Path> roots) {
        for (Path root : roots) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(Path dir, List<Path> roots) {
        for (Path root : roots) {
            if (root.startsWith(dir)) {
                return true;
            }
        }
        return false;
    }

    private static Path real(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        return Files.exists(normalized) ? normalized.toRealPath() : normalized;
    }
}
