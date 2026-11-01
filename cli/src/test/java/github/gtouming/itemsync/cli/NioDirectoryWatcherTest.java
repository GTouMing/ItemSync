package github.gtouming.itemsync.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NioDirectoryWatcherTest {

    @TempDir
    Path temp;

    @Test
    void reportsCreatedFiles() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        try (NioDirectoryWatcher watcher = new NioDirectoryWatcher(temp, path -> latch.countDown())) {
            Files.writeString(temp.resolve("a.txt"), "x", StandardCharsets.UTF_8);
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        }
    }
}
