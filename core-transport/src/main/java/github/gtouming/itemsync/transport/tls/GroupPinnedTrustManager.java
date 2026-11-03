package github.gtouming.itemsync.transport.tls;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.GroupId;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies inbound TLS client certificates against the pins of the group selected by
 * the handshake's SNI server name (ADR-0036).
 *
 * <p>There is deliberately no union fallback: a connection whose SNI is missing,
 * malformed or unknown is rejected, and a certificate is only accepted when it is
 * pinned for the group its SNI selects.
 */
public final class GroupPinnedTrustManager extends X509ExtendedTrustManager {

    private final Map<GroupId, Set<String>> expectedFingerprints;

    public GroupPinnedTrustManager(Map<GroupId, Set<String>> expectedFingerprints) {
        Objects.requireNonNull(expectedFingerprints, "expectedFingerprints");
        Map<GroupId, Set<String>> pinsByGroup = new ConcurrentHashMap<>();
        expectedFingerprints.forEach((group, fingerprints) -> {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(fingerprints, "fingerprints");
            Set<String> pins = ConcurrentHashMap.newKeySet();
            for (String fingerprint : fingerprints) {
                pins.add(normalize(fingerprint));
            }
            pinsByGroup.put(group, pins);
        });
        this.expectedFingerprints = pinsByGroup;
    }

    public void allow(GroupId groupId, String fingerprint) {
        pins(groupId).add(normalize(fingerprint));
    }

    public void revoke(GroupId groupId, String fingerprint) {
        if (fingerprint != null) {
            pins(groupId).remove(normalize(fingerprint));
        }
    }

    public Set<String> expectedFingerprints(GroupId groupId) {
        Set<String> pins = expectedFingerprints.get(Objects.requireNonNull(groupId, "groupId"));
        return pins == null ? Compat.set() : Compat.copySet(pins);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        GroupId groupId = selectGroup(socket);
        PinnedTrustManager.verifyPinned(chain, pins(groupId));
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        throw new CertificateException(
                "cannot verify an inbound client certificate without the SNI group of the TLS handshake");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        throw new CertificateException(
                "group routing requires an SSLSocket handshake session to read the SNI group;"
                        + " SSLEngine based servers are not supported");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        throw new CertificateException(
                "group routing verifies inbound clients only; use PinnedTrustManager for outbound connections");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        throw new CertificateException(
                "group routing verifies inbound clients only; use PinnedTrustManager for outbound connections");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        throw new CertificateException(
                "group routing verifies inbound clients only; use PinnedTrustManager for outbound connections");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    GroupId selectGroup(Socket socket) throws CertificateException {
        if (!(socket instanceof SSLSocket sslSocket)) {
            throw new CertificateException("group routing needs an SSLSocket handshake, got "
                    + (socket == null ? "no socket" : socket.getClass().getName()));
        }
        SSLSession handshakeSession = handshakeSession(sslSocket);
        if (handshakeSession == null) {
            throw new CertificateException(
                    "no TLS handshake session is available, so the SNI group cannot be selected");
        }
        return selectGroup(ServerNameIndication.hostName(handshakeSession).orElse(null));
    }

    GroupId selectGroup(String sniHostName) throws CertificateException {
        if (sniHostName == null || sniHostName.trim().isEmpty()) {
            throw new CertificateException("inbound TLS connection carries no SNI group id;"
                    + " the shared sync port only accepts connections that name a group");
        }
        GroupId groupId;
        try {
            groupId = GroupId.parse(sniHostName);
        } catch (IllegalArgumentException e) {
            throw new CertificateException(
                    "unknown SNI group id '" + sniHostName + "': not a valid group id", e);
        }
        if (!expectedFingerprints.containsKey(groupId)) {
            throw new CertificateException("unknown SNI group id '" + sniHostName
                    + "': this node does not route that group");
        }
        return groupId;
    }

    private static SSLSession handshakeSession(SSLSocket socket) {
        try {
            SSLSession handshake = socket.getHandshakeSession();
            if (handshake != null) {
                return handshake;
            }
        } catch (RuntimeException e) {
            // fall through to the established session below
        }
        try {
            return socket.getSession();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Set<String> pins(GroupId groupId) {
        Objects.requireNonNull(groupId, "groupId");
        Set<String> pins = expectedFingerprints.get(groupId);
        if (pins == null) {
            throw new IllegalArgumentException(
                    "group is not routed by this trust manager: " + groupId.value());
        }
        return pins;
    }

    private static String normalize(String fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (fingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("expected fingerprint must not be blank");
        }
        return fingerprint.toLowerCase(Locale.ROOT);
    }
}
