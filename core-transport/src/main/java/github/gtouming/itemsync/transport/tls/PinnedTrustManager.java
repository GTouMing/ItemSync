package github.gtouming.itemsync.transport.tls;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Objects;

public final class PinnedTrustManager implements X509TrustManager {

    private final String expectedFingerprint;

    public PinnedTrustManager(String expectedFingerprint) {
        Objects.requireNonNull(expectedFingerprint, "expectedFingerprint");
        if (expectedFingerprint.isBlank()) {
            throw new IllegalArgumentException("expected fingerprint must not be blank");
        }
        this.expectedFingerprint = expectedFingerprint.toLowerCase(Locale.ROOT);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        verify(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        verify(chain);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    private void verify(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("peer presented no certificate");
        }
        X509Certificate leaf = chain[0];
        leaf.checkValidity();
        String actual = TlsIdentity.fingerprint(leaf);
        if (!actual.equals(expectedFingerprint)) {
            throw new CertificateException(
                    "certificate fingerprint mismatch: expected " + expectedFingerprint + ", got " + actual);
        }
    }
}
