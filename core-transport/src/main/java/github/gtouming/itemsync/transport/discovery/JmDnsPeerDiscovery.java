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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
            try {
                jmDns.requestServiceInfo(event.getType(), event.getName());
            } catch (RuntimeException ignored) {
                // resolution may still arrive through serviceResolved
            }
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
        InetAddress target = address == null ? preferredAddress() : address;
        this.jmDns = target == null ? JmDNS.create() : JmDNS.create(target);
    }

    private static InetAddress preferredAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            InetAddress best = null;
            int bestScore = 0;
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()
                        || !network.supportsMulticast()) {
                    continue;
                }
                String name = (network.getName() + " " + network.getDisplayName())
                        .toLowerCase(Locale.ROOT);
                if (isVirtualAdapter(name)) {
                    continue;
                }
                int score = name.contains("wlan") || name.contains("wifi") || name.contains("wi-fi")
                        ? 3
                        : name.contains("eth") || name.contains("ethernet")
                        ? 2
                        : 1;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address)
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) {
                        continue;
                    }
                    if (score > bestScore) {
                        best = address;
                        bestScore = score;
                    }
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isVirtualAdapter(String name) {
        return name.contains("docker")
                || name.contains("vmnet")
                || name.contains("vmware")
                || name.contains("vbox")
                || name.contains("virtual")
                || name.contains("vethernet")
                || name.contains("tailscale")
                || name.contains("zerotier")
                || name.contains("vpn")
                || name.contains("tun")
                || name.contains("tap")
                || name.contains("wsl");
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
        properties.put("groupIds", encodeGroupIds(advertisement.groupIds()));
        properties.put("protocolVersion", Integer.toString(advertisement.protocolVersion()));
        properties.put("fingerprint", advertisement.fingerprint());
        properties.put("pairingPort", Integer.toString(advertisement.pairingPort()));
        return ServiceInfo.create(SERVICE_TYPE, advertisement.deviceId().value(),
                advertisement.port(), 0, 0, properties);
    }

    /**
     * Writes the group list as one comma separated TXT value; the record guarantees
     * at most {@link PeerAdvertisement#MAX_GROUPS} ids.
     */
    static String encodeGroupIds(Set<GroupId> groupIds) {
        StringBuilder encoded = new StringBuilder();
        for (GroupId groupId : groupIds) {
            if (encoded.length() > 0) {
                encoded.append(',');
            }
            encoded.append(groupId.value());
        }
        return encoded.toString();
    }

    /**
     * Parses the comma separated {@code groupIds} TXT value and falls back to the legacy
     * single {@code groupId} value, which predates multi-group discovery.
     */
    static Set<GroupId> groupIds(String rawGroupIds, String legacyGroupId) {
        String raw = Compat.isBlank(rawGroupIds) ? legacyGroupId : rawGroupIds;
        if (Compat.isBlank(raw)) {
            return Compat.set();
        }
        Set<GroupId> groupIds = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            if (Compat.isBlank(token)) {
                continue;
            }
            groupIds.add(GroupId.parse(token));
        }
        if (groupIds.size() > PeerAdvertisement.MAX_GROUPS) {
            throw new IllegalArgumentException("at most " + PeerAdvertisement.MAX_GROUPS
                    + " group ids can be discovered, got " + groupIds.size());
        }
        return groupIds;
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
        String protocolVersion = info.getPropertyString("protocolVersion");
        String fingerprint = info.getPropertyString("fingerprint");
        if (nodeId == null || deviceId == null
                || protocolVersion == null || fingerprint == null) {
            return null;
        }
        try {
            Set<GroupId> groupIds = groupIds(info.getPropertyString("groupIds"),
                    info.getPropertyString("groupId"));
            if (groupIds.isEmpty()) {
                return null;
            }
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
                    groupIds,
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
