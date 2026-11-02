package github.gtouming.itemsync.sync.trust;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.security.PairingCode;
import github.gtouming.itemsync.sync.engine.node.SyncNode;
import github.gtouming.itemsync.transport.discovery.DiscoveredPeer;
import github.gtouming.itemsync.transport.discovery.PresenceTracker;
import github.gtouming.itemsync.transport.pairing.PairingStatusClient;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class PeerTrustCoordinator implements AutoCloseable {

    private static final Duration REMOTE_TRUST_TTL = Duration.ofSeconds(30);
    private static final Duration REMOTE_TRUST_FAILURE_TTL = Duration.ofSeconds(5);
    private static final Duration PAIRING_QUERY_TIMEOUT = Duration.ofSeconds(2);

    private final GroupId group;
    private final String localFingerprint;
    private final TlsIdentity identity;
    private final SyncNode node;
    private final PeerTrustStore trustStore;
    private final Set<String> connectedPeers = ConcurrentHashMap.newKeySet();
    private final RemoteTrustCache remoteTrustCache = new RemoteTrustCache(Instant::now);
    private final ExecutorService statusExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "itemsync-pairing-status");
        thread.setDaemon(true);
        return thread;
    });

    public PeerTrustCoordinator(GroupId group, TlsIdentity identity, SyncNode node,
                                PeerTrustStore trustStore) {
        this.group = Objects.requireNonNull(group, "group");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.localFingerprint = identity.fingerprint();
        this.node = Objects.requireNonNull(node, "node");
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
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
        return Compat.copyList(views);
    }

    public boolean isTrusted(DiscoveredPeer peer) {
        return trustStore.isTrusted(group, peer.fingerprint());
    }

    public boolean isConnected(DiscoveredPeer peer) {
        return connectedPeers.contains(normalized(peer.fingerprint()));
    }

    public String pairingCode(DiscoveredPeer peer) {
        return PairingCode.between(group, localFingerprint, peer.fingerprint());
    }

    public void trustPeer(DiscoveredPeer peer) throws IOException {
        trustStore.trust(group, peer.fingerprint());
        remoteTrustCache.invalidate(peer.fingerprint());
        node.allowClient(peer.fingerprint());
        connect(peer);
    }

    public void untrustPeer(DiscoveredPeer peer) throws IOException {
        trustStore.untrust(group, peer.fingerprint());
        remoteTrustCache.invalidate(peer.fingerprint());
        node.revokeClient(peer.fingerprint());
        node.removePeer(peer.nodeId());
        connectedPeers.remove(normalized(peer.fingerprint()));
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
                // coordinator is closing
            }
        }
    }

    @Override
    public void close() {
        statusExecutor.shutdownNow();
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
            Optional<RemoteTrust> cached = remoteTrustCache.get(peer.fingerprint());
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        try {
            InetAddress address = addressOf(peer);
            PairingStatusClient.Result result = PairingStatusClient.query(
                    address.getHostAddress(), peer.pairingPort(), identity, peer.fingerprint(),
                    group, peer.nodeId(), peer.deviceId(), PAIRING_QUERY_TIMEOUT);
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

    private void retainOnlinePeers(List<PresenceTracker.Presence> peers) {
        Set<String> online = new LinkedHashSet<>();
        for (PresenceTracker.Presence presence : peers) {
            online.add(normalized(presence.peer().fingerprint()));
        }
        connectedPeers.retainAll(online);
    }

    private void connect(DiscoveredPeer peer) throws IOException {
        if (!isTrusted(peer)) {
            return;
        }
        String fingerprint = normalized(peer.fingerprint());
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

    private static String normalized(String fingerprint) {
        return fingerprint.trim().toLowerCase(Locale.ROOT);
    }

    public record PeerView(PresenceTracker.Presence presence, boolean locallyTrusted,
                           RemoteTrust remoteTrust, boolean connected) {

        public boolean identityVerified() {
            return remoteTrust != RemoteTrust.UNKNOWN;
        }
    }
}
