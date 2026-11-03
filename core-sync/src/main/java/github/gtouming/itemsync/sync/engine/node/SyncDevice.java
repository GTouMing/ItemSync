package github.gtouming.itemsync.sync.engine.node;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.store.blob.BlobStore;
import github.gtouming.itemsync.store.metadata.MetadataStore;
import github.gtouming.itemsync.sync.apply.PhysicalPathResolver;
import github.gtouming.itemsync.sync.engine.group.GroupRuntime;
import github.gtouming.itemsync.transport.discovery.JmDnsPeerDiscovery;
import github.gtouming.itemsync.transport.discovery.PeerAdvertisement;
import github.gtouming.itemsync.transport.frame.Frame;
import github.gtouming.itemsync.transport.frame.FrameCodec;
import github.gtouming.itemsync.transport.net.FrameConnection;
import github.gtouming.itemsync.transport.net.HeartbeatHandler;
import github.gtouming.itemsync.transport.net.TlsTransportServer;
import github.gtouming.itemsync.transport.pairing.PairingStatusServer;
import github.gtouming.itemsync.transport.pairing.PairingTrustManager;
import github.gtouming.itemsync.transport.pairing.PairingTrustProvider;
import github.gtouming.itemsync.transport.tls.GroupPinnedTrustManager;
import github.gtouming.itemsync.transport.tls.PinnedTrustManager;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The device level part of a running node: one TLS sync server on the shared port, one
 * group pinned trust manager, one pairing endpoint, one mDNS advertisement listing every
 * enabled group and one {@link GroupRuntime} per group (ADR-0036).
 *
 * <p>Inbound connections belong to the group their TLS SNI named; a frame is only ever
 * routed to that group's runtime. A connection without a known SNI group is closed instead
 * of falling back to another group.
 */
public final class SyncDevice implements AutoCloseable {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    private static final Duration PAIRING_READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * One group this device serves: its id, an optional name shown on this device only,
     * the local root and the pins and pairing trust of that group.
     */
    public record GroupConfig(GroupId groupId, String displayName, Path root,
                             Set<String> expectedClientFingerprints,
                             PairingTrustProvider pairingTrust) {

        public GroupConfig {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(root, "root");
            expectedClientFingerprints =
                    Compat.copySet(Objects.requireNonNull(expectedClientFingerprints,
                            "expectedClientFingerprints"));
        }

        public GroupConfig(GroupId groupId, Path root, Set<String> expectedClientFingerprints) {
            this(groupId, null, root, expectedClientFingerprints, null);
        }
    }

    private final NodeId nodeId;
    private final DeviceId deviceId;
    private final TlsIdentity identity;
    private final GroupPinnedTrustManager trustManager;
    private final TlsTransportServer server;
    private final TlsTransportServer pairingServer;
    private final int pairingPort;
    private final JmDnsPeerDiscovery discovery;
    private final boolean discoveryAvailable;
    private final Exception discoveryError;
    private final PeerAdvertisement advertisement;
    private final Map<GroupId, GroupRuntime> groups;

    private volatile IOException lastError;
    private volatile String lastRejectedFingerprint;

    public SyncDevice(NodeId nodeId, DeviceId deviceId, TlsIdentity identity,
                      MetadataStore store, BlobStore blobs, PhysicalPathResolver paths,
                      List<GroupConfig> groups, int port, Path transferTempDir,
                      InetAddress discoveryAddress) throws IOException {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.identity = Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(blobs, "blobs");
        Objects.requireNonNull(paths, "paths");
        List<GroupConfig> configs = validate(groups);
        Path tempDir = Objects.requireNonNull(transferTempDir, "transferTempDir");
        Files.createDirectories(tempDir);

        Map<GroupId, Set<String>> pinsByGroup = new LinkedHashMap<>();
        Set<GroupId> groupIds = new LinkedHashSet<>();
        for (GroupConfig config : configs) {
            groupIds.add(config.groupId());
            pinsByGroup.put(config.groupId(), config.expectedClientFingerprints());
        }
        this.trustManager = new GroupPinnedTrustManager(Compat.copyMap(pinsByGroup));

        TlsTransportServer syncServer = new TlsTransportServer(identity, trustManager, port,
                new HeartbeatHandler(this::onInboundFrame), this::onInboundHandshakeFailure,
                HEARTBEAT_INTERVAL);
        Map<GroupId, GroupRuntime> runtimes = new LinkedHashMap<>();
        TlsTransportServer pairing = null;
        int localPairingPort = 0;
        JmDnsPeerDiscovery peerDiscovery = null;
        boolean discoveryReady = false;
        Exception discoveryFailure = null;
        try {
            GroupRuntime.DeviceContext context = new GroupRuntime.DeviceContext(nodeId, deviceId,
                    identity, store, blobs, paths, syncServer, trustManager, tempDir);
            for (GroupConfig config : configs) {
                runtimes.put(config.groupId(), new GroupRuntime(config.groupId(),
                        config.displayName(), config.root(), context));
            }
            // assigned before the server accepts: every inbound frame needs the group map
            this.groups = Compat.copyMap(runtimes);
            this.server = syncServer;
            syncServer.start();

            pairing = startPairingServer(configs);
            localPairingPort = pairing == null ? 0 : pairing.port();

            this.advertisement = new PeerAdvertisement(nodeId, deviceId,
                    Compat.copySet(groupIds), FrameCodec.PROTOCOL_VERSION, identity.fingerprint(),
                    syncServer.port(), localPairingPort);
            try {
                peerDiscovery = new JmDnsPeerDiscovery(discoveryAddress);
                for (GroupRuntime runtime : runtimes.values()) {
                    peerDiscovery.addListener(runtime.presenceTracker());
                }
                peerDiscovery.start();
                peerDiscovery.advertise(advertisement);
                discoveryReady = true;
            } catch (IOException | RuntimeException e) {
                discoveryFailure = e;
                if (peerDiscovery != null) {
                    peerDiscovery.close();
                    peerDiscovery = null;
                }
            }
        } catch (IOException | RuntimeException e) {
            if (peerDiscovery != null) {
                peerDiscovery.close();
            }
            if (pairing != null) {
                pairing.close();
            }
            for (GroupRuntime runtime : runtimes.values()) {
                runtime.close();
            }
            syncServer.close();
            throw e;
        }
        this.pairingServer = pairing;
        this.pairingPort = localPairingPort;
        this.discovery = peerDiscovery;
        this.discoveryAvailable = discoveryReady;
        this.discoveryError = discoveryFailure;
    }

    public NodeId nodeId() {
        return nodeId;
    }

    public DeviceId deviceId() {
        return deviceId;
    }

    public int port() {
        return server.port();
    }

    public int pairingPort() {
        return pairingPort;
    }

    public Map<GroupId, GroupRuntime> groups() {
        return Compat.copyMap(groups);
    }

    /**
     * Returns the runtime of {@code groupId}, or {@code null} when this device does not
     * serve that group.
     */
    public GroupRuntime group(GroupId groupId) {
        Objects.requireNonNull(groupId, "groupId");
        return groups.get(groupId);
    }

    public boolean discoveryAvailable() {
        return discoveryAvailable;
    }

    public Exception discoveryError() {
        return discoveryError;
    }

    /**
     * The one advertisement this device publishes for all of its groups. It is built even
     * when mDNS could not start; {@link #discoveryAvailable()} tells whether it was
     * actually published.
     */
    public PeerAdvertisement advertisement() {
        return advertisement;
    }

    /**
     * The last inbound failure of the shared sync port. Handshake failures cannot be
     * attributed to a group, so they are reported here instead of on a group.
     */
    public IOException lastError() {
        return lastError;
    }

    public void allowClient(GroupId groupId, String fingerprint) {
        requireGroup(groupId);
        trustManager.allow(groupId, fingerprint);
        if (lastRejectedFingerprint != null
                && lastRejectedFingerprint.equalsIgnoreCase(fingerprint)) {
            lastRejectedFingerprint = null;
            lastError = null;
        }
    }

    public void revokeClient(GroupId groupId, String fingerprint) {
        requireGroup(groupId);
        trustManager.revoke(groupId, fingerprint);
        // group scoped: revoking one group must not close another group's connections
        server.closeConnections(groupId, fingerprint);
    }

    public boolean hasInboundConnection(GroupId groupId, String fingerprint) {
        requireGroup(groupId);
        return server.hasConnection(groupId, fingerprint);
    }

    @Override
    public void close() {
        for (GroupRuntime runtime : groups.values()) {
            runtime.close();
        }
        if (discovery != null) {
            discovery.close();
        }
        if (pairingServer != null) {
            pairingServer.close();
        }
        server.close();
    }

    private void onInboundFrame(FrameConnection connection, Frame frame) {
        GroupId groupId = connection.requestedGroup().orElse(null);
        GroupRuntime runtime = groupId == null ? null : groups.get(groupId);
        if (runtime == null) {
            // the handshake already rejects an unknown SNI group; never fall back to another
            connection.close();
            lastError = new IOException(
                    "inbound frame on a connection without a known sync group");
            return;
        }
        if (lastRejectedFingerprint != null
                && lastRejectedFingerprint.equalsIgnoreCase(connection.peerFingerprint())) {
            lastRejectedFingerprint = null;
            lastError = null;
        }
        runtime.onInboundFrame(connection, frame);
    }

    private void onInboundHandshakeFailure(IOException failure) {
        PinnedTrustManager.FingerprintMismatchException mismatch =
                PinnedTrustManager.findFingerprintMismatch(failure);
        if (mismatch != null) {
            lastRejectedFingerprint = mismatch.actualFingerprint();
        }
        lastError = failure;
    }

    private TlsTransportServer startPairingServer(List<GroupConfig> configs) {
        Map<GroupId, PairingTrustProvider> trustByGroup = new LinkedHashMap<>();
        for (GroupConfig config : configs) {
            if (config.pairingTrust() != null) {
                trustByGroup.put(config.groupId(), config.pairingTrust());
            }
        }
        if (trustByGroup.isEmpty()) {
            return null;
        }
        TlsTransportServer pairing = null;
        try {
            pairing = new TlsTransportServer(identity, new PairingTrustManager(), 0,
                    new PairingStatusServer(Compat.copyMap(trustByGroup), nodeId, deviceId,
                            identity.fingerprint()),
                    null, PAIRING_READ_TIMEOUT);
            pairing.start();
            return pairing;
        } catch (IOException | RuntimeException e) {
            if (pairing != null) {
                pairing.close();
            }
            return null;
        }
    }

    private void requireGroup(GroupId groupId) {
        Objects.requireNonNull(groupId, "groupId");
        if (!groups.containsKey(groupId)) {
            throw new IllegalArgumentException(
                    "this device does not serve the sync group " + groupId.value());
        }
    }

    private static List<GroupConfig> validate(List<GroupConfig> groups) {
        Objects.requireNonNull(groups, "groups");
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("a sync device must serve at least one group");
        }
        Set<GroupId> groupIds = new LinkedHashSet<>();
        for (GroupConfig config : groups) {
            Objects.requireNonNull(config, "group config");
            if (!groupIds.add(config.groupId())) {
                throw new IllegalArgumentException(
                        "duplicate sync group: " + config.groupId().value());
            }
        }
        return Compat.copyList(groups);
    }
}
