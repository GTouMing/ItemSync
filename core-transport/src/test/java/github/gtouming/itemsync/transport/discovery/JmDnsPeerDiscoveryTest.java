package github.gtouming.itemsync.transport.discovery;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.transport.frame.FrameCodec;
import javax.jmdns.ServiceInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmDnsPeerDiscoveryTest {

    @Test
    void mapsAdvertisementToServiceInfoAndBack() {
        PeerAdvertisement advertisement = new PeerAdvertisement(
                NodeId.of("node-a"), DeviceId.of("device-a"),
                FrameCodec.PROTOCOL_VERSION, "fingerprint", 47800);

        ServiceInfo info = JmDnsPeerDiscovery.serviceInfoFor(advertisement);

        assertEquals(JmDnsPeerDiscovery.SERVICE_TYPE, info.getType());
        assertEquals("device-a", info.getName());
        assertEquals(47800, info.getPort());

        DiscoveredPeer peer = JmDnsPeerDiscovery.toPeer(info);
        assertNotNull(peer);
        assertEquals(advertisement.nodeId(), peer.nodeId());
        assertEquals(advertisement.deviceId(), peer.deviceId());
        assertEquals(advertisement.protocolVersion(), peer.protocolVersion());
        assertEquals(advertisement.fingerprint(), peer.fingerprint());
        assertEquals(advertisement.port(), peer.port());
        assertTrue(peer.addresses().isEmpty());
    }

    @Test
    void rejectsIncompleteServiceInfo() {
        ServiceInfo info = ServiceInfo.create(JmDnsPeerDiscovery.SERVICE_TYPE, "device-a",
                1234, 0, 0, Map.of("nodeId", "node-a"));

        assertNull(JmDnsPeerDiscovery.toPeer(info));
    }

    @Test
    void rejectsInvalidProtocolVersion() {
        ServiceInfo info = ServiceInfo.create(JmDnsPeerDiscovery.SERVICE_TYPE, "device-a", 1234, 0, 0,
                Map.of("nodeId", "node-a", "deviceId", "device-a",
                        "protocolVersion", "abc", "fingerprint", "fp"));

        assertNull(JmDnsPeerDiscovery.toPeer(info));
    }
}
