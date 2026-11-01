package github.gtouming.itemsync.platform.desktop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsAutostartTest {

    @Test
    void buildsEnableCommand() {
        List<String> command = WindowsAutostart.enableCommand("C:\\Apps\\ItemSync.exe");

        assertEquals(List.of(
                "reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "ItemSync", "/t", "REG_SZ", "/d", "C:\\Apps\\ItemSync.exe", "/f"),
                command);
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
