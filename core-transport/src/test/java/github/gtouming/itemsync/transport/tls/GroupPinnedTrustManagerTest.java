package github.gtouming.itemsync.transport.tls;

import github.gtouming.itemsync.domain.identity.GroupId;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupPinnedTrustManagerTest {

    private static final GroupId GROUP_A = GroupId.parse("group001");
    private static final GroupId GROUP_B = GroupId.parse("group002");

    @Test
    void selectsThePinsOfTheGroupNamedBySni() throws Exception {
        GroupPinnedTrustManager trustManager = trustManager();

        assertEquals(GROUP_A, trustManager.selectGroup(GROUP_A.value()));
        assertEquals(GROUP_B, trustManager.selectGroup(GROUP_B.value()));
        assertEquals(Set.of("fp-a"), trustManager.expectedFingerprints(GROUP_A));
    }

    @Test
    void rejectsMissingSni() {
        GroupPinnedTrustManager trustManager = trustManager();

        CertificateException missing = assertThrows(CertificateException.class,
                () -> trustManager.selectGroup((String) null));
        assertTrue(missing.getMessage().contains("SNI"), missing.getMessage());

        CertificateException blank = assertThrows(CertificateException.class,
                () -> trustManager.selectGroup("  "));
        assertTrue(blank.getMessage().contains("SNI"), blank.getMessage());
    }

    @Test
    void rejectsAnSniGroupThatIsNotRouted() {
        GroupPinnedTrustManager trustManager = trustManager();

        CertificateException unknown = assertThrows(CertificateException.class,
                () -> trustManager.selectGroup(GROUP_A.value().replace('1', '9')));
        assertTrue(unknown.getMessage().contains("does not route"), unknown.getMessage());
    }

    @Test
    void rejectsAnSniValueThatIsNotAGroupId() {
        GroupPinnedTrustManager trustManager = trustManager();

        CertificateException malformed = assertThrows(CertificateException.class,
                () -> trustManager.selectGroup("example.invalid"));
        assertTrue(malformed.getMessage().contains("not a valid group id"), malformed.getMessage());
    }

    @Test
    void rejectsANonTlsSocket() {
        GroupPinnedTrustManager trustManager = trustManager();

        CertificateException failure = assertThrows(CertificateException.class,
                () -> trustManager.selectGroup(new Socket()));
        assertTrue(failure.getMessage().contains("SSLSocket"), failure.getMessage());
    }

    @Test
    void doesNotVerifyWithoutAHandshakeSession() {
        GroupPinnedTrustManager trustManager = trustManager();

        assertThrows(CertificateException.class,
                () -> trustManager.checkClientTrusted(new X509Certificate[0], "RSA"));
    }

    @Test
    void doesNotVerifyOutboundCertificates() {
        GroupPinnedTrustManager trustManager = trustManager();

        assertThrows(CertificateException.class,
                () -> trustManager.checkServerTrusted(new X509Certificate[0], "RSA"));
    }

    @Test
    void allowAndRevokeChangeOnlyTheNamedGroup() {
        GroupPinnedTrustManager trustManager = trustManager();

        trustManager.allow(GROUP_A, "FP-NEW");
        assertTrue(trustManager.expectedFingerprints(GROUP_A).contains("fp-new"));
        assertFalse(trustManager.expectedFingerprints(GROUP_B).contains("fp-new"));

        trustManager.revoke(GROUP_A, "fp-new");
        assertEquals(Set.of("fp-a"), trustManager.expectedFingerprints(GROUP_A));
    }

    @Test
    void rejectsPinsForAGroupItDoesNotRoute() {
        GroupPinnedTrustManager trustManager = trustManager();

        assertThrows(IllegalArgumentException.class,
                () -> trustManager.allow(GroupId.parse("group009"), "fp"));
    }

    private static GroupPinnedTrustManager trustManager() {
        return new GroupPinnedTrustManager(Map.of(
                GROUP_A, Set.of("fp-a"),
                GROUP_B, Set.of("fp-b")));
    }
}
