package github.gtouming.itemsync.sync.apply;

import github.gtouming.itemsync.domain.identity.LogicalPath;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathResolversTest {

    @Test
    void windowsResolverEncodesIllegalSegments() {
        Path resolved = PathResolvers.windows()
                .resolve(Path.of("C:/root"), LogicalPath.of("a:b/c?d.txt"));

        assertEquals(Path.of("C:/root/a%3Ab/c%3Fd.txt"), resolved);
    }

    @Test
    void posixResolverKeepsSegments() {
        Path resolved = PathResolvers.posix()
                .resolve(Path.of("/root"), LogicalPath.of("照片 2026/c.txt"));

        assertEquals(Path.of("/root/照片 2026/c.txt"), resolved);
    }
}
