package github.gtouming.itemsync.platform.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathSupportTest {

    @Test
    void expandsHomeDirectory() {
        Path home = Path.of(System.getProperty("user.home"));

        assertEquals(home, PathSupport.expandUser("~"));
        assertEquals(home.resolve("Desktop").resolve("tt"), PathSupport.expandUser("~/Desktop/tt"));
        assertEquals(home.resolve("Desktop").resolve("tt"), PathSupport.expandUser("~\\Desktop\\tt"));
    }

    @Test
    void keepsOtherPaths() {
        assertEquals(Path.of("C:/data"), PathSupport.expandUser("C:/data"));
    }

    @Test
    void rejectsBlankPaths() {
        assertThrows(IllegalArgumentException.class, () -> PathSupport.expandUser("  "));
    }

    @Test
    void rejectsDataDirectoryInsideRoot() {
        Path root = Path.of("C:/sync-root");

        assertThrows(IllegalArgumentException.class,
                () -> PathSupport.requireDataDirOutsideRoot(root.resolve("data"), root));
        assertThrows(IllegalArgumentException.class,
                () -> PathSupport.requireDataDirOutsideRoot(root, root));
    }

    @Test
    void allowsRootInsideDataDirectory() {
        Path data = Path.of("C:/data");

        PathSupport.requireDataDirOutsideRoot(data, data.resolve("root"));
    }
}
