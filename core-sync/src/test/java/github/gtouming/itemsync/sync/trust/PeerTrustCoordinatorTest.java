package github.gtouming.itemsync.sync.trust;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.transport.discovery.DiscoveredPeer;
import github.gtouming.itemsync.transport.discovery.PresenceTracker;
import github.gtouming.itemsync.transport.frame.PairingStatusResponse;
import github.gtouming.itemsync.transport.pairing.PairingStatusClient;
import github.gtouming.itemsync.transport.pairing.PairingStatusException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerTrustCoordinatorTest {

    private static final GroupId GROUP = GroupId.parse("group001");
    private static final String FINGERPRINT = "a".repeat(64);
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void connectedPeerIsTrustedWithoutQuerying() {
        AtomicInteger queries = new AtomicInteger();
        PeerTrustCoordinator coordinator = coordinator(
                peer -> true,
                (peer, timeout) -> {
                    queries.incrementAndGet();
                    throw new IOException("must not query");
                },
                () -> T0);

        PeerTrustCoordinator.PeerView view = coordinator.viewOf(presence(1234), true);

        assertEquals(RemoteTrust.TRUSTED, view.remoteTrust());
        assertTrue(view.connected());
        assertNull(view.remoteTrustFailure());
        assertNull(view.remoteTrustErrorDetail());
        assertEquals(0, queries.get());
    }

    @Test
    void mapsTimeoutToAStructuredFailure() {
        PeerTrustCoordinator coordinator = coordinator(
                peer -> false,
                (peer, timeout) -> {
                    throw new PairingStatusException(
                            PairingStatusException.Reason.TIMEOUT, "timed out");
                },
                () -> T0);

        PeerTrustCoordinator.PeerView view = coordinator.viewOf(presence(1234), true);

        assertEquals(RemoteTrust.UNKNOWN, view.remoteTrust());
        assertFalse(view.connected());
        assertEquals(RemoteTrustFailure.QUERY_TIMEOUT, view.remoteTrustFailure());
        assertEquals("timed out", view.remoteTrustErrorDetail());
    }

    @Test
    void skipsTheQueryWhenThePeerHasNoPairingEndpoint() {
        AtomicInteger queries = new AtomicInteger();
        PeerTrustCoordinator coordinator = coordinator(
                peer -> false,
                (peer, timeout) -> {
                    queries.incrementAndGet();
                    throw new IOException("must not query");
                },
                () -> T0);

        PeerTrustCoordinator.PeerView view = coordinator.viewOf(presence(0), true);

        assertEquals(RemoteTrust.UNKNOWN, view.remoteTrust());
        assertEquals(RemoteTrustFailure.NO_PAIRING_ENDPOINT, view.remoteTrustFailure());
        assertEquals(0, queries.get());
    }

    @Test
    void cachesSuccessfulQueriesAndRefreshesAfterTheTtl() {
        AtomicInteger queries = new AtomicInteger();
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        PeerTrustCoordinator coordinator = coordinator(
                peer -> false,
                (peer, timeout) -> {
                    queries.incrementAndGet();
                    return trustedResult(peer, true);
                },
                now::get);

        coordinator.viewOf(presence(1234), false);
        coordinator.viewOf(presence(1234), false);
        assertEquals(1, queries.get());

        now.set(T0.plusSeconds(31));
        coordinator.viewOf(presence(1234), false);
        assertEquals(2, queries.get());
    }

    @Test
    void forceRefreshBypassesTheCache() {
        AtomicInteger queries = new AtomicInteger();
        PeerTrustCoordinator coordinator = coordinator(
                peer -> false,
                (peer, timeout) -> {
                    queries.incrementAndGet();
                    return trustedResult(peer, true);
                },
                () -> T0);

        coordinator.viewOf(presence(1234), false);
        coordinator.viewOf(presence(1234), true);

        assertEquals(2, queries.get());
    }

    @Test
    void cachesFailuresForTheShorterTtl() {
        AtomicInteger queries = new AtomicInteger();
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        PeerTrustCoordinator coordinator = coordinator(
                peer -> false,
                (peer, timeout) -> {
                    queries.incrementAndGet();
                    throw new PairingStatusException(
                            PairingStatusException.Reason.CONNECTION_FAILED, "refused");
                },
                now::get);

        coordinator.viewOf(presence(1234), false);
        now.set(T0.plusSeconds(4));
        coordinator.viewOf(presence(1234), false);
        assertEquals(1, queries.get());

        now.set(T0.plusSeconds(6));
        PeerTrustCoordinator.PeerView view = coordinator.viewOf(presence(1234), false);
        assertEquals(2, queries.get());
        assertEquals(RemoteTrustFailure.CONNECTION_FAILED, view.remoteTrustFailure());
    }

    private static PeerTrustCoordinator coordinator(
            PeerTrustCoordinator.ConnectionProbe probe,
            PeerTrustCoordinator.PairingQuery query,
            Supplier<Instant> clock) {
        return new PeerTrustCoordinator(GROUP, null, trustStore(), probe, query, clock);
    }

    private static PresenceTracker.Presence presence(int pairingPort) {
        DiscoveredPeer peer = new DiscoveredPeer(NodeId.of("peer-node"),
                DeviceId.of("peer-device"), Set.of(GROUP), 1, FINGERPRINT,
                List.of(InetAddress.getLoopbackAddress()), 47800, pairingPort);
        return new PresenceTracker.Presence(peer, T0);
    }

    private static PairingStatusClient.Result trustedResult(DiscoveredPeer peer, boolean trusts) {
        return new PairingStatusClient.Result(
                new PairingStatusResponse(GROUP, peer.nodeId(), peer.deviceId(),
                        FINGERPRINT, trusts),
                FINGERPRINT);
    }

    private static PeerTrustStore trustStore() {
        return new PeerTrustStore() {
            @Override
            public boolean isTrusted(GroupId group, String fingerprint) {
                return false;
            }

            @Override
            public void trust(GroupId group, String fingerprint) {
            }

            @Override
            public void untrust(GroupId group, String fingerprint) {
            }

            @Override
            public List<String> fingerprints(GroupId group) {
                return List.of();
            }

            @Override
            public void retainOnly(GroupId group) {
            }

            @Override
            public void clear() {
            }
        };
    }
}
