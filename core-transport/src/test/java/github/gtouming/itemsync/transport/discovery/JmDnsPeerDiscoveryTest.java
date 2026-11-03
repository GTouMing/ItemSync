package github.gtouming.itemsync.transport.discovery;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.transport.frame.FrameCodec;
import javax.jmdns.ServiceInfo;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmDnsPeerDiscoveryTest {

    private static final GroupId GROUP = GroupId.parse("group001");
    private static final GroupId OTHER_GROUP = GroupId.parse("group002");

    @Test
    void mapsAdvertisementToServiceInfoAndBack() {
        PeerAdvertisement advertisement = new PeerAdvertisement(
                NodeId.of("node-a"), DeviceId.of("device-a"), Set.of(GROUP),
                FrameCodec.PROTOCOL_VERSION, "fingerprint", 47800, 47801);

        ServiceInfo info = JmDnsPeerDiscovery.serviceInfoFor(advertisement);

        assertEquals(JmDnsPeerDiscovery.SERVICE_TYPE, info.getType());
        assertEquals("device-a", info.getName());
        assertEquals(47800, info.getPort());

        DiscoveredPeer peer = JmDnsPeerDiscovery.toPeer(info);
        assertNotNull(peer);
        assertEquals(advertisement.nodeId(), peer.nodeId());
        assertEquals(advertisement.deviceId(), peer.deviceId());
        assertEquals(Set.of(GROUP), peer.groupIds());
        assertEquals(advertisement.protocolVersion(), peer.protocolVersion());
        assertEquals(advertisement.fingerprint(), peer.fingerprint());
        assertEquals(advertisement.port(), peer.port());
        assertEquals(47801, peer.pairingPort());
        assertTrue(peer.addresses().isEmpty());
    }

    @Test
    void writesEveryGroupIdAsOneCommaSeparatedTxtValue() {
        Set<GroupId> groupIds = new LinkedHashSet<>();
        groupIds.add(GROUP);
        groupIds.add(OTHER_GROUP);
        PeerAdvertisement advertisement = new PeerAdvertisement(
                NodeId.of("node-a"), DeviceId.of("device-a"), groupIds,
                FrameCodec.PROTOCOL_VERSION, "fingerprint", 47800, 0);

        ServiceInfo info = JmDnsPeerDiscovery.serviceInfoFor(advertisement);

        assertEquals(GROUP.value() + "," + OTHER_GROUP.value(),
                info.getPropertyString("groupIds"));
        assertNull(info.getPropertyString("groupId"));
        DiscoveredPeer peer = JmDnsPeerDiscovery.toPeer(info);
        assertNotNull(peer);
        assertEquals(Set.of(GROUP, OTHER_GROUP), peer.groupIds());
    }

    @Test
    void acceptsTheLegacySingleGroupIdTxt() {
        ServiceInfo info = ServiceInfo.create(JmDnsPeerDiscovery.SERVICE_TYPE, "device-a",
                1234, 0, 0,
                Map.of("nodeId", "node-a", "deviceId", "device-a", "groupId", GROUP.value(),
                        "protocolVersion", "1", "fingerprint", "fp"));

        DiscoveredPeer peer = JmDnsPeerDiscovery.toPeer(info);

        assertNotNull(peer);
        assertEquals(Set.of(GROUP), peer.groupIds());
    }

    @Test
    void fallsBackToTheLegacyGroupIdWhenGroupIdsIsBlank() {
        // a decoded TXT value can be blank even though a written empty value becomes "true"
        assertEquals(Set.of(GROUP), JmDnsPeerDiscovery.groupIds(" ", GROUP.value()));
        assertEquals(Set.of(), JmDnsPeerDiscovery.groupIds(null, null));
    }

    @Test
    void rejectsPeersWithoutAnyGroupId() {
        ServiceInfo info = ServiceInfo.create(JmDnsPeerDiscovery.SERVICE_TYPE, "device-a",
                1234, 0, 0,
                Map.of("nodeId", "node-a", "deviceId", "device-a", "groupIds", " , ",
                        "protocolVersion", "1", "fingerprint", "fp"));

        assertNull(JmDnsPeerDiscovery.toPeer(info));
    }

    @Test
    void rejectsAnInvalidGroupIdInTheTxtValue() {
        ServiceInfo info = ServiceInfo.create(JmDnsPeerDiscovery.SERVICE_TYPE, "device-a",
                1234, 0, 0,
                Map.of("nodeId", "node-a", "deviceId", "device-a",
                        "groupIds", GROUP.value() + ",NOTAGROUP",
                        "protocolVersion", "1", "fingerprint", "fp"));

        assertNull(JmDnsPeerDiscovery.toPeer(info));
    }

    @Test
    void rejectsMoreThanFiveGroupIds() {
        StringBuilder tooMany = new StringBuilder();
        for (int i = 0; i < PeerAdvertisement.MAX_GROUPS + 1; i++) {
            if (i > 0) {
                tooMany.append(',');
            }
            tooMany.append("group00").append(i);
        }
        ServiceInfo info = ServiceInfo.create(JmDnsPeerDiscovery.SERVICE_TYPE, "device-a",
                1234, 0, 0,
                Map.of("nodeId", "node-a", "deviceId", "device-a",
                        "groupIds", tooMany.toString(),
                        "protocolVersion", "1", "fingerprint", "fp"));

        assertNull(JmDnsPeerDiscovery.toPeer(info));
    }

    @Test
    void defaultsMissingPairingPortToZero() {
        ServiceInfo info = ServiceInfo.create(JmDnsPeerDiscovery.SERVICE_TYPE, "device-a",
                1234, 0, 0,
                Map.of("nodeId", "node-a", "deviceId", "device-a", "groupId", GROUP.value(),
                        "protocolVersion", "1", "fingerprint", "fp"));

        DiscoveredPeer peer = JmDnsPeerDiscovery.toPeer(info);

        assertNotNull(peer);
        assertEquals(0, peer.pairingPort());
    }

    @Test
    void defaultsInvalidPairingPortToZero() {
        ServiceInfo info = ServiceInfo.create(JmDnsPeerDiscovery.SERVICE_TYPE, "device-a",
                1234, 0, 0,
                Map.of("nodeId", "node-a", "deviceId", "device-a", "groupId", GROUP.value(),
                        "protocolVersion", "1", "fingerprint", "fp",
                        "pairingPort", "not-a-port"));

        DiscoveredPeer peer = JmDnsPeerDiscovery.toPeer(info);

        assertNotNull(peer);
        assertEquals(0, peer.pairingPort());
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
                Map.of("nodeId", "node-a", "deviceId", "device-a", "groupId", GROUP.value(),
                        "protocolVersion", "abc", "fingerprint", "fp"));

        assertNull(JmDnsPeerDiscovery.toPeer(info));
    }
}
