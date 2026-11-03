package github.gtouming.itemsync.transport.discovery;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class JmDnsPeerDiscovery implements PeerDiscovery {

    public static final String SERVICE_TYPE = "_itemsync._tcp.local.";

    private final JmDNS jmDns;
    private final List<PeerDiscoveryListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, DiscoveredPeer> known = new ConcurrentHashMap<>();

    private final ServiceListener serviceListener = new ServiceListener() {
        @Override
        public void serviceAdded(ServiceEvent event) {
            // resolution is handled in serviceResolved
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            DiscoveredPeer removed = known.remove(event.getInfo().getName());
            if (removed != null) {
                for (PeerDiscoveryListener listener : listeners) {
                    listener.onPeerLost(removed);
                }
            }
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            DiscoveredPeer peer = toPeer(event.getInfo());
            if (peer == null) {
                return;
            }
            DiscoveredPeer previous = known.put(event.getInfo().getName(), peer);
            if (!peer.equals(previous)) {
                for (PeerDiscoveryListener listener : listeners) {
                    listener.onPeerDiscovered(peer);
                }
            }
        }
    };

    private ServiceInfo advertised;
    private boolean started;

    public JmDnsPeerDiscovery() throws IOException {
        this(null);
    }

    public JmDnsPeerDiscovery(InetAddress address) throws IOException {
        this.jmDns = address == null ? JmDNS.create() : JmDNS.create(address);
    }

    @Override
    public void start() {
        if (!started) {
            jmDns.addServiceListener(SERVICE_TYPE, serviceListener);
            started = true;
        }
    }

    @Override
    public void advertise(PeerAdvertisement advertisement) throws IOException {
        Objects.requireNonNull(advertisement, "advertisement");
        ServiceInfo info = serviceInfoFor(advertisement);
        if (advertised != null) {
            jmDns.unregisterService(advertised);
        }
        jmDns.registerService(info);
        advertised = info;
    }

    @Override
    public void stopAdvertising() {
        if (advertised != null) {
            jmDns.unregisterService(advertised);
            advertised = null;
        }
    }

    @Override
    public void addListener(PeerDiscoveryListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void close() {
        stopAdvertising();
        if (started) {
            jmDns.removeServiceListener(SERVICE_TYPE, serviceListener);
            started = false;
        }
        try {
            jmDns.close();
        } catch (IOException ignored) {
            // closing is best effort
        }
    }

    static ServiceInfo serviceInfoFor(PeerAdvertisement advertisement) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("nodeId", advertisement.nodeId().value());
        properties.put("deviceId", advertisement.deviceId().value());
        properties.put("groupId", advertisement.groupId().value());
        properties.put("protocolVersion", Integer.toString(advertisement.protocolVersion()));
        properties.put("fingerprint", advertisement.fingerprint());
        properties.put("pairingPort", Integer.toString(advertisement.pairingPort()));
        return ServiceInfo.create(SERVICE_TYPE, advertisement.deviceId().value(),
                advertisement.port(), 0, 0, properties);
    }

    static int pairingPort(String raw) {
        if (Compat.isBlank(raw)) {
            return 0;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            return port >= 0 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static DiscoveredPeer toPeer(ServiceInfo info) {
        String nodeId = info.getPropertyString("nodeId");
        String deviceId = info.getPropertyString("deviceId");
        String groupId = info.getPropertyString("groupId");
        String protocolVersion = info.getPropertyString("protocolVersion");
        String fingerprint = info.getPropertyString("fingerprint");
        if (nodeId == null || deviceId == null || groupId == null
                || protocolVersion == null || fingerprint == null) {
            return null;
        }
        try {
            List<InetAddress> addresses = new ArrayList<>();
            for (InetAddress address : info.getInet4Addresses()) {
                addresses.add(address);
            }
            if (addresses.isEmpty()) {
                for (InetAddress address : info.getInetAddresses()) {
                    addresses.add(address);
                }
            }
            return new DiscoveredPeer(
                    NodeId.of(nodeId),
                    DeviceId.of(deviceId),
                    GroupId.parse(groupId),
                    Integer.parseInt(protocolVersion),
                    fingerprint,
                    addresses,
                    info.getPort(),
                    pairingPort(info.getPropertyString("pairingPort")));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
