package github.gtouming.itemsync.transport.discovery;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceTrackerTest {

    private static final GroupId GROUP = GroupId.parse("group001");
    private static final GroupId OTHER_GROUP = GroupId.parse("group002");
    private static final NodeId SELF = NodeId.of("self");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void keepsOnlyOnlinePeersFromTheSameGroup() {
        PresenceTracker tracker = new PresenceTracker(GROUP, SELF, () -> T0);

        tracker.onPeerDiscovered(peer("node-a", "device-a", Set.of(GROUP)));
        tracker.onPeerDiscovered(peer("node-b", "device-b", Set.of(OTHER_GROUP)));
        tracker.onPeerDiscovered(peer("self", "device-self", Set.of(GROUP)));

        assertEquals(1, tracker.online().size());
        assertEquals("device-a", tracker.online().get(0).peer().deviceId().value());
    }

    @Test
    void keepsPeersThatShareTheGroupAmongOthers() {
        PresenceTracker tracker = new PresenceTracker(GROUP, SELF, () -> T0);

        tracker.onPeerDiscovered(peer("node-a", "device-a", Set.of(OTHER_GROUP, GROUP)));

        assertEquals(1, tracker.online().size());
        assertEquals(Set.of(OTHER_GROUP, GROUP), tracker.online().get(0).peer().groupIds());
    }

    @Test
    void refreshesLastSeenAndRemovesLostPeers() {
        Instant[] now = {T0};
        PresenceTracker tracker = new PresenceTracker(GROUP, SELF, () -> now[0]);
        DiscoveredPeer peer = peer("node-a", "device-a", Set.of(GROUP));

        tracker.onPeerDiscovered(peer);
        now[0] = T0.plusSeconds(5);
        tracker.onPeerDiscovered(peer);

        assertEquals(T0.plusSeconds(5), tracker.online().get(0).lastSeen());

        tracker.onPeerLost(peer);

        assertTrue(tracker.online().isEmpty());
    }

    @Test
    void notifiesListenersWhenPeersAppearAndDisappear() throws Exception {
        PresenceTracker tracker = new PresenceTracker(GROUP, SELF, () -> T0);
        AtomicInteger notifications = new AtomicInteger();
        AutoCloseable handle = tracker.addListener(notifications::incrementAndGet);
        DiscoveredPeer peer = peer("node-a", "device-a", Set.of(GROUP));

        tracker.onPeerDiscovered(peer);
        assertEquals(1, notifications.get(), "a new peer must notify");
        tracker.onPeerDiscovered(peer);
        assertEquals(1, notifications.get(), "an unchanged announcement must not notify");

        tracker.onPeerLost(peer);
        assertEquals(2, notifications.get(), "a lost peer must notify");

        handle.close();
        tracker.onPeerDiscovered(peer);
        assertEquals(2, notifications.get(), "a closed listener must not be called");
    }

    private static DiscoveredPeer peer(String nodeId, String deviceId, Set<GroupId> groupIds) {
        return new DiscoveredPeer(NodeId.of(nodeId), DeviceId.of(deviceId), groupIds,
                1, "fingerprint", List.of(), 47800, 0);
    }
}
