package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.ConflictId;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.model.SyncRun;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.platform.desktop.DesktopConfig;
import github.gtouming.itemsync.platform.desktop.NioDirectoryWatcher;
import github.gtouming.itemsync.platform.desktop.ProcessWatcher;
import github.gtouming.itemsync.store.blob.BlobStore;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import github.gtouming.itemsync.sync.apply.PhysicalPathResolver;
import github.gtouming.itemsync.sync.engine.SyncRunResult;
import github.gtouming.itemsync.sync.engine.node.SyncNode;
import github.gtouming.itemsync.sync.state.ConflictResolver;
import github.gtouming.itemsync.sync.state.VersionHistoryService;
import github.gtouming.itemsync.sync.trust.PeerTrustCoordinator;
import github.gtouming.itemsync.sync.trigger.TriggerConfig;
import github.gtouming.itemsync.sync.trigger.TriggerConfigCodec;
import github.gtouming.itemsync.sync.trigger.TriggerEngine;
import github.gtouming.itemsync.transport.discovery.DiscoveredPeer;
import github.gtouming.itemsync.transport.discovery.PresenceTracker;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class DesktopService implements AutoCloseable {

    private final DesktopConfig config;
    private final TlsIdentity identity;
    private final JdbcMetadataStore store;
    private final SyncNode node;
    private final TriggerEngine triggerEngine;
    private final ConflictResolver conflictResolver;
    private final VersionHistoryService history;
    private final PeerTrustCoordinator peerTrust;
    private final List<AutoCloseable> watchers;

    private DesktopService(DesktopConfig config, TlsIdentity identity, JdbcMetadataStore store,
                           SyncNode node, TriggerEngine triggerEngine,
                           ConflictResolver conflictResolver, VersionHistoryService history,
                           PeerTrustCoordinator peerTrust, List<AutoCloseable> watchers) {
        this.config = config;
        this.identity = identity;
        this.store = store;
        this.node = node;
        this.triggerEngine = triggerEngine;
        this.conflictResolver = conflictResolver;
        this.history = history;
        this.peerTrust = peerTrust;
        this.watchers = watchers;
    }

    public static DesktopService open(DesktopConfig config, char[] password) throws Exception {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(password, "password");

        TlsIdentity identity = ensureIdentity(config.dataDir(), password);
        NodeId nodeId = NodeId.of(read(config.dataDir().resolve("node-id"), "node id"));
        DeviceId deviceId = DeviceId.of(read(config.dataDir().resolve("device-id"), "device id"));
        Files.createDirectories(config.root());

        TrustStore trustStore = new TrustStore(config.dataDir());
        trustStore.retainOnly(config.group());

        JdbcMetadataStore store = openStore(config.dataDir());
        PhysicalPathResolver physical = resolver();
        BlobStore blobs = new FileSystemBlobStore(config.dataDir());
        // SyncNode and PinnedTrustManager reject an empty set; "none" never matches a
        // certificate. Trusted peers are added dynamically through the device list.
        Set<String> acceptedClients = Set.of("none");
        SyncNode node;
        try {
            node = new SyncNode(nodeId, config.group(), config.root(), store,
                    blobs, physical, identity, deviceId,
                    acceptedClients, config.port(),
                    config.dataDir().resolve("transfer"), trustStore::isTrusted);
        } catch (Exception e) {
            store.close();
            throw e;
        }
        PeerTrustCoordinator peerTrust = new PeerTrustCoordinator(
                config.group(), identity, node, trustStore);

        ConflictResolver conflictResolver = new ConflictResolver(config.root(), store, physical,
                config.group(), nodeId, Instant::now);
        VersionHistoryService history = new VersionHistoryService(config.root(), store, blobs,
                physical, config.group(), nodeId, Instant::now);

        TriggerEngine engine = new TriggerEngine(store, config.group(), nodeId,
                node::syncNow, node::runSerialized, Instant::now, config.debounce());
        List<AutoCloseable> watchers = new ArrayList<>();
        try {
            List<Trigger> triggers = store.triggers().findByGroup(config.group());
            engine.registerAll(triggers);
            for (Trigger trigger : triggers) {
                if (!trigger.enabled()) {
                    continue;
                }
                TriggerConfig triggerConfig = TriggerConfigCodec.decode(trigger.configJson());
                if (triggerConfig instanceof TriggerConfig.DirectoryChange) {
                    watchers.add(new NioDirectoryWatcher(config.root(),
                            path -> engine.onDirectoryChanged(trigger.id())));
                } else if (triggerConfig instanceof TriggerConfig.ProcessEvent process) {
                    Consumer<String> callback = executable -> engine.onProcessEvent(trigger.id());
                    watchers.add(new ProcessWatcher(process.executables(), callback, callback,
                            Duration.ofSeconds(2)));
                }
            }
        } catch (Exception e) {
            watchers.forEach(DesktopService::closeQuietly);
            engine.close();
            peerTrust.close();
            node.close();
            store.close();
            throw e;
        }

        return new DesktopService(config, identity, store, node, engine, conflictResolver,
                history, peerTrust, List.copyOf(watchers));
    }

    public List<SyncRunResult> syncNow() throws IOException {
        peerTrust.reconcileTrustedPeers();
        return node.syncNow();
    }

    public List<PresenceTracker.Presence> onlinePeers() {
        return peerTrust.onlinePeers();
    }

    public List<PeerTrustCoordinator.PeerView> peerViews() {
        return peerTrust.peerViews();
    }

    public List<PeerTrustCoordinator.PeerView> peerViews(boolean forceRefresh) {
        return peerTrust.peerViews(forceRefresh);
    }

    public boolean isTrusted(DiscoveredPeer peer) {
        return peerTrust.isTrusted(peer);
    }

    public boolean isConnected(DiscoveredPeer peer) {
        return peerTrust.isConnected(peer);
    }

    public void trustPeer(DiscoveredPeer peer) throws IOException {
        peerTrust.trustPeer(peer);
    }

    public void untrustPeer(DiscoveredPeer peer) throws IOException {
        peerTrust.untrustPeer(peer);
    }

    public void reconcileTrustedPeers() {
        peerTrust.reconcileTrustedPeers();
    }

    public void refreshRemoteTrust() {
        peerTrust.refreshRemoteTrust();
    }

    public List<ConflictResolver.ConflictView> openConflicts() {
        return node.readSerialized(() -> conflictResolver.open(200));
    }

    public void resolveConflict(ConflictId id, ConflictResolver.Resolution resolution)
            throws IOException {
        try {
            node.readSerialized(() -> {
                try {
                    conflictResolver.resolve(id, resolution);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        node.refreshManifest();
    }

    public List<VersionHistoryService.ItemView> historyItems() {
        return node.readSerialized(() -> history.items(200));
    }

    public List<FileVersion> versions(ItemId itemId) {
        return node.readSerialized(() -> history.versions(itemId));
    }

    public void restoreVersion(ItemId itemId, VersionId versionId) throws IOException {
        try {
            node.readSerialized(() -> {
                try {
                    history.restore(itemId, versionId);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        node.refreshManifest();
    }

    public Status status() {
        return node.readSerialized(() -> {
            SyncRun lastRun = store.runs().recent(config.group(), 1).stream()
                    .findFirst()
                    .orElse(null);
            int conflicts = store.conflicts().findOpen(1000).size();
            IOException error = node.lastError() != null ? node.lastError() : triggerEngine.lastError();
            return new Status(node.nodeId(), identity.fingerprint(), node.port(), config.root(),
                    lastRun, conflicts, error, peerTrust.lastWarning());
        });
    }

    @Override
    public void close() {
        watchers.forEach(DesktopService::closeQuietly);
        triggerEngine.close();
        peerTrust.close();
        node.close();
        store.close();
    }

    public record Status(NodeId nodeId, String fingerprint, int port, Path root,
                         SyncRun lastRun, int openConflicts, IOException lastError,
                         String trustWarning) {
    }

    private static TlsIdentity ensureIdentity(Path dataDir, char[] password) throws IOException {
        Path identityFile = dataDir.resolve("identity.p12");
        TlsIdentity identity;
        if (Files.isRegularFile(identityFile)) {
            identity = TlsIdentity.load(identityFile, password);
        } else {
            identity = TlsIdentity.generate("itemsync", password);
            identity.save(identityFile);
        }
        ensureId(dataDir.resolve("node-id"));
        ensureId(dataDir.resolve("device-id"));
        return identity;
    }

    private static void ensureId(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            Files.writeString(file, UUID.randomUUID().toString());
        }
    }

    private static JdbcMetadataStore openStore(Path dataDir) throws Exception {
        Path dbDirectory = dataDir.resolve("db");
        Files.createDirectories(dbDirectory);
        return JdbcMetadataStore.open(
                "jdbc:sqlite:" + dbDirectory.resolve("itemsync.sqlite").toAbsolutePath());
    }

    private static PhysicalPathResolver resolver() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? PathResolvers.windows() : PathResolvers.posix();
    }

    private static String read(Path file, String description) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(
                    description + " not found, run itemsync-cli init first: " + file);
        }
        return Files.readString(file).trim();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // closing is best effort
        }
    }
}
