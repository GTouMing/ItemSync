package github.gtouming.itemsync.cli;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.platform.desktop.DesktopConfig;
import github.gtouming.itemsync.sync.trust.FileTrustStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliAdminDesktopTest {

    @TempDir
    Path temp;

    @Test
    void readsUpdatesAndClearsDesktopData() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        Path root = Files.createDirectories(temp.resolve("root"));
        DesktopConfig.create(data, root, GroupId.parse("group001"),
                47999, "", Duration.ofSeconds(30));
        FileTrustStore trust = new FileTrustStore(data);
        String fingerprint = "a".repeat(64);
        trust.trust(GroupId.parse("group001"), fingerprint);

        String json = run("config", "get", "--data", data.toString(), "--json");
        assertTrue(json.contains("\"group\":\"group001\""));
        assertTrue(json.contains("\"trustCount\":1"));

        trust.clear();
        run("trust", "add", fingerprint, "--data", data.toString());
        assertEquals(1, new FileTrustStore(data)
                .fingerprints(GroupId.parse("group001")).size());

        run("config", "set", "group", "group002", "--data", data.toString(), "--yes");
        assertEquals("group002", DesktopConfig.load(data).group().value());
        assertTrue(new FileTrustStore(data)
                .fingerprints(GroupId.parse("group002")).isEmpty());

        CliAdmin.AdminException failure = assertThrows(CliAdmin.AdminException.class,
                () -> ItemSyncCli.run(new String[] {"config", "set", "root",
                        temp.resolve("other").toString(), "--data", data.toString()}));
        assertEquals(2, failure.code());
    }

    private static String run(String... args) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        try {
            ItemSyncCli.run(args);
        } finally {
            System.setOut(original);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
