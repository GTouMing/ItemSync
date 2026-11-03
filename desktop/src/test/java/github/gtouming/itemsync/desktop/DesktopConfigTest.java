package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.platform.desktop.DesktopConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopConfigTest {

    @TempDir
    Path temp;

    @Test
    void loadsConfiguration() throws Exception {
        Path root = temp.resolve("root");
        Files.createDirectories(root);
        Properties properties = new Properties();
        properties.setProperty("root", root.toString());
        properties.setProperty("group", "group001");
        properties.setProperty("root-id", "root-1");
        properties.setProperty("port", "47800");
        properties.setProperty("launch-command", "C:\\Apps\\ItemSync.exe");
        properties.setProperty("debounce-seconds", "5");
        try (Writer writer = Files.newBufferedWriter(temp.resolve(DesktopConfig.FILE_NAME))) {
            properties.store(writer, null);
        }

        DesktopConfig config = DesktopConfig.load(temp);

        assertEquals(root.toAbsolutePath(), config.root());
        assertEquals("group001", config.group().value());
        assertEquals(47800, config.port());
        assertEquals("C:\\Apps\\ItemSync.exe", config.launchCommand());
        assertEquals(5, config.debounce().toSeconds());
    }

    @Test
    void rejectsMissingRequiredKeys() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("group", "group001");
        try (Writer writer = Files.newBufferedWriter(temp.resolve(DesktopConfig.FILE_NAME))) {
            properties.store(writer, null);
        }

        assertThrows(IllegalArgumentException.class, () -> DesktopConfig.load(temp));
    }

    @Test
    void createsAndReloadsConfiguration() throws Exception {
        Path root = temp.resolve("root");
        DesktopConfig config = DesktopConfig.create(temp, root, GroupId.random(),
                47900, "C:\\Apps\\ItemSync.exe", Duration.ofSeconds(10));

        assertEquals(root.toAbsolutePath(), config.root());
        assertEquals(47900, config.port());
        assertEquals("C:\\Apps\\ItemSync.exe", config.launchCommand());
        assertEquals(10, config.debounce().toSeconds());
    }

    @Test
    void createRejectsDataDirectoryInsideRoot() {
        assertThrows(IllegalArgumentException.class, () -> DesktopConfig.create(temp, temp,
                GroupId.random(), 47800, "", Duration.ofSeconds(30)));
    }

    @Test
    void changingGroupPreservesRootPortAndLaunchCommand() throws Exception {
        Path root = temp.resolve("root");
        DesktopConfig created = DesktopConfig.create(temp, root, GroupId.parse("group001"),
                47900, "C:\\Apps\\ItemSync.exe", Duration.ofSeconds(10));

        DesktopConfig changed = DesktopConfig.changeGroup(temp, GroupId.parse("group002"));

        assertEquals("group002", changed.group().value());
        assertEquals(created.root(), changed.root());
        assertEquals(47900, changed.port());
        assertEquals("C:\\Apps\\ItemSync.exe", changed.launchCommand());
        assertEquals(10, changed.debounce().toSeconds());
    }

    @Test
    void legacyPeerKeysAreIgnored() throws Exception {
        Path root = temp.resolve("root");
        Files.createDirectories(root);
        Properties properties = new Properties();
        properties.setProperty("root", root.toString());
        properties.setProperty("group", "group001");
        properties.setProperty("root-id", "root-1");
        properties.setProperty("port", "47800");
        properties.setProperty("expect", "peer-fingerprint");
        properties.setProperty("peers", "127.0.0.1:47801:fp-b,127.0.0.1:47802:fp-c");
        try (Writer writer = Files.newBufferedWriter(temp.resolve(DesktopConfig.FILE_NAME))) {
            properties.store(writer, null);
        }

        DesktopConfig config = DesktopConfig.load(temp);

        assertEquals("group001", config.group().value());
        assertEquals(47800, config.port());
        assertEquals(root.toAbsolutePath(), config.root());
    }

    @Test
    void createDoesNotWriteLegacyKeys() throws Exception {
        DesktopConfig.create(temp, temp.resolve("root"), GroupId.random(),
                47900, "", Duration.ofSeconds(10));

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(temp.resolve(DesktopConfig.FILE_NAME))) {
            properties.load(reader);
        }

        assertFalse(properties.containsKey("expect"));
        assertFalse(properties.containsKey("peers"));
    }

    @Test
    void rejectsRootContainingDataDirectory() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("root", temp.toString());
        properties.setProperty("group", "group001");
        try (Writer writer = Files.newBufferedWriter(temp.resolve(DesktopConfig.FILE_NAME))) {
            properties.store(writer, null);
        }

        assertThrows(IllegalArgumentException.class, () -> DesktopConfig.load(temp));
    }
}
