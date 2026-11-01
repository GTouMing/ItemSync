package github.gtouming.itemsync.transport.discovery;

public interface PeerDiscoveryListener {

    void onPeerDiscovered(DiscoveredPeer peer);

    void onPeerLost(DiscoveredPeer peer);
}
