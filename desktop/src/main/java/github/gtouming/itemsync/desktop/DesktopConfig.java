package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.platform.desktop.PathSupport;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public record DesktopConfig(Path dataDir, Path root, GroupId group, SyncRootId rootId,
                            int port, String expectedClientFingerprint,
                            List<PeerTarget> peers, String launchCommand, Duration debounce) {

    public static final String FILE_NAME = "desktop.properties";

    public DesktopConfig {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(rootId, "rootId");
        Objects.requireNonNull(expectedClientFingerprint, "expectedClientFingerprint");
        peers = List.copyOf(Objects.requireNonNull(peers, "peers"));
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
        String expectedClient = properties.getProperty("expect", "").trim();
        if (expectedClient.isEmpty()) {
            expectedClient = "none";
        }
        List<PeerTarget> peers = parsePeers(properties.getProperty("peers", ""));
        String launchCommand = properties.getProperty("launch-command", "").trim();
        int debounceSeconds = Integer.parseInt(
                properties.getProperty("debounce-seconds", "30").trim());
        if (debounceSeconds < 0) {
            throw new IllegalArgumentException("debounce-seconds must not be negative");
        }
        return new DesktopConfig(dataDir, root, group, rootId, port, expectedClient,
                peers, launchCommand, Duration.ofSeconds(debounceSeconds));
    }

    public Set<String> expectedClientFingerprints() {
        if (expectedClientFingerprint == null || expectedClientFingerprint.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(expectedClientFingerprint.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public static DesktopConfig create(Path dataDir, Path root, GroupId group, SyncRootId rootId,
                                       int port, String expectedClientFingerprint,
                                       List<PeerTarget> peers, String launchCommand,
                                       Duration debounce) throws IOException {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(rootId, "rootId");
        Objects.requireNonNull(expectedClientFingerprint, "expectedClientFingerprint");
        Objects.requireNonNull(peers, "peers");
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
        properties.setProperty("expect", expectedClientFingerprint.isBlank()
                ? "none" : expectedClientFingerprint.trim());
        properties.setProperty("peers", peers.stream().map(PeerTarget::format)
                .collect(Collectors.joining(",")));
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

    public record PeerTarget(String host, int port, String fingerprint) {
        public static PeerTarget parse(String value) {
            int fingerprintSeparator = value.lastIndexOf(':');
            int portSeparator = value.lastIndexOf(':', fingerprintSeparator - 1);
            if (portSeparator <= 0 || fingerprintSeparator <= portSeparator + 1
                    || fingerprintSeparator == value.length() - 1) {
                throw new IllegalArgumentException("peer must be host:port:fingerprint, got: " + value);
            }
            String host = value.substring(0, portSeparator);
            int port = parsePort(value.substring(portSeparator + 1, fingerprintSeparator));
            String fingerprint = value.substring(fingerprintSeparator + 1);
            return new PeerTarget(host, port, fingerprint);
        }

        public String format() {
            return host + ":" + port + ":" + fingerprint;
        }
    }

    static List<PeerTarget> parsePeers(String raw) {
        List<PeerTarget> peers = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                peers.add(PeerTarget.parse(value));
            }
        }
        return List.copyOf(peers);
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
