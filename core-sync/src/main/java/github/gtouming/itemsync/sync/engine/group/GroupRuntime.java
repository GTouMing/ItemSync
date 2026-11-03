package github.gtouming.itemsync.sync.engine.group;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.domain.replication.LogicalClock;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.blob.BlobStore;
import github.gtouming.itemsync.store.metadata.MetadataStore;
import github.gtouming.itemsync.sync.apply.LocalApplier;
import github.gtouming.itemsync.sync.apply.PhysicalPathResolver;
import github.gtouming.itemsync.sync.engine.SyncEngine;
import github.gtouming.itemsync.sync.engine.SyncRunResult;
import github.gtouming.itemsync.sync.engine.transport.BlobSources;
import github.gtouming.itemsync.sync.engine.transport.ManifestSnapshot;
import github.gtouming.itemsync.sync.engine.transport.RemoteSyncServer;
import github.gtouming.itemsync.sync.engine.transport.TlsRemotePeer;
import github.gtouming.itemsync.sync.plan.SyncPlanner;
import github.gtouming.itemsync.sync.state.LocalReconciler;
import github.gtouming.itemsync.sync.trust.PeerGroup;
import github.gtouming.itemsync.sync.trigger.TriggerEngine;
import github.gtouming.itemsync.transport.discovery.PresenceTracker;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.frame.Notify;
import github.gtouming.itemsync.transport.frame.NotifyAck;
import github.gtouming.itemsync.transport.net.FrameConnection;
import github.gtouming.itemsync.transport.net.TlsTransportServer;
import github.gtouming.itemsync.transport.tls.GroupPinnedTrustManager;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Everything one sync group needs while it is online: its root, engine, peers, locks,
 * manifest cache and presence, with no access to another group's state.
 *
 * <p>The TLS sync server, its group pinned trust manager, the pairing endpoint and the
 * mDNS advertisement belong to the device ({@code SyncDevice}) and are shared by every
 * group of this node (ADR-0036). A group only routes the connections the device assigned
 * to it and asks the device to allow, revoke or report inbound connections of its own
 * group; closing a group never closes the device.
 */
public final class GroupRuntime implements AutoCloseable, PeerGroup {

    /**
     * The device level collaborators shared by every group of one running node.
     */
    public record DeviceContext(NodeId nodeId, DeviceId deviceId, TlsIdentity identity,
                                MetadataStore store, BlobStore blobs, PhysicalPathResolver paths,
                                TlsTransportServer server, GroupPinnedTrustManager trustManager,
                                Path transferTempDir) {

        public DeviceContext {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(blobs, "blobs");
            Objects.requireNonNull(paths, "paths");
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(trustManager, "trustManager");
            Objects.requireNonNull(transferTempDir, "transferTempDir");
        }
    }

    private final GroupId groupId;
    private final String displayName;
    private final Path root;
    private final NodeId nodeId;
    private final DeviceId deviceId;
    private final TlsIdentity identity;
    private final MetadataStore store;
    private final TlsTransportServer server;
    private final GroupPinnedTrustManager trustManager;
    private final Path transferTempDir;
    private final LocalReconciler reconciler;
    private final SyncEngine engine;
    private final RemoteSyncServer remoteSyncServer;
    private final PresenceTracker presence;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "itemsync-sync-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ReentrantLock syncLock = new ReentrantLock();
    private final ReentrantReadWriteLock manifestLock = new ReentrantReadWriteLock();
    private final Map<NodeId, PeerLink> peers = new ConcurrentHashMap<>();
    private final Map<String, PeerLink> peersByFingerprint = new ConcurrentHashMap<>();
    private final Map<LogicalPath, VersionedEntry> manifestEntries = new ConcurrentHashMap<>();

    private volatile long manifestSequence;
    private volatile LogicalClock clock = LogicalClock.ZERO;
    private volatile IOException lastError;

    public GroupRuntime(GroupId groupId, String displayName, Path root, DeviceContext device)
            throws IOException {
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.displayName = displayName;
        this.root = Objects.requireNonNull(root, "root");
        Objects.requireNonNull(device, "device");
        this.nodeId = device.nodeId();
        this.deviceId = device.deviceId();
        this.identity = device.identity();
        this.store = device.store();
        this.server = device.server();
        this.trustManager = device.trustManager();
        this.transferTempDir = device.transferTempDir();
        BlobStore blobs = device.blobs();
        PhysicalPathResolver paths = device.paths();

        this.reconciler = new LocalReconciler(root, store, blobs, groupId, nodeId, paths, Instant::now);
        LocalApplier applier = new LocalApplier(root, store, blobs, paths, groupId, nodeId,
                deviceId, Instant::now);
        this.engine = new SyncEngine(reconciler, applier, new SyncPlanner(), store.cursors());
        this.remoteSyncServer = new RemoteSyncServer(groupId,
                (requestedGroup, sinceSequence) -> manifestSnapshot(sinceSequence),
                BlobSources.of(blobs));
        this.presence = new PresenceTracker(groupId, nodeId, Instant::now);
        reconciler.reconcile();
        refreshManifestCache();
    }

    @Override
    public GroupId groupId() {
        return groupId;
    }

    /**
     * The group name shown on this device only; the name is never synced (ADR-0035).
     */
    public String displayName() {
        return displayName;
    }

    public Path root() {
        return root;
    }

    /**
     * The presence tracker of this group; the device registers it as the discovery listener
     * that fills this group's online peers.
     */
    public PresenceTracker presenceTracker() {
        return presence;
    }

    @Override
    public List<PresenceTracker.Presence> onlinePeers() {
        return presence.online();
    }

    @Override
    public void allowClient(String fingerprint) {
        trustManager.allow(groupId, fingerprint);
    }

    @Override
    public void revokeClient(String fingerprint) {
        trustManager.revoke(groupId, fingerprint);
        // group scoped: revoking this group must not close another group's connections
        server.closeConnections(groupId, fingerprint);
    }

    @Override
    public boolean hasInboundConnection(String fingerprint) {
        return server.hasConnection(groupId, fingerprint);
    }

    @Override
    public void removePeer(NodeId peerNodeId) {
        PeerLink link = peers.remove(peerNodeId);
        if (link != null) {
            peersByFingerprint.remove(link.fingerprint(), link);
            link.remote().close();
        }
    }

    @Override
    public boolean isConnected(NodeId peerNodeId) {
        PeerLink link = peers.get(peerNodeId);
        return link != null && link.remote.isConnected();
    }

    public IOException lastError() {
        return lastError;
    }

    @Override
    public void addPeer(NodeId peerNodeId, String host, int port,
                        String expectedServerFingerprint) throws IOException {
        TlsRemotePeer remote = TlsRemotePeer.connect(host, port, identity,
                expectedServerFingerprint, groupId, peerNodeId, transferTempDir);
        PeerLink link = new PeerLink(peerNodeId, remote, expectedServerFingerprint);
        PeerLink previous = peers.put(peerNodeId, link);
        peersByFingerprint.put(expectedServerFingerprint, link);
        if (previous != null) {
            peersByFingerprint.remove(previous.fingerprint(), previous);
            previous.remote.close();
        }
    }

    public void notifyPeers() throws IOException {
        syncLock.lock();
        try {
            reconcileLocal();
            long localSequence = store.items().currentSequence(groupId);
            for (PeerLink link : Compat.copyList(peers.values())) {
                notifyIfNeeded(link, localSequence);
            }
        } finally {
            syncLock.unlock();
        }
    }

    public List<SyncRunResult> syncNow() throws IOException {
        syncLock.lock();
        try {
            reconcileLocal();
            long localSequence = store.items().currentSequence(groupId);
            Map<PeerLink, SyncRunResult> results = new LinkedHashMap<>();
            for (PeerLink link : Compat.copyList(peers.values())) {
                results.put(link, engine.pullOnce(link.remote));
            }
            reconcileLocal();
            for (PeerLink link : results.keySet()) {
                notifyIfNeeded(link, localSequence);
            }
            return Compat.copyList(results.values());
        } finally {
            syncLock.unlock();
        }
    }

    public List<SyncRunResult> runSerialized(TriggerEngine.SyncRunner work) throws IOException {
        syncLock.lock();
        try {
            return work.run();
        } finally {
            syncLock.unlock();
        }
    }

    public <T> T readSerialized(java.util.function.Supplier<T> work) {
        syncLock.lock();
        try {
            return work.get();
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * Handles one inbound frame of a connection the device assigned to this group.
     */
    public void onInboundFrame(FrameConnection connection, Frame frame) {
        if (frame.type() == FrameType.NOTIFY) {
            handleNotify(connection, frame);
        } else {
            remoteSyncServer.onFrame(connection, frame);
        }
    }

    public void refreshManifest() {
        syncLock.lock();
        try {
            refreshManifestCache();
        } finally {
            syncLock.unlock();
        }
    }

    public ManifestSnapshot manifestSnapshot(long sinceSequence) {
        manifestLock.readLock().lock();
        try {
            long sequence = manifestSequence;
            long since = sinceSequence > sequence ? 0 : sinceSequence;
            List<ManifestEntry> entries = new ArrayList<>();
            for (VersionedEntry value : manifestEntries.values()) {
                if (value.sequence() > since && value.sequence() <= sequence) {
                    entries.add(value.entry());
                }
            }
            return new ManifestSnapshot(Compat.copyList(entries), sequence);
        } finally {
            manifestLock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        for (PeerLink link : Compat.copyList(peers.values())) {
            link.remote.close();
        }
        peers.clear();
        peersByFingerprint.clear();
        worker.shutdownNow();
        try {
            worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleNotify(FrameConnection connection, Frame frame) {
        Notify notify;
        try {
            notify = FramePayloads.decodeNotify(frame.payload());
        } catch (RuntimeException e) {
            return;
        }
        PeerLink link = peersByFingerprint.get(connection.peerFingerprint());
        boolean accepted = link != null && notify.groupId().equals(groupId);
        sendAck(connection, notify.runId(), accepted, accepted ? 0 : -1);
        if (!accepted) {
            return;
        }
        worker.execute(() -> pullAfterNotify(link));
    }

    private void pullAfterNotify(PeerLink link) {
        syncLock.lock();
        try {
            reconcileLocal();
            long localSequence = store.items().currentSequence(groupId);
            engine.pullOnce(link.remote);
            reconcileLocal();
            notifyIfNeeded(link, localSequence);
        } catch (IOException e) {
            lastError = e;
        } finally {
            syncLock.unlock();
        }
    }

    private void reconcileLocal() throws IOException {
        reconciler.reconcile();
        refreshManifestCache();
    }

    private void refreshManifestCache() {
        manifestLock.writeLock().lock();
        try {
            long current = store.items().currentSequence(groupId);
            long since = manifestSequence;
            if (current < since) {
                manifestEntries.clear();
                since = 0;
            }
            if (current > since) {
                for (MetadataStore.ItemRepository.ChangedItem changed
                        : store.items().changedSince(groupId, since, current)) {
                    ManifestEntry entry = reconciler.toEntry(changed.item());
                    if (entry == null) {
                        manifestEntries.remove(changed.item().logicalPath());
                    } else {
                        manifestEntries.put(entry.path(),
                                new VersionedEntry(entry, changed.sequence()));
                    }
                }
            }
            manifestSequence = current;
        } finally {
            manifestLock.writeLock().unlock();
        }
    }

    private void notifyIfNeeded(PeerLink link, long localSequence) {
        if (localSequence <= store.cursors().pushSequence(groupId, link.nodeId)) {
            return;
        }
        if (sendNotify(link)) {
            store.cursors().updatePush(groupId, link.nodeId, localSequence);
        }
    }

    private boolean sendNotify(PeerLink link) {
        try {
            link.remote.send(new Frame(FrameType.NOTIFY, FramePayloads.encodeNotify(new Notify(
                    new SyncRunId(UUID.randomUUID().toString()), groupId, nextClock(),
                    VersionVector.empty()))));
            return true;
        } catch (IOException e) {
            lastError = e;
            return false;
        }
    }

    private void sendAck(FrameConnection connection, SyncRunId runId, boolean accepted, int position) {
        try {
            connection.send(new Frame(FrameType.NOTIFY_ACK,
                    FramePayloads.encodeNotifyAck(new NotifyAck(runId, accepted, position))));
        } catch (RuntimeException ignored) {
            // the connection is already broken
        }
    }

    private synchronized LogicalClock nextClock() {
        clock = clock.tick();
        return clock;
    }

    private record VersionedEntry(ManifestEntry entry, long sequence) {
    }

    private record PeerLink(NodeId nodeId, TlsRemotePeer remote, String fingerprint) {
    }
}
