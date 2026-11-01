package github.gtouming.itemsync.platform.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsAutostartTest {

    @TempDir
    Path temp;

    @Test
    void writesImportableRegistryFile() throws Exception {
        Path file = temp.resolve("autostart.reg");
        String launch = "\"C:\\Program Files\\ItemSync\\ItemSync.exe\" --data \"C:\\My Data\"";
        WindowsAutostart.writeRegFile(file, launch);

        byte[] bytes = Files.readAllBytes(file);
        assertEquals((byte) 0xFF, bytes[0]);
        assertEquals((byte) 0xFE, bytes[1]);
        String content = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        String escaped = launch.replace("\\", "\\\\").replace("\"", "\\\"");
        assertTrue(content.contains("[HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]"));
        assertTrue(content.contains("\"ItemSync\"=\"" + escaped + "\""));
    }

    @Test
    void buildsDisableAndQueryCommands() {
        assertEquals(List.of(
                "reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "ItemSync", "/f"),
                WindowsAutostart.disableCommand());

        assertEquals(List.of(
                "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "ItemSync"),
                WindowsAutostart.queryCommand());
    }
}
