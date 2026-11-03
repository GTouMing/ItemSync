package github.gtouming.itemsync.platform.desktop;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.SyncRootId;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;

public record DesktopConfig(Path dataDir, Path root, GroupId group, SyncRootId rootId,
                            int port, String launchCommand, Duration debounce) {

    public static final String FILE_NAME = "desktop.properties";

    public DesktopConfig {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(rootId, "rootId");
        Objects.requireNonNull(launchCommand, "launchCommand");
        Objects.requireNonNull(debounce, "debounce");
    }

    public static DesktopConfig load(Path dataDir) throws IOException {
        Path file = dataDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("missing config: " + file);
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        Path root = PathSupport.expandUser(required(properties, "root")).toAbsolutePath();
        PathSupport.requireDataDirOutsideRoot(dataDir, root);
        GroupId group = GroupId.parse(required(properties, "group"));
        SyncRootId rootId = SyncRootId.of(properties.getProperty("root-id", "root-1").trim());
        int port = parsePort(properties.getProperty("port", "47800").trim());
        String launchCommand = properties.getProperty("launch-command", "").trim();
        int debounceSeconds = Integer.parseInt(
                properties.getProperty("debounce-seconds", "30").trim());
        if (debounceSeconds < 0) {
            throw new IllegalArgumentException("debounce-seconds must not be negative");
        }
        // Legacy "expect"/"peers" keys are intentionally ignored; trust is only granted
        // through the device list after pairing.
        return new DesktopConfig(dataDir, root, group, rootId, port, launchCommand,
                Duration.ofSeconds(debounceSeconds));
    }

    public static DesktopConfig changeGroup(Path dataDir, GroupId group) throws IOException {
        DesktopConfig current = load(dataDir);
        return create(dataDir, current.root, group, current.rootId, current.port,
                current.launchCommand, current.debounce);
    }

    public static DesktopConfig create(Path dataDir, Path root, GroupId group, SyncRootId rootId,
                                       int port, String launchCommand, Duration debounce)
            throws IOException {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(rootId, "rootId");
        Objects.requireNonNull(debounce, "debounce");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (debounce.isNegative()) {
            throw new IllegalArgumentException("debounce must not be negative");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        PathSupport.requireDataDirOutsideRoot(dataDir, normalizedRoot);

        Properties properties = new Properties();
        properties.setProperty("root", normalizedRoot.toString());
        properties.setProperty("group", group.value());
        properties.setProperty("root-id", rootId.value());
        properties.setProperty("port", Integer.toString(port));
        properties.setProperty("launch-command", launchCommand == null ? "" : launchCommand.trim());
        properties.setProperty("debounce-seconds", Long.toString(debounce.toSeconds()));

        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(FILE_NAME);
        Path tmp = Files.createTempFile(dataDir, ".desktop", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                properties.store(writer, "ItemSync desktop configuration");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return load(dataDir);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing config key: " + key);
        }
        return value.trim();
    }

    private static int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw);
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port: " + raw, e);
        }
    }
}
