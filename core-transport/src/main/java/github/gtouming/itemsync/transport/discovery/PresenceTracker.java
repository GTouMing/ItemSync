package github.gtouming.itemsync.transport.discovery;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class PresenceTracker implements PeerDiscoveryListener {

    public record Presence(DiscoveredPeer peer, Instant lastSeen) {
    }

    private final GroupId groupId;
    private final NodeId selfNodeId;
    private final Supplier<Instant> clock;
    private final Map<NodeId, Presence> peers = new ConcurrentHashMap<>();

    public PresenceTracker(GroupId groupId, NodeId selfNodeId, Supplier<Instant> clock) {
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.selfNodeId = Objects.requireNonNull(selfNodeId, "selfNodeId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onPeerDiscovered(DiscoveredPeer peer) {
        if (!groupId.equals(peer.groupId()) || selfNodeId.equals(peer.nodeId())) {
            return;
        }
        peers.put(peer.nodeId(), new Presence(peer, clock.get()));
    }

    @Override
    public void onPeerLost(DiscoveredPeer peer) {
        if (groupId.equals(peer.groupId())) {
            peers.remove(peer.nodeId());
        }
    }

    public List<Presence> online() {
        List<Presence> values = new ArrayList<>(peers.values());
        values.sort(Comparator.comparing(presence -> presence.peer().deviceId().value()));
        return Compat.copyList(values);
    }
}
