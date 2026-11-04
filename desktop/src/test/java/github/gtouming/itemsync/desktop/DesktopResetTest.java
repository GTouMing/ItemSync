package github.gtouming.itemsync.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @Test
    void keepsEverySyncRootOfAMultiGroupDevice() throws Exception {
        Path data = temp.resolve("data");
        Path firstRoot = data.resolve("root-1");
        Path secondRoot = data.resolve("root-2");
        Files.createDirectories(firstRoot);
        Files.createDirectories(secondRoot);
        Files.writeString(firstRoot.resolve("keep.txt"), "group one file");
        Files.writeString(secondRoot.resolve("keep.txt"), "group two file");
        Files.createDirectories(data.resolve("db"));
        Files.writeString(data.resolve("db").resolve("itemsync.sqlite"), "db");

        DesktopReset.reset(data, List.of(firstRoot, secondRoot));

        assertTrue(Files.isRegularFile(firstRoot.resolve("keep.txt")));
        assertTrue(Files.isRegularFile(secondRoot.resolve("keep.txt")));
        assertFalse(Files.exists(data.resolve("db")));
    }
}
