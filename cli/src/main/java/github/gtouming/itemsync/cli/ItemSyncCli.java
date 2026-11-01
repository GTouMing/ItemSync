package github.gtouming.itemsync.cli;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.model.SyncRun;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.domain.model.TriggerType;
import github.gtouming.itemsync.platform.desktop.NioDirectoryWatcher;
import github.gtouming.itemsync.platform.desktop.PathSupport;
import github.gtouming.itemsync.platform.desktop.ProcessWatcher;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import github.gtouming.itemsync.sync.apply.PhysicalPathResolver;
import github.gtouming.itemsync.sync.engine.SyncRunResult;
import github.gtouming.itemsync.sync.engine.node.SyncNode;
import github.gtouming.itemsync.sync.trigger.AlarmSchedule;
import github.gtouming.itemsync.sync.trigger.TriggerConfig;
import github.gtouming.itemsync.sync.trigger.TriggerConfigCodec;
import github.gtouming.itemsync.sync.trigger.TriggerEngine;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
                case "trigger-add" -> triggerAdd(arguments);
                case "trigger-list" -> triggerList(arguments);
                case "trigger-remove" -> triggerRemove(arguments);
                case "run-list" -> runList(arguments);
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
        TlsIdentity identity = TlsIdentity.load(identityFile(data), password(arguments));
        NodeId nodeId = NodeId.of(readText(data.resolve("node-id"), "node id"));
        DeviceId deviceId = DeviceId.of(readText(data.resolve("device-id"), "device id"));
        GroupId group = GroupId.parse(arguments.required("group"));
        SyncRootId rootId = SyncRootId.of(arguments.optional("root-id", "root-1"));
        Path root = PathSupport.expandUser(arguments.required("root")).toAbsolutePath();
        Files.createDirectories(root);
        PathSupport.requireDataDirOutsideRoot(data, root);
        int port = port(arguments.optional("port", "47800"));
        int intervalSeconds = Integer.parseInt(arguments.optional("interval", "0"));
        int debounceSeconds = Integer.parseInt(arguments.optional("debounce", "30"));
        if (intervalSeconds < 0 || debounceSeconds < 0) {
            throw new IllegalArgumentException("interval and debounce must not be negative");
        }
        String expectedClient = arguments.required("expect");

        JdbcMetadataStore store = openStore(data);
        SyncNode node;
        try {
            node = new SyncNode(nodeId, group, rootId, root, store,
                    new FileSystemBlobStore(data), resolver(), identity, deviceId,
                    expectedClient, port, data.resolve("transfer"));
            for (String peer : arguments.all("peer")) {
                addPeer(node, PeerAddress.parse(peer));
            }
        } catch (Exception e) {
            store.close();
            throw e;
        }

        TriggerEngine triggerEngine = new TriggerEngine(store, group, rootId, nodeId,
                node::syncNow, node::runSerialized, Instant::now,
                Duration.ofSeconds(debounceSeconds));
        List<AutoCloseable> watchers = new ArrayList<>();
        try {
            List<Trigger> triggers = store.triggers().findByGroup(group);
            triggerEngine.registerAll(triggers);
            for (Trigger trigger : triggers) {
                if (!trigger.enabled()) {
                    continue;
                }
                TriggerConfig config = TriggerConfigCodec.decode(trigger.configJson());
                if (config instanceof TriggerConfig.DirectoryChange) {
                    watchers.add(new NioDirectoryWatcher(root,
                            path -> triggerEngine.onDirectoryChanged(trigger.id())));
                } else if (config instanceof TriggerConfig.ProcessEvent process) {
                    Consumer<String> callback = executable -> triggerEngine.onProcessEvent(trigger.id());
                    watchers.add(new ProcessWatcher(process.executables(), callback, callback,
                            Duration.ofSeconds(2)));
                }
            }
        } catch (Exception e) {
            for (AutoCloseable watcher : watchers) {
                closeQuietly(watcher);
            }
            closeQuietly(triggerEngine);
            node.close();
            store.close();
            throw e;
        }

        ScheduledExecutorService scheduler = periodicSync(intervalSeconds, node);
        Runnable shutdown = () -> {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            for (AutoCloseable watcher : watchers) {
                closeQuietly(watcher);
            }
            closeQuietly(triggerEngine);
            node.close();
            store.close();
        };
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown));

        System.out.println("listening on port " + node.port());
        System.out.println("fingerprint: " + identity.fingerprint());
        System.out.flush();
        try {
            new CountDownLatch(1).await();
        } finally {
            shutdown.run();
        }
    }

    private static void sync(CliArguments arguments) throws Exception {
        Path data = dataDirectory(arguments);
        TlsIdentity identity = TlsIdentity.load(identityFile(data), password(arguments));
        NodeId nodeId = NodeId.of(readText(data.resolve("node-id"), "node id"));
        DeviceId deviceId = DeviceId.of(readText(data.resolve("device-id"), "device id"));
        GroupId group = GroupId.parse(arguments.required("group"));
        SyncRootId rootId = SyncRootId.of(arguments.optional("root-id", "root-1"));
        Path root = PathSupport.expandUser(arguments.required("root")).toAbsolutePath();
        Files.createDirectories(root);
        PathSupport.requireDataDirOutsideRoot(data, root);
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

    private static void triggerAdd(CliArguments arguments) throws Exception {
        Path data = dataDirectory(arguments);
        GroupId group = GroupId.parse(arguments.required("group"));
        SyncRootId rootId = SyncRootId.of(arguments.optional("root-id", "root-1"));
        String type = arguments.required("type");

        TriggerType triggerType;
        TriggerConfig config;
        switch (type) {
            case "alarm" -> {
                Set<DayOfWeek> days = parseDays(arguments.required("days"));
                LocalTime time = LocalTime.parse(arguments.required("time"));
                ZoneId zone = ZoneId.of(arguments.optional("zone", ZoneId.systemDefault().getId()));
                config = new TriggerConfig.Alarm(new AlarmSchedule(days, time, zone));
                triggerType = TriggerType.ALARM;
            }
            case "directory" -> {
                config = new TriggerConfig.DirectoryChange();
                triggerType = TriggerType.CONDITION;
            }
            case "process" -> {
                List<String> executables = arguments.all("executable");
                if (executables.isEmpty()) {
                    throw new IllegalArgumentException("--executable is required for process triggers");
                }
                boolean onStart = Boolean.parseBoolean(arguments.optional("on-start", "false"));
                boolean onStop = Boolean.parseBoolean(arguments.optional("on-stop", "false"));
                config = new TriggerConfig.ProcessEvent(new LinkedHashSet<>(executables), onStart, onStop);
                triggerType = TriggerType.CONDITION;
            }
            default -> throw new IllegalArgumentException("unknown trigger type: " + type);
        }

        JdbcMetadataStore store = openStore(data);
        try {
            Trigger trigger = new Trigger(new TriggerId(UUID.randomUUID().toString()), group, rootId,
                    triggerType, TriggerConfigCodec.encode(config), true, null);
            store.triggers().upsert(trigger);
            System.out.println("trigger: " + trigger.id());
        } finally {
            store.close();
        }
    }

    private static void triggerList(CliArguments arguments) throws Exception {
        Path data = dataDirectory(arguments);
        GroupId group = GroupId.parse(arguments.required("group"));
        JdbcMetadataStore store = openStore(data);
        try {
            for (Trigger trigger : store.triggers().findByGroup(group)) {
                System.out.println(trigger.id() + " " + trigger.type()
                        + " enabled=" + trigger.enabled() + " " + trigger.configJson());
            }
        } finally {
            store.close();
        }
    }

    private static void triggerRemove(CliArguments arguments) throws Exception {
        Path data = dataDirectory(arguments);
        TriggerId id = TriggerId.of(arguments.required("id"));
        JdbcMetadataStore store = openStore(data);
        try {
            store.triggers().delete(id);
            System.out.println("removed: " + id);
        } finally {
            store.close();
        }
    }

    private static void runList(CliArguments arguments) throws Exception {
        Path data = dataDirectory(arguments);
        SyncRootId rootId = SyncRootId.of(arguments.optional("root-id", "root-1"));
        JdbcMetadataStore store = openStore(data);
        try {
            for (SyncRun run : store.runs().recent(rootId, 20)) {
                System.out.println(run.id() + " " + run.state()
                        + " trigger=" + run.triggerId()
                        + " stats=" + run.statsJson()
                        + " error=" + run.errorCode());
            }
        } finally {
            store.close();
        }
    }

    private static void usage() {
        System.out.println("""
                usage: itemsync-cli <command> [options]

                commands:
                  init          --data <dir> --password <pw>
                  fingerprint   --data <dir> --password <pw>
                  serve         --data <dir> --password <pw> --root <path> --group <id>
                                --expect <peer-fingerprint> [--port 47800] [--root-id root-1]
                                [--interval 0] [--debounce 30] [--peer <host:port:fingerprint>]...
                  sync          --data <dir> --password <pw> --root <path> --group <id>
                                --peer <host:port:fingerprint> [--root-id root-1] [--wait 3]
                  trigger-add   --data <dir> --group <id> --type alarm|directory|process
                                  alarm:     --days MON,WED --time 23:00 [--zone Asia/Shanghai]
                                  directory: (no extra options)
                                  process:   --executable code.exe [--on-start true] [--on-stop true]
                  trigger-list  --data <dir> --group <id>
                  trigger-remove --data <dir> --id <trigger-id>
                  run-list      --data <dir> [--root-id root-1]

                The --password value protects the local identity keystore (identity.p12),
                not an account. It can also be supplied via ITEMS_SYNC_PASSWORD.
                """);
    }

    private static Path dataDirectory(CliArguments arguments) throws IOException {
        Path data = PathSupport.expandUser(arguments.required("data")).toAbsolutePath();
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

    private static Set<DayOfWeek> parseDays(String raw) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                days.add(DayOfWeek.valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid day: " + value, e);
            }
        }
        if (days.isEmpty()) {
            throw new IllegalArgumentException("--days must list at least one day");
        }
        return days;
    }

    private static ScheduledExecutorService periodicSync(int intervalSeconds, SyncNode node) {
        if (intervalSeconds <= 0) {
            return null;
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
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
        return scheduler;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // closing is best effort
        }
    }
}
