package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        properties.setProperty("expect", "peer-fingerprint");
        properties.setProperty("peers", "127.0.0.1:47801:fp-b,127.0.0.1:47802:fp-c");
        properties.setProperty("launch-command", "C:\\Apps\\ItemSync.exe");
        properties.setProperty("debounce-seconds", "5");
        try (Writer writer = Files.newBufferedWriter(temp.resolve(DesktopConfig.FILE_NAME))) {
            properties.store(writer, null);
        }

        DesktopConfig config = DesktopConfig.load(temp);

        assertEquals(root.toAbsolutePath(), config.root());
        assertEquals("group001", config.group().value());
        assertEquals("root-1", config.rootId().value());
        assertEquals(47800, config.port());
        assertEquals("peer-fingerprint", config.expectedClientFingerprint());
        assertEquals(Set.of("peer-fingerprint"), config.expectedClientFingerprints());
        assertEquals(2, config.peers().size());
        assertEquals("127.0.0.1", config.peers().get(0).host());
        assertEquals(47801, config.peers().get(0).port());
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
                SyncRootId.of("root-1"), 47900, "peer-fp",
                List.of(DesktopConfig.PeerTarget.parse("127.0.0.1:47801:peer-fp")),
                "", Duration.ofSeconds(10));

        assertEquals(root.toAbsolutePath(), config.root());
        assertEquals(47900, config.port());
        assertEquals("peer-fp", config.expectedClientFingerprint());
        assertEquals(1, config.peers().size());
        assertEquals("127.0.0.1", config.peers().get(0).host());
        assertEquals(47801, config.peers().get(0).port());
        assertEquals("peer-fp", config.peers().get(0).fingerprint());
        assertEquals(10, config.debounce().toSeconds());
    }

    @Test
    void createRejectsDataDirectoryInsideRoot() {
        assertThrows(IllegalArgumentException.class, () -> DesktopConfig.create(temp, temp,
                GroupId.random(), SyncRootId.of("root-1"), 47800, "none", List.of(), "",
                Duration.ofSeconds(30)));
    }

    @Test
    void changingGroupClearsLegacyPeers() throws Exception {
        Path root = temp.resolve("root");
        DesktopConfig created = DesktopConfig.create(temp, root, GroupId.parse("group001"),
                SyncRootId.of("root-1"), 47900, "peer-fp",
                List.of(DesktopConfig.PeerTarget.parse("127.0.0.1:47801:peer-fp")),
                "", Duration.ofSeconds(10));

        DesktopConfig changed = DesktopConfig.changeGroup(temp, GroupId.parse("group002"));

        assertEquals("group002", changed.group().value());
        assertEquals("none", changed.expectedClientFingerprint());
        assertTrue(changed.peers().isEmpty());
        assertEquals(created.root(), changed.root());
        assertEquals(47900, changed.port());
    }

    @Test
    void parsesMultipleExpectedClientFingerprints() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("root", temp.resolve("root").toString());
        properties.setProperty("group", "group001");
        properties.setProperty("expect", "fp-a, fp-b");
        try (Writer writer = Files.newBufferedWriter(temp.resolve(DesktopConfig.FILE_NAME))) {
            properties.store(writer, null);
        }

        DesktopConfig config = DesktopConfig.load(temp);

        assertEquals(Set.of("fp-a", "fp-b"), config.expectedClientFingerprints());
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
