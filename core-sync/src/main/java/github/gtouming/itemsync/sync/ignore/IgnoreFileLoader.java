package github.gtouming.itemsync.sync.ignore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class IgnoreFileLoader {

    private IgnoreFileLoader() {
    }

    public static IgnoreRules load(Path syncRoot) throws IOException {
        Objects.requireNonNull(syncRoot, "syncRoot");
        Path ignoreFile = syncRoot.resolve(IgnoreRules.FILE_NAME);
        if (!Files.isRegularFile(ignoreFile)) {
            return IgnoreRules.empty();
        }
        return IgnoreRules.parse(Files.readAllLines(ignoreFile, StandardCharsets.UTF_8));
    }
}
