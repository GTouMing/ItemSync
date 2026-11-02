package github.gtouming.itemsync.sync.engine.node;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
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
import github.gtouming.itemsync.sync.engine.transport.RemoteSyncServer;
import github.gtouming.itemsync.sync.engine.transport.TlsRemotePeer;
import github.gtouming.itemsync.sync.plan.SyncPlanner;
import github.gtouming.itemsync.sync.state.LocalReconciler;
import github.gtouming.itemsync.sync.trigger.TriggerEngine;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FrameCodec;
import github.gtouming.itemsync.transport.frame.FramePayloads;
import github.gtouming.itemsync.transport.frame.FrameType;
import github.gtouming.itemsync.transport.frame.Notify;
import github.gtouming.itemsync.transport.frame.NotifyAck;
import github.gtouming.itemsync.transport.discovery.JmDnsPeerDiscovery;
import github.gtouming.itemsync.transport.discovery.PeerAdvertisement;
import github.gtouming.itemsync.transport.discovery.PresenceTracker;
import github.gtouming.itemsync.transport.net.FrameConnection;
import github.gtouming.itemsync.transport.net.TlsTransportServer;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

public final class SyncNode implements AutoCloseable {

    private final NodeId nodeId;
    private final GroupId groupId;
    private final SyncRootId rootId;
    private final TlsIdentity identity;
    private final Path transferTempDir;
    private final LocalReconciler reconciler;
    private final SyncEngine engine;
    private final RemoteSyncServer remoteSyncServer;
    private final TlsTransportServer server;
    private final PresenceTracker presence;
    private final JmDnsPeerDiscovery discovery;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "itemsync-sync-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ReentrantLock syncLock = new ReentrantLock();
    private final Object cacheLock = new Object();
    private final Map<NodeId, PeerLink> peers = new ConcurrentHashMap<>();
    private final Map<String, PeerLink> peersByFingerprint = new ConcurrentHashMap<>();

    private volatile List<ManifestEntry> manifestCache = Compat.list();
    private volatile LogicalClock clock = LogicalClock.ZERO;
    private volatile IOException lastError;

    public SyncNode(NodeId nodeId, GroupId groupId, SyncRootId rootId, Path syncRoot,
                    MetadataStore store, BlobStore blobs, PhysicalPathResolver paths,
                    TlsIdentity identity, DeviceId deviceId, String expectedClientFingerprint,
                    int port, Path transferTempDir) throws IOException {
        this(nodeId, groupId, rootId, syncRoot, store, blobs, paths, identity, deviceId,
                Compat.set(expectedClientFingerprint), port, transferTempDir);
    }

    public SyncNode(NodeId nodeId, GroupId groupId, SyncRootId rootId, Path syncRoot,
                    MetadataStore store, BlobStore blobs, PhysicalPathResolver paths,
                    TlsIdentity identity, DeviceId deviceId, Set<String> expectedClientFingerprints,
                    int port, Path transferTempDir) throws IOException {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.rootId = Objects.requireNonNull(rootId, "rootId");
        this.identity = Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(syncRoot, "syncRoot");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(blobs, "blobs");
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(expectedClientFingerprints, "expectedClientFingerprints");
        if (expectedClientFingerprints.isEmpty()) {
            throw new IllegalArgumentException("expected client fingerprints must not be empty");
        }
        Path tempDir = Objects.requireNonNull(transferTempDir, "transferTempDir");
        Files.createDirectories(tempDir);

        this.reconciler = new LocalReconciler(syncRoot, store, blobs, rootId, nodeId, paths, Instant::now);
        LocalApplier applier = new LocalApplier(syncRoot, store, blobs, paths, rootId, nodeId,
                deviceId, Instant::now);
        this.engine = new SyncEngine(reconciler, applier, new SyncPlanner());
        this.remoteSyncServer = new RemoteSyncServer(rootId,
                (requestedRoot, since) -> manifestCache,
                BlobSources.of(blobs));
        this.reconcileAndCache();
        this.server = new TlsTransportServer(identity, expectedClientFingerprints, port,
                this::onInboundFrame);
        this.server.start();
        this.transferTempDir = tempDir;

        PresenceTracker presenceTracker = new PresenceTracker(groupId, nodeId, Instant::now);
        JmDnsPeerDiscovery peerDiscovery = null;
        try {
            peerDiscovery = new JmDnsPeerDiscovery();
            peerDiscovery.addListener(presenceTracker);
            peerDiscovery.start();
            peerDiscovery.advertise(new PeerAdvertisement(nodeId, deviceId, groupId,
                    FrameCodec.PROTOCOL_VERSION, identity.fingerprint(), server.port()));
        } catch (IOException | RuntimeException e) {
            if (peerDiscovery != null) {
                peerDiscovery.close();
                peerDiscovery = null;
            }
        }
        this.presence = presenceTracker;
        this.discovery = peerDiscovery;
    }

    public List<PresenceTracker.Presence> onlinePeers() {
        return presence.online();
    }

    public int port() {
        return server.port();
    }

    public NodeId nodeId() {
        return nodeId;
    }

    public IOException lastError() {
        return lastError;
    }

    public void addPeer(NodeId peerNodeId, String host, int port,
                        String expectedServerFingerprint) throws IOException {
        TlsRemotePeer remote = TlsRemotePeer.connect(host, port, identity,
                expectedServerFingerprint, peerNodeId, transferTempDir);
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
            reconcileAndCache();
            for (PeerLink link : peers.values()) {
                sendNotify(link);
            }
        } finally {
            syncLock.unlock();
        }
    }

    public List<SyncRunResult> syncNow() throws IOException {
        syncLock.lock();
        try {
            reconcileAndCache();
            Map<PeerLink, SyncRunResult> results = new LinkedHashMap<>();
            for (PeerLink link : Compat.copyList(peers.values())) {
                results.put(link, engine.pullOnce(link.remote));
            }
            reconcileAndCache();
            for (Map.Entry<PeerLink, SyncRunResult> entry : results.entrySet()) {
                if (entry.getValue().pendingPush() > 0) {
                    sendNotify(entry.getKey());
                }
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

    @Override
    public void close() {
        for (PeerLink link : Compat.copyList(peers.values())) {
            link.remote.close();
        }
        peers.clear();
        peersByFingerprint.clear();
        if (discovery != null) {
            discovery.close();
        }
        server.close();
        worker.shutdownNow();
        try {
            worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void onInboundFrame(FrameConnection connection, Frame frame) {
        if (frame.type() == FrameType.NOTIFY) {
            handleNotify(connection, frame);
        } else {
            remoteSyncServer.onFrame(connection, frame);
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
        boolean accepted = link != null && notify.rootId().equals(rootId);
        sendAck(connection, notify.runId(), accepted, accepted ? 0 : -1);
        if (!accepted) {
            return;
        }
        worker.execute(() -> pullAfterNotify(link));
    }

    private void pullAfterNotify(PeerLink link) {
        syncLock.lock();
        try {
            reconcileAndCache();
            SyncRunResult result = engine.pullOnce(link.remote);
            reconcileAndCache();
            if (result.pendingPush() > 0) {
                sendNotify(link);
            }
        } catch (IOException e) {
            lastError = e;
        } finally {
            syncLock.unlock();
        }
    }

    private void reconcileAndCache() throws IOException {
        synchronized (cacheLock) {
            manifestCache = reconciler.reconcile();
        }
    }

    private void sendNotify(PeerLink link) {
        try {
            link.remote.send(new Frame(FrameType.NOTIFY, FramePayloads.encodeNotify(new Notify(
                    new SyncRunId(UUID.randomUUID().toString()), groupId, nextClock(), rootId,
                    VersionVector.empty()))));
        } catch (IOException e) {
            lastError = e;
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

    private record PeerLink(NodeId nodeId, TlsRemotePeer remote, String fingerprint) {
    }
}
