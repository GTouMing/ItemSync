package github.gtouming.itemsync.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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
    void writesTemplateWhenMissing() throws Exception {
        Path file = DesktopConfig.writeTemplate(temp);

        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.readString(file).contains("root="));
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
