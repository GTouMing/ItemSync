package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.ConflictId;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.model.SyncRun;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.platform.desktop.NioDirectoryWatcher;
import github.gtouming.itemsync.platform.desktop.ProcessWatcher;
import github.gtouming.itemsync.domain.security.PairingCode;
import github.gtouming.itemsync.store.blob.BlobStore;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import github.gtouming.itemsync.sync.apply.PhysicalPathResolver;
import github.gtouming.itemsync.sync.engine.SyncRunResult;
import github.gtouming.itemsync.sync.engine.node.SyncNode;
import github.gtouming.itemsync.sync.state.ConflictResolver;
import github.gtouming.itemsync.sync.state.VersionHistoryService;
import github.gtouming.itemsync.sync.trigger.TriggerConfig;
import github.gtouming.itemsync.sync.trigger.TriggerConfigCodec;
import github.gtouming.itemsync.sync.trigger.TriggerEngine;
import github.gtouming.itemsync.transport.discovery.DiscoveredPeer;
import github.gtouming.itemsync.transport.discovery.PresenceTracker;
import github.gtouming.itemsync.transport.pairing.PairingStatusClient;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

public final class DesktopService implements AutoCloseable {

    private static final Duration REMOTE_TRUST_TTL = Duration.ofSeconds(30);
    private static final Duration REMOTE_TRUST_FAILURE_TTL = Duration.ofSeconds(5);
    private static final Duration PAIRING_QUERY_TIMEOUT = Duration.ofSeconds(2);

    private final DesktopConfig config;
    private final TlsIdentity identity;
    private final JdbcMetadataStore store;
    private final SyncNode node;
    private final TriggerEngine triggerEngine;
    private final ConflictResolver conflictResolver;
    private final VersionHistoryService history;
    private final TrustStore trustStore;
    private final Set<String> connectedPeers = ConcurrentHashMap.newKeySet();
    private final RemoteTrustCache remoteTrustCache = new RemoteTrustCache(Instant::now);
    private final ExecutorService statusExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "itemsync-pairing-status");
        thread.setDaemon(true);
        return thread;
    });
    private final List<AutoCloseable> watchers;

    private DesktopService(DesktopConfig config, TlsIdentity identity, JdbcMetadataStore store,
                           SyncNode node, TriggerEngine triggerEngine,
                           ConflictResolver conflictResolver, VersionHistoryService history,
                           TrustStore trustStore, List<AutoCloseable> watchers) {
        this.config = config;
        this.identity = identity;
        this.store = store;
        this.node = node;
        this.triggerEngine = triggerEngine;
        this.conflictResolver = conflictResolver;
        this.history = history;
        this.trustStore = trustStore;
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
            node = new SyncNode(nodeId, config.group(), config.rootId(), config.root(), store,
                    blobs, physical, identity, deviceId,
                    acceptedClients, config.port(),
                    config.dataDir().resolve("transfer"), trustStore::isTrusted);
        } catch (Exception e) {
            store.close();
            throw e;
        }

        ConflictResolver conflictResolver = new ConflictResolver(config.root(), store, physical,
                config.rootId(), nodeId, Instant::now);
        VersionHistoryService history = new VersionHistoryService(config.root(), store, blobs,
                physical, config.rootId(), nodeId, Instant::now);

        TriggerEngine engine = new TriggerEngine(store, config.group(), config.rootId(), nodeId,
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
            node.close();
            store.close();
            throw e;
        }

        return new DesktopService(config, identity, store, node, engine, conflictResolver,
                history, trustStore, List.copyOf(watchers));
    }

    public List<SyncRunResult> syncNow() throws IOException {
        reconcileTrustedPeers();
        return node.syncNow();
    }

    public List<PresenceTracker.Presence> onlinePeers() {
        List<PresenceTracker.Presence> peers = node.onlinePeers();
        retainOnlinePeers(peers);
        for (PresenceTracker.Presence presence : peers) {
            if (isTrusted(presence.peer())) {
                try {
                    connect(presence.peer());
                } catch (IOException ignored) {
                    // the peer has not trusted us yet, or is temporarily unreachable
                }
            }
        }
        return peers;
    }

    public List<PeerView> peerViews() {
        return peerViews(true);
    }

    public List<PeerView> peerViews(boolean forceRefresh) {
        List<PresenceTracker.Presence> peers = onlinePeers();
        List<CompletableFuture<PeerView>> futures = new ArrayList<>(peers.size());
        for (PresenceTracker.Presence presence : peers) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> viewOf(presence, forceRefresh), statusExecutor));
        }
        List<PeerView> views = new ArrayList<>(futures.size());
        for (CompletableFuture<PeerView> future : futures) {
            views.add(future.join());
        }
        return List.copyOf(views);
    }

    private PeerView viewOf(PresenceTracker.Presence presence, boolean forceRefresh) {
        DiscoveredPeer peer = presence.peer();
        return new PeerView(presence, isTrusted(peer), remoteTrust(peer, forceRefresh),
                isConnected(peer));
    }

    private RemoteTrust remoteTrust(DiscoveredPeer peer, boolean forceRefresh) {
        if (peer.pairingPort() <= 0) {
            return RemoteTrust.UNKNOWN;
        }
        if (!forceRefresh) {
            var cached = remoteTrustCache.get(peer.fingerprint());
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        try {
            InetAddress address = addressOf(peer);
            PairingStatusClient.Result result = PairingStatusClient.query(
                    address.getHostAddress(), peer.pairingPort(), identity, peer.fingerprint(),
                    config.group(), peer.nodeId(), peer.deviceId(), PAIRING_QUERY_TIMEOUT);
            RemoteTrust trust = result.response().trustsPeer()
                    ? RemoteTrust.TRUSTED
                    : RemoteTrust.UNTRUSTED;
            remoteTrustCache.put(peer.fingerprint(), trust, REMOTE_TRUST_TTL);
            return trust;
        } catch (IOException | RuntimeException e) {
            remoteTrustCache.put(peer.fingerprint(), RemoteTrust.UNKNOWN,
                    REMOTE_TRUST_FAILURE_TTL);
            return RemoteTrust.UNKNOWN;
        }
    }

    public boolean isTrusted(DiscoveredPeer peer) {
        return trustStore.isTrusted(config.group(), peer.fingerprint());
    }

    public boolean isConnected(DiscoveredPeer peer) {
        return connectedPeers.contains(peer.fingerprint().toLowerCase(Locale.ROOT));
    }

    public String pairingCode(DiscoveredPeer peer) {
        return PairingCode.between(config.group(), identity.fingerprint(), peer.fingerprint());
    }

    public void trustPeer(DiscoveredPeer peer) throws IOException {
        trustStore.trust(config.group(), peer.fingerprint());
        remoteTrustCache.invalidate(peer.fingerprint());
        node.allowClient(peer.fingerprint());
        connect(peer);
    }

    public void untrustPeer(DiscoveredPeer peer) throws IOException {
        trustStore.untrust(config.group(), peer.fingerprint());
        remoteTrustCache.invalidate(peer.fingerprint());
        node.revokeClient(peer.fingerprint());
        node.removePeer(peer.nodeId());
        connectedPeers.remove(peer.fingerprint().toLowerCase(Locale.ROOT));
    }

    public void reconcileTrustedPeers() {
        List<PresenceTracker.Presence> peers = node.onlinePeers();
        retainOnlinePeers(peers);
        for (PresenceTracker.Presence presence : peers) {
            if (isTrusted(presence.peer())) {
                try {
                    connect(presence.peer());
                } catch (IOException ignored) {
                    // retry on the next background pass
                }
            }
        }
    }

    public void refreshRemoteTrust() {
        List<PresenceTracker.Presence> peers = node.onlinePeers();
        retainOnlinePeers(peers);
        for (PresenceTracker.Presence presence : peers) {
            if (presence.peer().pairingPort() <= 0) {
                continue;
            }
            try {
                statusExecutor.execute(() -> remoteTrust(presence.peer(), false));
            } catch (RejectedExecutionException ignored) {
                // service is closing
            }
        }
    }

    private void retainOnlinePeers(List<PresenceTracker.Presence> peers) {
        Set<String> online = new LinkedHashSet<>();
        for (PresenceTracker.Presence presence : peers) {
            online.add(presence.peer().fingerprint().toLowerCase(Locale.ROOT));
        }
        connectedPeers.retainAll(online);
    }

    private void connect(DiscoveredPeer peer) throws IOException {
        if (!isTrusted(peer)) {
            return;
        }
        String fingerprint = peer.fingerprint().toLowerCase(Locale.ROOT);
        if (connectedPeers.contains(fingerprint)) {
            return;
        }
        InetAddress address = addressOf(peer);
        node.allowClient(peer.fingerprint());
        node.addPeer(peer.nodeId(), address.getHostAddress(), peer.port(), peer.fingerprint());
        connectedPeers.add(fingerprint);
    }

    private static InetAddress addressOf(DiscoveredPeer peer) throws IOException {
        InetAddress address = peer.addresses().stream()
                .filter(candidate -> candidate instanceof Inet4Address)
                .findFirst()
                .orElseGet(() -> peer.addresses().stream().findFirst().orElse(null));
        if (address == null) {
            throw new IOException("no address for peer " + peer.deviceId().value());
        }
        return address;
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
    }

    public Status status() {
        return node.readSerialized(() -> {
            SyncRun lastRun = store.runs().recent(config.rootId(), 1).stream()
                    .findFirst()
                    .orElse(null);
            int conflicts = store.conflicts().findOpen(1000).size();
            IOException error = node.lastError() != null ? node.lastError() : triggerEngine.lastError();
            return new Status(node.nodeId(), identity.fingerprint(), node.port(), config.root(),
                    lastRun, conflicts, error);
        });
    }

    @Override
    public void close() {
        watchers.forEach(DesktopService::closeQuietly);
        triggerEngine.close();
        node.close();
        statusExecutor.shutdownNow();
        store.close();
    }

    public record PeerView(PresenceTracker.Presence presence, boolean locallyTrusted,
                           RemoteTrust remoteTrust, boolean connected) {

        public boolean identityVerified() {
            return remoteTrust != RemoteTrust.UNKNOWN;
        }
    }

    public record Status(NodeId nodeId, String fingerprint, int port, Path root,
                         SyncRun lastRun, int openConflicts, IOException lastError) {
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
