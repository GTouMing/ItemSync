package github.gtouming.itemsync.transport.tls;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsIdentityTest {

    private static TlsIdentity alice;
    private static TlsIdentity bob;

    @TempDir
    Path temp;

    @BeforeAll
    static void createIdentities() {
        alice = TlsIdentity.generate("alice");
        bob = TlsIdentity.generate("bob");
    }

    @Test
    void generatesStableFingerprints() {
        assertEquals(64, alice.fingerprint().length());
        assertEquals(alice.fingerprint(), TlsIdentity.fingerprint(alice.certificate()));
        assertNotEquals(alice.fingerprint(), bob.fingerprint());
        assertTrue(alice.fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    void pinnedTrustManagerAcceptsMatchingCertificate() throws Exception {
        PinnedTrustManager trustManager = new PinnedTrustManager(alice.fingerprint());

        trustManager.checkServerTrusted(new X509Certificate[] {alice.certificate()}, "RSA");
        trustManager.checkClientTrusted(new X509Certificate[] {alice.certificate()}, "RSA");
    }

    @Test
    void pinnedTrustManagerRejectsOtherCertificate() {
        PinnedTrustManager trustManager = new PinnedTrustManager(alice.fingerprint());

        assertThrows(CertificateException.class,
                () -> trustManager.checkServerTrusted(new X509Certificate[] {bob.certificate()}, "RSA"));
    }

    @Test
    void pinnedTrustManagerRejectsEmptyChain() {
        PinnedTrustManager trustManager = new PinnedTrustManager(alice.fingerprint());

        assertThrows(CertificateException.class,
                () -> trustManager.checkServerTrusted(new X509Certificate[0], "RSA"));
    }

    @Test
    void createsTls13Context() {
        assertNotNull(alice.sslContext(bob.fingerprint()));
    }

    @Test
    void savesAndLoadsIdentity() throws Exception {
        char[] password = "secret-pass".toCharArray();
        TlsIdentity generated = TlsIdentity.generate("persisted", password);
        Path file = temp.resolve("identity.p12");

        generated.save(file);
        TlsIdentity loaded = TlsIdentity.load(file, password);

        assertEquals(generated.fingerprint(), loaded.fingerprint());
        assertNotNull(loaded.sslContext(bob.fingerprint()));
    }

    @Test
    void rejectsWrongKeystorePassword() throws Exception {
        char[] password = "secret-pass".toCharArray();
        TlsIdentity generated = TlsIdentity.generate("persisted", password);
        Path file = temp.resolve("identity.p12");
        generated.save(file);

        assertThrows(IOException.class,
                () -> TlsIdentity.load(file, "wrong-password".toCharArray()));
    }
}
