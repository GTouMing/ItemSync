package github.gtouming.itemsync.cli;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import github.gtouming.itemsync.sync.apply.PhysicalPathResolver;
import github.gtouming.itemsync.sync.engine.SyncRunResult;
import github.gtouming.itemsync.sync.engine.node.SyncNode;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ItemSyncCli {

    private ItemSyncCli() {
    }

    public static void main(String[] args) {
        try {
            CliArguments arguments = CliArguments.parse(args);
            switch (arguments.command()) {
                case "init" -> init(arguments);
                case "fingerprint" -> fingerprint(arguments);
                case "serve" -> serve(arguments);
                case "sync" -> sync(arguments);
                case "help", "--help" -> usage();
                default -> throw new IllegalArgumentException("unknown command: " + arguments.command());
            }
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void init(CliArguments arguments) throws IOException {
        Path data = dataDirectory(arguments);
        Path identityFile = data.resolve("identity.p12");
        if (Files.exists(identityFile)) {
            throw new IllegalStateException("identity already exists: " + identityFile);
        }
        char[] password = password(arguments);
        TlsIdentity identity = TlsIdentity.generate("itemsync", password);
        identity.save(identityFile);
        Files.writeString(data.resolve("node-id"), UUID.randomUUID().toString());
        Files.writeString(data.resolve("device-id"), UUID.randomUUID().toString());
        System.out.println("initialized: " + data);
        System.out.println("fingerprint: " + identity.fingerprint());
    }

    private static void fingerprint(CliArguments arguments) throws IOException {
        Path data = dataDirectory(arguments);
        TlsIdentity identity = TlsIdentity.load(identityFile(data), password(arguments));
        System.out.println(identity.fingerprint());
    }

    private static void serve(CliArguments arguments) throws Exception {
        Path data = dataDirectory(arguments);
        char[] password = password(arguments);
        TlsIdentity identity = TlsIdentity.load(identityFile(data), password);
        NodeId nodeId = NodeId.of(readText(data.resolve("node-id"), "node id"));
        DeviceId deviceId = DeviceId.of(readText(data.resolve("device-id"), "device id"));
        GroupId group = GroupId.parse(arguments.required("group"));
        SyncRootId rootId = SyncRootId.of(arguments.optional("root-id", "root-1"));
        Path root = Path.of(arguments.required("root")).toAbsolutePath();
        Files.createDirectories(root);
        int port = port(arguments.optional("port", "47800"));
        int intervalSeconds = Integer.parseInt(arguments.optional("interval", "0"));
        if (intervalSeconds < 0) {
            throw new IllegalArgumentException("interval must not be negative");
        }
        String expectedClient = arguments.required("expect");

        JdbcMetadataStore store = openStore(data);
        SyncNode node;
        try {
            node = new SyncNode(nodeId, group, rootId, root, store,
                    new FileSystemBlobStore(data), resolver(), identity, deviceId,
                    expectedClient, port, data.resolve("transfer"));
            for (String peer : arguments.all("peer")) {
                PeerAddress address = PeerAddress.parse(peer);
                addPeer(node, address);
            }
        } catch (Exception e) {
            store.close();
            throw e;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            node.close();
            store.close();
        }));

        ScheduledExecutorService scheduler = null;
        if (intervalSeconds > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "itemsync-periodic-sync");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    node.syncNow();
                } catch (Exception e) {
                    System.err.println("periodic sync failed: " + e.getMessage());
                }
            }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        }

        System.out.println("listening on port " + node.port());
        System.out.println("fingerprint: " + identity.fingerprint());
        System.out.flush();
        try {
            new CountDownLatch(1).await();
        } finally {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            node.close();
            store.close();
        }
    }

    private static void sync(CliArguments arguments) throws Exception {
        Path data = dataDirectory(arguments);
        char[] password = password(arguments);
        TlsIdentity identity = TlsIdentity.load(identityFile(data), password);
        NodeId nodeId = NodeId.of(readText(data.resolve("node-id"), "node id"));
        DeviceId deviceId = DeviceId.of(readText(data.resolve("device-id"), "device id"));
        GroupId group = GroupId.parse(arguments.required("group"));
        SyncRootId rootId = SyncRootId.of(arguments.optional("root-id", "root-1"));
        Path root = Path.of(arguments.required("root")).toAbsolutePath();
        Files.createDirectories(root);
        PeerAddress peer = PeerAddress.parse(arguments.required("peer"));
        int waitSeconds = Integer.parseInt(arguments.optional("wait", "3"));

        JdbcMetadataStore store = openStore(data);
        try {
            try (SyncNode node = new SyncNode(nodeId, group, rootId, root, store,
                    new FileSystemBlobStore(data), resolver(), identity, deviceId,
                    peer.fingerprint(), 0, data.resolve("transfer"))) {
                addPeer(node, peer);
                for (SyncRunResult result : node.syncNow()) {
                    System.out.println("pulled=" + result.pulled()
                            + " deleted=" + result.deleted()
                            + " conflicts=" + result.conflicts()
                            + " pendingPush=" + result.pendingPush());
                }
                if (waitSeconds > 0) {
                    Thread.sleep(waitSeconds * 1000L);
                }
            }
        } finally {
            store.close();
        }
    }

    private static void usage() {
        System.out.println("""
                usage: itemsync-cli <command> [options]

                commands:
                  init         --data <dir> --password <pw>
                  fingerprint  --data <dir> --password <pw>
                  serve        --data <dir> --password <pw> --root <path> --group <id>
                               --expect <peer-fingerprint> [--port 47800] [--root-id root-1]
                               [--interval 0] [--peer <host:port:fingerprint>]...
                  sync         --data <dir> --password <pw> --root <path> --group <id>
                               --peer <host:port:fingerprint> [--root-id root-1] [--wait 3]

                The keystore password can also be supplied via ITEMS_SYNC_PASSWORD.
                """);
    }

    private static Path dataDirectory(CliArguments arguments) throws IOException {
        Path data = Path.of(arguments.required("data")).toAbsolutePath();
        Files.createDirectories(data);
        return data;
    }

    private static Path identityFile(Path data) {
        Path file = data.resolve("identity.p12");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("identity not found, run init first: " + file);
        }
        return file;
    }

    private static char[] password(CliArguments arguments) {
        String value = arguments.optional("password", System.getenv("ITEMS_SYNC_PASSWORD"));
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("--password or ITEMS_SYNC_PASSWORD is required");
        }
        return value.toCharArray();
    }

    private static String readText(Path file, String description) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(description + " not found, run init first: " + file);
        }
        return Files.readString(file).trim();
    }

    private static JdbcMetadataStore openStore(Path data) throws Exception {
        Path dbDirectory = data.resolve("db");
        Files.createDirectories(dbDirectory);
        return JdbcMetadataStore.open(
                "jdbc:sqlite:" + dbDirectory.resolve("itemsync.sqlite").toAbsolutePath());
    }

    private static PhysicalPathResolver resolver() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? PathResolvers.windows() : PathResolvers.posix();
    }

    private static int port(String raw) {
        try {
            int port = Integer.parseInt(raw);
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port: " + raw, e);
        }
    }

    private static NodeId peerNodeId(PeerAddress address) {
        String fingerprint = address.fingerprint();
        return NodeId.of("peer-" + fingerprint.substring(0, Math.min(8, fingerprint.length())));
    }

    private static void addPeer(SyncNode node, PeerAddress address) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                node.addPeer(peerNodeId(address), address.host(), address.port(),
                        address.fingerprint());
                return;
            } catch (IOException e) {
                lastFailure = e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw lastFailure;
    }
}
