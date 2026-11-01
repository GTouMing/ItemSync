package github.gtouming.itemsync.platform.desktop;

import java.nio.file.Path;
import java.util.Objects;

public final class PathSupport {

    private PathSupport() {
    }

    public static Path expandUser(String raw) {
        Objects.requireNonNull(raw, "raw");
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        String home = System.getProperty("user.home");
        if ("~".equals(value)) {
            return Path.of(home);
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Path.of(home, value.substring(2));
        }
        return Path.of(value);
    }

    public static void requireDataDirOutsideRoot(Path dataDir, Path root) {
        Path data = dataDir.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (data.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(
                    "数据目录不能位于同步根内：" + data + " 在 " + normalizedRoot + " 之下；"
                            + "请把 root 指向数据目录之外的独立目录（例如 "
                            + data.resolve("root") + "）");
        }
    }
}
