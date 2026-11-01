package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.platform.desktop.PathSupport;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

public record DesktopConfig(Path dataDir, Path root, GroupId group, SyncRootId rootId,
                            int port, String expectedClientFingerprint,
                            List<PeerTarget> peers, String launchCommand, Duration debounce) {

    public static final String FILE_NAME = "desktop.properties";

    private static final String TEMPLATE = """
            # ItemSync 桌面配置
            # 同步根目录（支持 ~ 与正斜杠）
            # 不能与数据目录相同或位于其内；建议放在数据目录之外的独立目录
            root=~/ItemSyncRoot
            # 组 ID：8 位 [0-9a-z]
            group=group001
            # 同步根 ID：两端必须一致
            root-id=root-1
            # 监听端口
            port=47800
            # 对端证书指纹（用于双向认证），对端启动后从界面或 itemsync-cli fingerprint 获取
            # 留空则暂不接受邻居连接
            expect=
            # 对端列表，多个用逗号分隔: host:port:fingerprint
            peers=
            # 开机自启命令（打包后的 exe 路径），留空则禁用
            launch-command=
            # 条件触发防抖秒数
            debounce-seconds=30
            """;

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

    public static Path writeTemplate(Path dataDir) throws IOException {
        Path file = dataDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            Files.writeString(file, TEMPLATE, StandardCharsets.UTF_8);
        }
        return file;
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
    }

    private static List<PeerTarget> parsePeers(String raw) {
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
