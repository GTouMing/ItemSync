package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.ConflictId;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.Conflict;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.model.GroupDevice;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.model.SyncRun;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.domain.model.TriggerType;
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
import java.util.Comparator;
import java.util.HashSet;
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
    private final TrustStore trustStore;
    private final SyncDevice device;
    private final List<GroupSession> sessions;
    private final Map<GroupId, GroupSession> sessionsByGroup;
    private final ReentrantLock storeLock;

    private DesktopService(DesktopConfig config, NodeId nodeId, TlsIdentity identity,
                           JdbcMetadataStore store, TrustStore trustStore, SyncDevice device,
                           List<GroupSession> sessions, ReentrantLock storeLock) {
        this.config = config;
        this.nodeId = nodeId;
        this.identity = identity;
        this.store = store;
        this.trustStore = trustStore;
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
        // A device keeps the trust of exactly the groups it still serves, so deleting a group
        // (or the last group) drops its trust pairs on the next start (ADR-0035 §13).
        trustStore.retainOnly(groups.stream().map(DesktopGroup::id).toList());

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
        return new DesktopService(config, nodeId, identity, store, trustStore, device,
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
            // One directory watcher per group; it asks the engine for the current directory
            // rules on every event so adding or removing one is live.
            watchers.add(new NioDirectoryWatcher(group.root(), path -> {
                for (TriggerId triggerId : engine.directoryTriggers()) {
                    engine.onDirectoryChanged(triggerId);
                }
            }));
            for (Trigger trigger : triggers) {
                if (!trigger.enabled()) {
                    continue;
                }
                TriggerConfig triggerConfig = TriggerConfigCodec.decode(trigger.configJson());
                if (triggerConfig instanceof TriggerConfig.ProcessEvent process) {
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
     * Registers a listener that fires when a peer appears or disappears in one group. The
     * listener runs on the discovery thread; callers must post to their UI thread.
     */
    public AutoCloseable addPresenceListener(GroupId groupId, Runnable listener) {
        return requireSession(groupId).runtime().presenceTracker().addListener(listener);
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
        return session == null ? List.of() : peerViews(session.group().id(), true);
    }

    public List<PeerTrustCoordinator.PeerView> peerViews(boolean forceRefresh) {
        GroupSession session = firstSessionOrNull();
        return session == null ? List.of() : peerViews(session.group().id(), forceRefresh);
    }

    public List<PeerTrustCoordinator.PeerView> peerViews(GroupId groupId) {
        return peerViews(groupId, true);
    }

    public List<PeerTrustCoordinator.PeerView> peerViews(GroupId groupId, boolean forceRefresh) {
        GroupSession session = requireSession(groupId);
        List<PeerTrustCoordinator.PeerView> online = session.peerTrust().peerViews(forceRefresh);
        return mergeKnownDevices(session, online);
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
            rememberOnlineDevices(session.group().id(),
                    session.peerTrust().onlinePeers());
        }
    }

    public void reconcileTrustedPeers(GroupId groupId) {
        GroupSession session = requireSession(groupId);
        session.peerTrust().reconcileTrustedPeers();
        rememberOnlineDevices(groupId, session.peerTrust().onlinePeers());
    }

    private List<PeerTrustCoordinator.PeerView> mergeKnownDevices(
            GroupSession session, List<PeerTrustCoordinator.PeerView> online) {
        GroupId groupId = session.group().id();
        storeLock.lock();
        try {
            Set<DeviceId> onlineIds = new HashSet<>();
            for (PeerTrustCoordinator.PeerView view : online) {
                DiscoveredPeer peer = view.presence().peer();
                onlineIds.add(peer.deviceId());
                rememberDeviceIfChangedLocked(groupId, peer, view.presence().lastSeen());
            }
            List<PeerTrustCoordinator.PeerView> merged = new ArrayList<>(online);
            for (GroupDevice device : store.devices().findByGroup(groupId)) {
                if (onlineIds.contains(device.deviceId())) {
                    continue;
                }
                boolean trusted = trustStore.isTrusted(groupId, device.certFingerprint());
                Instant lastSeen = device.lastSeenAt() == null
                        ? device.knownAt()
                        : device.lastSeenAt();
                merged.add(PeerTrustCoordinator.PeerView.offline(groupId, device.deviceId(),
                        device.certFingerprint(), lastSeen, trusted));
            }
            merged.sort(Comparator.comparing(
                    view -> view.presence().peer().deviceId().value()));
            return List.copyOf(merged);
        } finally {
            storeLock.unlock();
        }
    }

    private void rememberOnlineDevices(GroupId groupId,
                                       List<PresenceTracker.Presence> presences) {
        storeLock.lock();
        try {
            for (PresenceTracker.Presence presence : presences) {
                rememberDeviceLocked(groupId, presence.peer(), presence.lastSeen());
            }
        } finally {
            storeLock.unlock();
        }
    }

    private void rememberDeviceLocked(GroupId groupId, DiscoveredPeer peer, Instant lastSeen) {
        GroupDevice existing = store.devices().find(groupId, peer.deviceId()).orElse(null);
        Instant seen = lastSeen == null ? Instant.EPOCH : lastSeen;
        Instant knownAt = existing == null || existing.knownAt() == null
                ? seen
                : existing.knownAt();
        store.devices().upsert(new GroupDevice(groupId, peer.deviceId(),
                existing == null ? null : existing.displayName(), peer.fingerprint(),
                seen, knownAt));
    }

    /**
     * The peer view refresh happens every few seconds; only a new device or a rotated
     * certificate needs a write, the ten-second reconcile updates last-seen.
     */
    private void rememberDeviceIfChangedLocked(GroupId groupId, DiscoveredPeer peer,
                                               Instant lastSeen) {
        GroupDevice existing = store.devices().find(groupId, peer.deviceId()).orElse(null);
        if (existing != null
                && existing.certFingerprint().equalsIgnoreCase(peer.fingerprint())) {
            return;
        }
        rememberDeviceLocked(groupId, peer, lastSeen);
    }

    public void refreshRemoteTrust() {
        for (GroupSession session : sessions) {
            session.peerTrust().refreshRemoteTrust();
        }
    }

    public void refreshRemoteTrust(GroupId groupId) {
        requireSession(groupId).peerTrust().refreshRemoteTrust();
    }

    /**
     * The stored sync rules of one group. They are read from the store on every call so the
     * rules dialog sees a rule added by the CLI or by an earlier window.
     */
    public List<Trigger> triggers(GroupId groupId) {
        requireSession(groupId);
        return storeGuardedUnchecked(storeLock,
                () -> store.triggers().findByGroup(groupId));
    }

    /**
     * Adds an enabled rule and registers it with the running engine immediately.
     */
    public Trigger addTrigger(GroupId groupId, TriggerConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        GroupSession session = requireSession(groupId);
        TriggerId id = new TriggerId(UUID.randomUUID().toString());
        TriggerType type = config instanceof TriggerConfig.Alarm
                ? TriggerType.ALARM
                : TriggerType.CONDITION;
        Instant nextFire = config instanceof TriggerConfig.Alarm alarm
                ? alarm.schedule().nextFire(Instant.now())
                : null;
        Trigger trigger = new Trigger(id, groupId, type, TriggerConfigCodec.encode(config),
                true, nextFire);
        storeGuarded(storeLock, () -> {
            store.triggers().upsert(trigger);
            return null;
        });
        session.triggers().register(trigger);
        return trigger;
    }

    public void deleteTrigger(GroupId groupId, TriggerId id) throws IOException {
        Objects.requireNonNull(id, "id");
        GroupSession session = requireSession(groupId);
        storeGuarded(storeLock, () -> {
            Trigger trigger = store.triggers().find(id)
                    .orElseThrow(() -> new IOException("trigger not found: " + id.value()));
            if (!trigger.groupId().equals(groupId)) {
                throw new IOException("trigger does not belong to this group: " + id.value());
            }
            store.triggers().delete(id);
            return null;
        });
        session.triggers().unregister(id);
    }

    public Trigger setTriggerEnabled(GroupId groupId, TriggerId id, boolean enabled)
            throws IOException {
        Objects.requireNonNull(id, "id");
        GroupSession session = requireSession(groupId);
        Trigger updated = storeGuarded(storeLock, () -> {
            Trigger trigger = store.triggers().find(id)
                    .orElseThrow(() -> new IOException("trigger not found: " + id.value()));
            if (!trigger.groupId().equals(groupId)) {
                throw new IOException("trigger does not belong to this group: " + id.value());
            }
            Instant nextFire = null;
            if (enabled && TriggerConfigCodec.decode(trigger.configJson())
                    instanceof TriggerConfig.Alarm alarm) {
                nextFire = alarm.schedule().nextFire(Instant.now());
            }
            Trigger changed = new Trigger(trigger.id(), trigger.groupId(), trigger.type(),
                    trigger.configJson(), enabled, nextFire);
            store.triggers().upsert(changed);
            return changed;
        });
        if (enabled) {
            session.triggers().register(updated);
        } else {
            session.triggers().unregister(id);
        }
        return updated;
    }

    public List<ConflictResolver.ConflictView> openConflicts() {
        GroupSession session = firstSessionOrNull();
        return session == null ? List.of() : openConflicts(session);
    }

    public List<ConflictResolver.ConflictView> openConflicts(GroupId groupId) {
        return openConflicts(requireSession(groupId));
    }

    public void resolveConflict(ConflictId id, ConflictResolver.Resolution resolution)
            throws IOException {
        resolveConflict(requireFirstSession(), id, resolution);
    }

    public void resolveConflict(GroupId groupId, ConflictId id,
                                ConflictResolver.Resolution resolution) throws IOException {
        resolveConflict(requireSession(groupId), id, resolution);
    }

    public List<VersionHistoryService.ItemView> historyItems() {
        GroupSession session = firstSessionOrNull();
        return session == null ? List.of() : historyItems(session);
    }

    public List<VersionHistoryService.ItemView> historyItems(GroupId groupId) {
        return historyItems(requireSession(groupId));
    }

    public List<FileVersion> versions(ItemId itemId) {
        GroupSession session = firstSessionOrNull();
        return session == null ? List.of() : versions(session, itemId);
    }

    public List<FileVersion> versions(GroupId groupId, ItemId itemId) {
        return versions(requireSession(groupId), itemId);
    }

    public void restoreVersion(ItemId itemId, VersionId versionId) throws IOException {
        restoreVersion(requireFirstSession(), itemId, versionId);
    }

    public void restoreVersion(GroupId groupId, ItemId itemId, VersionId versionId)
            throws IOException {
        restoreVersion(requireSession(groupId), itemId, versionId);
    }

    /**
     * The status of the first group; with no group the installation still reports its node,
     * fingerprint, port and conflicts so the window can open on an empty installation.
     */
    public Status status() {
        return status(firstSessionOrNull());
    }

    /**
     * The status of one configured group.
     *
     * @throws IllegalArgumentException when this installation does not serve {@code groupId}
     */
    public Status status(GroupId groupId) {
        return status(requireSession(groupId));
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

    private Status status(GroupSession session) {
        if (session == null) {
            return new Status(null, nodeId, identity.fingerprint(), config.port(), null, null,
                    openConflictCount(null), deviceError(), null);
        }
        GroupId groupId = session.group().id();
        return storeGuardedUnchecked(storeLock, () -> session.runtime().readSerialized(() -> {
            SyncRun lastRun = store.runs().recent(groupId, 1).stream()
                    .findFirst()
                    .orElse(null);
            return new Status(groupId, nodeId, identity.fingerprint(), port(),
                    session.group().root(), lastRun, openConflictCount(groupId),
                    lastError(session), session.peerTrust().lastWarning());
        }));
    }

    /**
     * Counts the unresolved conflicts of one group. Conflicts are stored per item, so the
     * item decides the group; a null group counts whatever the shared store still holds.
     */
    private int openConflictCount(GroupId groupId) {
        int count = 0;
        for (Conflict conflict : store.conflicts().findOpen(1000)) {
            if (groupId == null) {
                count++;
                continue;
            }
            Item item = store.items().findById(conflict.itemId()).orElse(null);
            if (item != null && item.groupId().equals(groupId)) {
                count++;
            }
        }
        return count;
    }

    private List<ConflictResolver.ConflictView> openConflicts(GroupSession session) {
        return storeGuardedUnchecked(storeLock,
                () -> session.runtime().readSerialized(() -> session.conflicts().open(200)));
    }

    private void resolveConflict(GroupSession session, ConflictId id,
                                 ConflictResolver.Resolution resolution) throws IOException {
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

    private List<VersionHistoryService.ItemView> historyItems(GroupSession session) {
        return storeGuardedUnchecked(storeLock,
                () -> session.runtime().readSerialized(() -> session.history().items(200)));
    }

    private List<FileVersion> versions(GroupSession session, ItemId itemId) {
        return storeGuardedUnchecked(storeLock,
                () -> session.runtime().readSerialized(() -> session.history().versions(itemId)));
    }

    private void restoreVersion(GroupSession session, ItemId itemId, VersionId versionId)
            throws IOException {
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
