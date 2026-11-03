package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.ConflictId;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.model.SyncRun;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.platform.desktop.DesktopConfig;
import github.gtouming.itemsync.platform.desktop.DesktopGroup;
import github.gtouming.itemsync.platform.desktop.NioDirectoryWatcher;
import github.gtouming.itemsync.platform.desktop.ProcessWatcher;
import github.gtouming.itemsync.store.blob.BlobStore;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import github.gtouming.itemsync.sync.apply.PhysicalPathResolver;
import github.gtouming.itemsync.sync.engine.SyncRunResult;
import github.gtouming.itemsync.sync.engine.group.GroupRuntime;
import github.gtouming.itemsync.sync.engine.node.SyncDevice;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The desktop backend: one {@link SyncDevice} for every configured group, one trusted peer
 * coordinator, conflict resolver, version history and trigger engine per group, and a single
 * shared metadata store.
 *
 * <p>The existing single-group methods work on the first configured group so the current
 * window keeps behaving as before; the group aware overloads and {@link #syncAllNow()} cover
 * every group. Because one {@link JdbcMetadataStore} is not safe for concurrent group writes,
 * every store touching call is serialized on one service lock: group parallelism is capped to
 * one until the store itself becomes concurrency safe (ADR-0035 §8 asks for two).
 */
public final class DesktopService implements AutoCloseable {

    private final DesktopConfig config;
    private final NodeId nodeId;
    private final TlsIdentity identity;
    private final JdbcMetadataStore store;
    private final SyncDevice device;
    private final List<GroupSession> sessions;
    private final Map<GroupId, GroupSession> sessionsByGroup;
    private final ReentrantLock storeLock;

    private DesktopService(DesktopConfig config, NodeId nodeId, TlsIdentity identity,
                           JdbcMetadataStore store, SyncDevice device,
                           List<GroupSession> sessions, ReentrantLock storeLock) {
        this.config = config;
        this.nodeId = nodeId;
        this.identity = identity;
        this.store = store;
        this.device = device;
        this.sessions = sessions;
        Map<GroupId, GroupSession> byGroup = new LinkedHashMap<>();
        for (GroupSession session : sessions) {
            byGroup.put(session.group().id(), session);
        }
        this.sessionsByGroup = Map.copyOf(byGroup);
        this.storeLock = storeLock;
    }

    public static DesktopService open(DesktopConfig config, char[] password) throws Exception {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(password, "password");

        TlsIdentity identity = ensureIdentity(config.dataDir(), password);
        NodeId nodeId = NodeId.of(read(config.dataDir().resolve("node-id"), "node id"));
        DeviceId deviceId = DeviceId.of(read(config.dataDir().resolve("device-id"), "device id"));
        List<DesktopGroup> groups = config.groups();
        for (DesktopGroup group : groups) {
            Files.createDirectories(group.root());
        }

        TrustStore trustStore = new TrustStore(config.dataDir());
        if (!groups.isEmpty()) {
            // A device that serves several groups keeps the trust of all of them.
            trustStore.retainOnly(groups.stream().map(DesktopGroup::id).toList());
        }

        JdbcMetadataStore store = openStore(config.dataDir());
        BlobStore blobs = new FileSystemBlobStore(config.dataDir());
        PhysicalPathResolver physical = resolver();
        ReentrantLock storeLock = new ReentrantLock();
        SyncDevice device = null;
        List<GroupSession> sessions = new ArrayList<>();
        try {
            if (!groups.isEmpty()) {
                device = openDevice(nodeId, deviceId, identity, store, blobs, physical, groups,
                        trustStore, config);
            }
            for (DesktopGroup group : groups) {
                GroupRuntime runtime = device == null ? null : device.group(group.id());
                if (runtime == null) {
                    throw new IllegalStateException(
                            "the sync device did not start group " + group.id().value());
                }
                sessions.add(openGroup(group, runtime, nodeId, identity, store, blobs, physical,
                        trustStore, config, storeLock));
            }
        } catch (Exception e) {
            for (GroupSession session : sessions) {
                session.close();
            }
            if (device != null) {
                device.close();
            }
            store.close();
            throw e;
        }
        return new DesktopService(config, nodeId, identity, store, device,
                List.copyOf(sessions), storeLock);
    }

    private static SyncDevice openDevice(NodeId nodeId, DeviceId deviceId, TlsIdentity identity,
                                         JdbcMetadataStore store, BlobStore blobs,
                                         PhysicalPathResolver physical, List<DesktopGroup> groups,
                                         TrustStore trustStore, DesktopConfig config)
            throws IOException {
        List<SyncDevice.GroupConfig> groupConfigs = new ArrayList<>(groups.size());
        for (DesktopGroup group : groups) {
            // PinnedTrustManager rejects an empty set; "none" never matches a certificate.
            // Trusted peers are added dynamically through the device list of that group.
            groupConfigs.add(new SyncDevice.GroupConfig(group.id(), group.name(), group.root(),
                    Set.of("none"), trustStore::isTrusted));
        }
        return new SyncDevice(nodeId, deviceId, identity, store, blobs, physical, groupConfigs,
                config.port(), config.dataDir().resolve("transfer"), null);
    }

    private static GroupSession openGroup(DesktopGroup group, GroupRuntime runtime, NodeId nodeId,
                                          TlsIdentity identity, JdbcMetadataStore store,
                                          BlobStore blobs, PhysicalPathResolver physical,
                                          TrustStore trustStore, DesktopConfig config,
                                          ReentrantLock storeLock) throws Exception {
        PeerTrustCoordinator peerTrust = new PeerTrustCoordinator(identity, runtime, trustStore);
        ConflictResolver conflictResolver = new ConflictResolver(group.root(), store, physical,
                group.id(), nodeId, Instant::now);
        VersionHistoryService history = new VersionHistoryService(group.root(), store, blobs,
                physical, group.id(), nodeId, Instant::now);
        TriggerEngine engine = new TriggerEngine(store, group.id(), nodeId,
                () -> storeGuarded(storeLock, runtime::syncNow),
                work -> storeGuarded(storeLock, () -> runtime.runSerialized(work)),
                Instant::now, config.debounce());
        List<AutoCloseable> watchers = new ArrayList<>();
        try {
            List<Trigger> triggers = store.triggers().findByGroup(group.id());
            engine.registerAll(triggers);
            for (Trigger trigger : triggers) {
                if (!trigger.enabled()) {
                    continue;
                }
                TriggerConfig triggerConfig = TriggerConfigCodec.decode(trigger.configJson());
                if (triggerConfig instanceof TriggerConfig.DirectoryChange) {
                    watchers.add(new NioDirectoryWatcher(group.root(),
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
            throw e;
        }
        return new GroupSession(group, runtime, peerTrust, conflictResolver, history, engine,
                List.copyOf(watchers));
    }

    /**
     * The configured groups, in the order they are stored.
     */
    public List<DesktopGroup> groups() {
        return config.groups();
    }

    /**
     * The running runtime of every group; empty when this installation has no group yet.
     */
    public Map<GroupId, GroupRuntime> groupRuntimes() {
        return device == null ? Map.of() : device.groups();
    }

    /**
     * Syncs the first group, like the single-group window always did.
     */
    public List<SyncRunResult> syncNow() throws IOException {
        GroupSession session = firstSessionOrNull();
        return session == null ? List.of() : syncNow(session);
    }

    public List<SyncRunResult> syncNow(GroupId groupId) throws IOException {
        return syncNow(requireSession(groupId));
    }

    /**
     * Syncs every group one after another and combines the runs. Groups are deliberately not
     * synced in parallel: the shared metadata store only tolerates one writer.
     */
    public List<SyncRunResult> syncAllNow() throws IOException {
        List<SyncRunResult> combined = new ArrayList<>();
        for (GroupSession session : sessions) {
            combined.addAll(syncNow(session));
        }
        return List.copyOf(combined);
    }

    public List<PresenceTracker.Presence> onlinePeers() {
        GroupSession session = firstSessionOrNull();
        return session == null ? List.of() : session.peerTrust().onlinePeers();
    }

    public List<PresenceTracker.Presence> onlinePeers(GroupId groupId) {
        return requireSession(groupId).peerTrust().onlinePeers();
    }

    public List<PeerTrustCoordinator.PeerView> peerViews() {
        GroupSession session = firstSessionOrNull();
        return session == null ? List.of() : session.peerTrust().peerViews();
    }

    public List<PeerTrustCoordinator.PeerView> peerViews(boolean forceRefresh) {
        GroupSession session = firstSessionOrNull();
        return session == null ? List.of() : session.peerTrust().peerViews(forceRefresh);
    }

    public List<PeerTrustCoordinator.PeerView> peerViews(GroupId groupId) {
        return requireSession(groupId).peerTrust().peerViews();
    }

    public List<PeerTrustCoordinator.PeerView> peerViews(GroupId groupId, boolean forceRefresh) {
        return requireSession(groupId).peerTrust().peerViews(forceRefresh);
    }

    public boolean isTrusted(DiscoveredPeer peer) {
        GroupSession session = firstSessionOrNull();
        return session != null && session.peerTrust().isTrusted(peer);
    }

    public boolean isTrusted(GroupId groupId, DiscoveredPeer peer) {
        return requireSession(groupId).peerTrust().isTrusted(peer);
    }

    public boolean isConnected(DiscoveredPeer peer) {
        GroupSession session = firstSessionOrNull();
        return session != null && session.peerTrust().isConnected(peer);
    }

    public boolean isConnected(GroupId groupId, DiscoveredPeer peer) {
        return requireSession(groupId).peerTrust().isConnected(peer);
    }

    public void trustPeer(DiscoveredPeer peer) throws IOException {
        requireFirstSession().peerTrust().trustPeer(peer);
    }

    public void trustPeer(GroupId groupId, DiscoveredPeer peer) throws IOException {
        requireSession(groupId).peerTrust().trustPeer(peer);
    }

    public void untrustPeer(DiscoveredPeer peer) throws IOException {
        requireFirstSession().peerTrust().untrustPeer(peer);
    }

    public void untrustPeer(GroupId groupId, DiscoveredPeer peer) throws IOException {
        requireSession(groupId).peerTrust().untrustPeer(peer);
    }

    public void reconcileTrustedPeers() {
        for (GroupSession session : sessions) {
            session.peerTrust().reconcileTrustedPeers();
        }
    }

    public void reconcileTrustedPeers(GroupId groupId) {
        requireSession(groupId).peerTrust().reconcileTrustedPeers();
    }

    public void refreshRemoteTrust() {
        for (GroupSession session : sessions) {
            session.peerTrust().refreshRemoteTrust();
        }
    }

    public void refreshRemoteTrust(GroupId groupId) {
        requireSession(groupId).peerTrust().refreshRemoteTrust();
    }

    public List<ConflictResolver.ConflictView> openConflicts() {
        GroupSession session = firstSessionOrNull();
        if (session == null) {
            return List.of();
        }
        return storeGuardedUnchecked(storeLock,
                () -> session.runtime().readSerialized(() -> session.conflicts().open(200)));
    }

    public void resolveConflict(ConflictId id, ConflictResolver.Resolution resolution)
            throws IOException {
        GroupSession session = requireFirstSession();
        try {
            storeGuarded(storeLock, () -> session.runtime().readSerialized(() -> {
                try {
                    session.conflicts().resolve(id, resolution);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return null;
            }));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        session.runtime().refreshManifest();
    }

    public List<VersionHistoryService.ItemView> historyItems() {
        GroupSession session = firstSessionOrNull();
        if (session == null) {
            return List.of();
        }
        return storeGuardedUnchecked(storeLock,
                () -> session.runtime().readSerialized(() -> session.history().items(200)));
    }

    public List<FileVersion> versions(ItemId itemId) {
        GroupSession session = firstSessionOrNull();
        if (session == null) {
            return List.of();
        }
        return storeGuardedUnchecked(storeLock,
                () -> session.runtime().readSerialized(() -> session.history().versions(itemId)));
    }

    public void restoreVersion(ItemId itemId, VersionId versionId) throws IOException {
        GroupSession session = requireFirstSession();
        try {
            storeGuarded(storeLock, () -> session.runtime().readSerialized(() -> {
                try {
                    session.history().restore(itemId, versionId);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return null;
            }));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        session.runtime().refreshManifest();
    }

    /**
     * The status of the first group; with no group the installation still reports its node,
     * fingerprint, port and conflicts so the window can open on an empty installation.
     */
    public Status status() {
        GroupSession session = firstSessionOrNull();
        if (session == null) {
            return new Status(null, nodeId, identity.fingerprint(), config.port(), null, null,
                    store.conflicts().findOpen(1000).size(), deviceError(), null);
        }
        return storeGuardedUnchecked(storeLock, () -> session.runtime().readSerialized(() -> {
            SyncRun lastRun = store.runs().recent(session.group().id(), 1).stream()
                    .findFirst()
                    .orElse(null);
            int conflicts = store.conflicts().findOpen(1000).size();
            return new Status(session.group().id(), nodeId, identity.fingerprint(), port(),
                    session.group().root(), lastRun, conflicts, lastError(session),
                    session.peerTrust().lastWarning());
        }));
    }

    /**
     * The last error of the first group, falling back to the failures of the shared sync port.
     */
    public IOException lastError() {
        GroupSession session = firstSessionOrNull();
        return session == null ? deviceError() : lastError(session);
    }

    public IOException lastError(GroupId groupId) {
        return lastError(requireSession(groupId));
    }

    @Override
    public void close() {
        for (GroupSession session : sessions) {
            session.close();
        }
        if (device != null) {
            device.close();
        }
        store.close();
    }

    public record Status(GroupId groupId, NodeId nodeId, String fingerprint, int port, Path root,
                         SyncRun lastRun, int openConflicts, IOException lastError,
                         String trustWarning) {
    }

    private List<SyncRunResult> syncNow(GroupSession session) throws IOException {
        session.peerTrust().reconcileTrustedPeers();
        return storeGuarded(storeLock, () -> session.runtime().syncNow());
    }

    private IOException lastError(GroupSession session) {
        IOException error = session.runtime().lastError();
        return error != null ? error : deviceError();
    }

    private IOException deviceError() {
        return device == null ? null : device.lastError();
    }

    private int port() {
        return device == null ? config.port() : device.port();
    }

    private GroupSession firstSessionOrNull() {
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    private GroupSession requireFirstSession() {
        GroupSession session = firstSessionOrNull();
        if (session == null) {
            throw new IllegalStateException("desktop configuration has no sync group");
        }
        return session;
    }

    private GroupSession requireSession(GroupId groupId) {
        Objects.requireNonNull(groupId, "groupId");
        GroupSession session = sessionsByGroup.get(groupId);
        if (session == null) {
            throw new IllegalArgumentException(
                    "this installation does not serve the sync group " + groupId.value());
        }
        return session;
    }

    @FunctionalInterface
    private interface StoreTask<T> {
        T run() throws IOException;
    }

    private static <T> T storeGuarded(ReentrantLock lock, StoreTask<T> task) throws IOException {
        lock.lock();
        try {
            return task.run();
        } finally {
            lock.unlock();
        }
    }

    private static <T> T storeGuardedUnchecked(ReentrantLock lock, Supplier<T> task) {
        lock.lock();
        try {
            return task.get();
        } finally {
            lock.unlock();
        }
    }

    private record GroupSession(DesktopGroup group, GroupRuntime runtime,
                                PeerTrustCoordinator peerTrust, ConflictResolver conflicts,
                                VersionHistoryService history, TriggerEngine triggers,
                                List<AutoCloseable> watchers) implements AutoCloseable {

        @Override
        public void close() {
            watchers.forEach(DesktopService::closeQuietly);
            triggers.close();
            peerTrust.close();
        }
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
