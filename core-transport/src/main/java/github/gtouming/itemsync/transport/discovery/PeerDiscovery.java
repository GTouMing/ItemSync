package github.gtouming.itemsync.transport.discovery;

import java.io.IOException;

public interface PeerDiscovery extends AutoCloseable {

    void start() throws IOException;

    void advertise(PeerAdvertisement advertisement) throws IOException;

    void stopAdvertising();

    void addListener(PeerDiscoveryListener listener);

    @Override
    void close();
}
