package github.gtouming.itemsync.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopResetTest {

    @TempDir
    Path temp;

    @Test
    void removesLocalDataButKeepsTheSyncRoot() throws Exception {
        Path data = temp.resolve("data");
        Path root = data.resolve("root");
        Files.createDirectories(root);
        Files.writeString(root.resolve("keep.txt"), "user file");
        Files.createDirectories(data.resolve("db"));
        Files.writeString(data.resolve("db").resolve("itemsync.sqlite"), "db");
        Files.writeString(data.resolve("identity.p12"), "identity");
        Files.writeString(data.resolve("trust.properties"), "trust");

        DesktopReset.reset(data, root);

        assertTrue(Files.isRegularFile(root.resolve("keep.txt")));
        assertFalse(Files.exists(data.resolve("db")));
        assertFalse(Files.exists(data.resolve("identity.p12")));
        assertFalse(Files.exists(data.resolve("trust.properties")));
    }

    @Test
    void keepsAnExternalSyncRoot() throws Exception {
        Path data = temp.resolve("data");
        Path root = temp.resolve("root");
        Files.createDirectories(data.resolve("db"));
        Files.writeString(data.resolve("db").resolve("itemsync.sqlite"), "db");
        Files.createDirectories(root);
        Files.writeString(root.resolve("keep.txt"), "user file");

        DesktopReset.reset(data, root);

        assertTrue(Files.isRegularFile(root.resolve("keep.txt")));
        assertFalse(Files.exists(data.resolve("db")));
    }

    @Test
    void refusesWhenTheSyncRootIsTheDataDirectory() {
        assertThrows(IllegalArgumentException.class, () -> DesktopReset.reset(temp, temp));
    }
}
