package github.gtouming.itemsync.sync.trust;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.sync.engine.node.SyncNode;
import github.gtouming.itemsync.transport.discovery.DiscoveredPeer;
import github.gtouming.itemsync.transport.discovery.PresenceTracker;
import github.gtouming.itemsync.transport.net.PeerCertificateMismatchException;
import github.gtouming.itemsync.transport.pairing.PairingStatusClient;
import github.gtouming.itemsync.transport.pairing.PairingStatusException;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

public final class PeerTrustCoordinator implements AutoCloseable {

    private static final Duration REMOTE_TRUST_TTL = Duration.ofSeconds(30);
    private static final Duration REMOTE_TRUST_FAILURE_TTL = Duration.ofSeconds(5);
    private static final Duration PAIRING_QUERY_TIMEOUT = Duration.ofSeconds(2);

    private final GroupId group;
    private final PeerGroup peerGroup;
    private final PeerTrustStore trustStore;
    private final ConnectionProbe connectionProbe;
    private final PairingQuery pairingQuery;
    private final RemoteTrustCache remoteTrustCache;
    private final ExecutorService statusExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "itemsync-pairing-status");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String lastWarning;

    public PeerTrustCoordinator(GroupId group, TlsIdentity identity, SyncNode node,
                                PeerTrustStore trustStore) {
        this(group, node, trustStore, probe(node), query(identity, group), Instant::now);
    }

    /**
     * Coordinates the trust of one group of a running device, whatever shape that group has
     * (a single group {@link SyncNode} or one {@code GroupRuntime} of a multi-group device).
     */
    public PeerTrustCoordinator(TlsIdentity identity, PeerGroup peerGroup,
                                PeerTrustStore trustStore) {
        this(groupIdOf(peerGroup), peerGroup, trustStore, probe(peerGroup),
                query(identity, groupIdOf(peerGroup)), Instant::now);
    }

    PeerTrustCoordinator(GroupId group, SyncNode node, PeerTrustStore trustStore,
                         ConnectionProbe connectionProbe, PairingQuery pairingQuery,
                         Supplier<Instant> clock) {
        this(group, (PeerGroup) node, trustStore, connectionProbe, pairingQuery, clock);
    }

    private PeerTrustCoordinator(GroupId group, PeerGroup peerGroup, PeerTrustStore trustStore,
                                 ConnectionProbe connectionProbe, PairingQuery pairingQuery,
                                 Supplier<Instant> clock) {
        this.group = Objects.requireNonNull(group, "group");
        this.peerGroup = peerGroup;
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
        this.connectionProbe = Objects.requireNonNull(connectionProbe, "connectionProbe");
        this.pairingQuery = Objects.requireNonNull(pairingQuery, "pairingQuery");
        this.remoteTrustCache = new RemoteTrustCache(Objects.requireNonNull(clock, "clock"));
    }

    public List<PresenceTracker.Presence> onlinePeers() {
        List<PresenceTracker.Presence> peers = peerGroup.onlinePeers();
        pruneCache(peers);
        for (PresenceTracker.Presence presence : peers) {
            if (isTrusted(presence.peer())) {
                try {
                    connect(presence.peer());
                } catch (PeerCertificateMismatchException e) {
                    handleCertificateMismatch(presence.peer());
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
        return Compat.copyList(views);
    }

    public boolean isTrusted(DiscoveredPeer peer) {
        return trustStore.isTrusted(group, peer.fingerprint());
    }

    public boolean isConnected(DiscoveredPeer peer) {
        return connectionProbe.isConnected(peer);
    }

    public String lastWarning() {
        return lastWarning;
    }

    public void clearWarning() {
        lastWarning = null;
    }

    public void trustPeer(DiscoveredPeer peer) throws IOException {
        trustStore.trust(group, peer.fingerprint());
        remoteTrustCache.invalidate(peer.fingerprint());
        peerGroup.allowClient(peer.fingerprint());
        try {
            connect(peer);
            lastWarning = null;
        } catch (PeerCertificateMismatchException e) {
            handleCertificateMismatch(peer);
            throw e;
        }
    }

    public void untrustPeer(DiscoveredPeer peer) throws IOException {
        trustStore.untrust(group, peer.fingerprint());
        remoteTrustCache.invalidate(peer.fingerprint());
        peerGroup.revokeClient(peer.fingerprint());
        peerGroup.removePeer(peer.nodeId());
        lastWarning = null;
    }

    public void reconcileTrustedPeers() {
        List<PresenceTracker.Presence> peers = peerGroup.onlinePeers();
        pruneCache(peers);
        for (PresenceTracker.Presence presence : peers) {
            if (isTrusted(presence.peer())) {
                try {
                    connect(presence.peer());
                } catch (PeerCertificateMismatchException e) {
                    handleCertificateMismatch(presence.peer());
                } catch (IOException ignored) {
                    // retry on the next background pass
                }
            }
        }
    }

    public void refreshRemoteTrust() {
        List<PresenceTracker.Presence> peers = peerGroup.onlinePeers();
        pruneCache(peers);
        for (PresenceTracker.Presence presence : peers) {
            if (presence.peer().pairingPort() <= 0) {
                continue;
            }
            try {
                statusExecutor.execute(() -> remoteTrust(presence.peer(), false));
            } catch (RejectedExecutionException ignored) {
                // coordinator is closing
            }
        }
    }

    @Override
    public void close() {
        statusExecutor.shutdownNow();
    }

    PeerView viewOf(PresenceTracker.Presence presence, boolean forceRefresh) {
        DiscoveredPeer peer = presence.peer();
        boolean connected = connectionProbe.isConnected(peer);
        if (connected) {
            return new PeerView(group, presence, isTrusted(peer), RemoteTrust.TRUSTED, true,
                    null, null);
        }
        RemoteTrustOutcome outcome = remoteTrust(peer, forceRefresh);
        return new PeerView(group, presence, isTrusted(peer), outcome.trust(), false,
                outcome.failure(), outcome.detail());
    }

    private RemoteTrustOutcome remoteTrust(DiscoveredPeer peer, boolean forceRefresh) {
        if (peer.pairingPort() <= 0) {
            return RemoteTrustOutcome.unknown(RemoteTrustFailure.NO_PAIRING_ENDPOINT, null);
        }
        if (!forceRefresh) {
            Optional<RemoteTrustOutcome> cached = remoteTrustCache.get(peer.fingerprint());
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        try {
            PairingStatusClient.Result result = pairingQuery.query(peer, PAIRING_QUERY_TIMEOUT);
            RemoteTrustOutcome outcome = result.response().trustsPeer()
                    ? RemoteTrustOutcome.trusted()
                    : RemoteTrustOutcome.untrusted();
            remoteTrustCache.put(peer.fingerprint(), outcome, REMOTE_TRUST_TTL);
            return outcome;
        } catch (PairingStatusException e) {
            RemoteTrustOutcome outcome = RemoteTrustOutcome.unknown(
                    failureOf(e.reason()), detail(e));
            remoteTrustCache.put(peer.fingerprint(), outcome, REMOTE_TRUST_FAILURE_TTL);
            return outcome;
        } catch (IOException | RuntimeException e) {
            RemoteTrustOutcome outcome = RemoteTrustOutcome.unknown(
                    RemoteTrustFailure.CONNECTION_FAILED, detail(e));
            remoteTrustCache.put(peer.fingerprint(), outcome, REMOTE_TRUST_FAILURE_TTL);
            return outcome;
        }
    }

    private void connect(DiscoveredPeer peer) throws IOException {
        if (!isTrusted(peer)) {
            return;
        }
        if (peerGroup.isConnected(peer.nodeId())) {
            return;
        }
        InetAddress address = addressOf(peer);
        peerGroup.allowClient(peer.fingerprint());
        peerGroup.addPeer(peer.nodeId(), address.getHostAddress(), peer.port(),
                peer.fingerprint());
    }

    private void handleCertificateMismatch(DiscoveredPeer peer) {
        try {
            trustStore.untrust(group, peer.fingerprint());
        } catch (IOException ignored) {
            // the warning below is still useful
        }
        remoteTrustCache.invalidate(peer.fingerprint());
        peerGroup.revokeClient(peer.fingerprint());
        peerGroup.removePeer(peer.nodeId());
        lastWarning = "对端证书与缓存的指纹不匹配，已关闭对 " + peer.deviceId().value() + " 的信任";
    }

    private void pruneCache(List<PresenceTracker.Presence> peers) {
        Set<String> online = new HashSet<>();
        for (PresenceTracker.Presence presence : peers) {
            online.add(normalized(presence.peer().fingerprint()));
        }
        remoteTrustCache.prune(online);
    }

    private static RemoteTrustFailure failureOf(PairingStatusException.Reason reason) {
        return switch (reason) {
            case TIMEOUT -> RemoteTrustFailure.QUERY_TIMEOUT;
            case IDENTITY_MISMATCH -> RemoteTrustFailure.IDENTITY_MISMATCH;
            case CONNECTION_FAILED -> RemoteTrustFailure.CONNECTION_FAILED;
        };
    }

    private static String detail(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty() ? null : message;
    }

    private static ConnectionProbe probe(PeerGroup peerGroup) {
        Objects.requireNonNull(peerGroup, "peerGroup");
        return peer -> peerGroup.isConnected(peer.nodeId())
                || peerGroup.hasInboundConnection(peer.fingerprint());
    }

    private static GroupId groupIdOf(PeerGroup peerGroup) {
        return Objects.requireNonNull(peerGroup, "peerGroup").groupId();
    }

    private static PairingQuery query(TlsIdentity identity, GroupId group) {
        Objects.requireNonNull(identity, "identity");
        return (peer, timeout) -> PairingStatusClient.query(
                addressOf(peer).getHostAddress(), peer.pairingPort(), identity,
                peer.fingerprint(), group, peer.nodeId(), peer.deviceId(), timeout);
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

    private static String normalized(String fingerprint) {
        return fingerprint.trim().toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    interface ConnectionProbe {
        boolean isConnected(DiscoveredPeer peer);
    }

    @FunctionalInterface
    interface PairingQuery {
        PairingStatusClient.Result query(DiscoveredPeer peer, Duration timeout) throws IOException;
    }

    /**
     * One discovered peer of {@code groupId}.
     *
     * <p>The group id is part of the view so a backend that runs several groups can tell
     * which group a device row belongs to; the same device shows up once per group.
     */
    public record PeerView(GroupId groupId, PresenceTracker.Presence presence,
                           boolean locallyTrusted, RemoteTrust remoteTrust, boolean connected,
                           RemoteTrustFailure remoteTrustFailure,
                           String remoteTrustErrorDetail) {

        private static final String OFFLINE_NODE_PREFIX = "offline:";

        /**
         * A device row restored from the persisted group device list. It has no live address
         * and no remote trust information; the UI must show it as offline and must not allow
         * granting trust. Revoking the local trust of an offline device is still possible.
         */
        public static PeerView offline(GroupId groupId, DeviceId deviceId, String fingerprint,
                                       Instant lastSeen, boolean locallyTrusted) {
            DiscoveredPeer peer = new DiscoveredPeer(
                    NodeId.of(OFFLINE_NODE_PREFIX + deviceId.value()), deviceId,
                    Set.of(groupId), 0, fingerprint, List.of(), 1, 0);
            PresenceTracker.Presence presence =
                    new PresenceTracker.Presence(peer, lastSeen == null ? Instant.EPOCH : lastSeen);
            return new PeerView(groupId, presence, locallyTrusted, RemoteTrust.UNKNOWN, false,
                    RemoteTrustFailure.NO_PAIRING_ENDPOINT, "离线");
        }

        public static boolean isOffline(PeerView view) {
            return view != null
                    && view.presence().peer().nodeId().value().startsWith(OFFLINE_NODE_PREFIX);
        }
    }
}
