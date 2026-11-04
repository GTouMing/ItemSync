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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

public final class PresenceTracker implements PeerDiscoveryListener {

    public record Presence(DiscoveredPeer peer, Instant lastSeen) {
    }

    private final GroupId groupId;
    private final NodeId selfNodeId;
    private final Supplier<Instant> clock;
    private final Map<NodeId, Presence> peers = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public PresenceTracker(GroupId groupId, NodeId selfNodeId, Supplier<Instant> clock) {
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.selfNodeId = Objects.requireNonNull(selfNodeId, "selfNodeId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onPeerDiscovered(DiscoveredPeer peer) {
        if (!peer.groupIds().contains(groupId) || selfNodeId.equals(peer.nodeId())) {
            return;
        }
        Presence previous = peers.put(peer.nodeId(), new Presence(peer, clock.get()));
        if (previous == null || !previous.peer().equals(peer)) {
            notifyListeners();
        }
    }

    @Override
    public void onPeerLost(DiscoveredPeer peer) {
        if (peer.groupIds().contains(groupId) && peers.remove(peer.nodeId()) != null) {
            notifyListeners();
        }
    }

    /**
     * Registers a listener that fires when a peer appears or disappears in this group. The
     * listener runs on the discovery thread; UI callers must post to their own UI thread.
     * Closing the returned handle removes it.
     */
    public AutoCloseable addListener(Runnable listener) {
        Runnable value = Objects.requireNonNull(listener, "listener");
        listeners.add(value);
        return () -> listeners.remove(value);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // one failing UI listener must not break discovery
            }
        }
    }

    public List<Presence> online() {
        List<Presence> values = new ArrayList<>(peers.values());
        values.sort(Comparator.comparing(presence -> presence.peer().deviceId().value()));
        return Compat.copyList(values);
    }
}
