package github.gtouming.itemsync.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AutostartCommandTest {

    @Test
    void prefersConfiguredCommand() {
        assertEquals("C:\\Apps\\ItemSync.exe --data C:\\data",
                AutostartCommand.resolve("C:\\Apps\\ItemSync.exe --data C:\\data",
                        "C:\\Apps\\ItemSync.exe", Path.of("C:/data")));
    }

    @Test
    void buildsCommandFromExecutableAndDataDirectory() {
        String dataDir = Path.of("C:/Data Files/tt").toAbsolutePath().toString();

        assertEquals("\"C:\\Program Files\\ItemSync\\ItemSync.exe\" --data \"" + dataDir + "\"",
                AutostartCommand.resolve("", "C:\\Program Files\\ItemSync\\ItemSync.exe",
                        Path.of("C:/Data Files/tt")));
    }

    @Test
    void rejectsJavaLaunchers() {
        assertNull(AutostartCommand.resolve(null, "C:\\Java\\bin\\java.exe", Path.of("C:/data")));
        assertNull(AutostartCommand.resolve(null, "C:\\Java\\bin\\javaw.exe", Path.of("C:/data")));
    }

    @Test
    void rejectsNonExecutablePaths() {
        assertNull(AutostartCommand.resolve(null, "C:\\ItemSync\\bin\\ItemSync.bat", Path.of("C:/data")));
    }
}
