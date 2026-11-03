package github.gtouming.itemsync.sync.trust;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.transport.discovery.PresenceTracker;

import java.io.IOException;
import java.util.List;

/**
 * One running sync group as seen by the trust layer: its presence, its group scoped
 * connection probe and the client pin and connection controls of that group only.
 *
 * <p>{@code SyncNode} (single group facade) and {@code GroupRuntime} (one group of a
 * multi-group device) both implement this, so a single {@link PeerTrustCoordinator}
 * implementation serves every group of a device without knowing how many groups the device
 * runs (ADR-0036).
 */
public interface PeerGroup {

    GroupId groupId();

    List<PresenceTracker.Presence> onlinePeers();

    boolean isConnected(NodeId peerNodeId);

    boolean hasInboundConnection(String fingerprint);

    void allowClient(String fingerprint);

    void revokeClient(String fingerprint);

    void removePeer(NodeId peerNodeId);

    void addPeer(NodeId peerNodeId, String host, int port, String expectedServerFingerprint)
            throws IOException;
}
