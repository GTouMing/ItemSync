package github.gtouming.itemsync.transport.tls;

import github.gtouming.itemsync.domain.compat.Compat;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PinnedTrustManager implements X509TrustManager {

    private final Set<String> expectedFingerprints = ConcurrentHashMap.newKeySet();

    public PinnedTrustManager(String expectedFingerprint) {
        this(Compat.set(expectedFingerprint));
    }

    public PinnedTrustManager(Set<String> expectedFingerprints) {
        Objects.requireNonNull(expectedFingerprints, "expectedFingerprints");
        if (expectedFingerprints.isEmpty()) {
            throw new IllegalArgumentException("expected fingerprints must not be empty");
        }
        for (String fingerprint : expectedFingerprints) {
            allow(fingerprint);
        }
    }

    public void allow(String fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (fingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("expected fingerprint must not be blank");
        }
        expectedFingerprints.add(fingerprint.toLowerCase(Locale.ROOT));
    }

    public void revoke(String fingerprint) {
        if (fingerprint != null) {
            expectedFingerprints.remove(fingerprint.toLowerCase(Locale.ROOT));
        }
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
        if (!expectedFingerprints.contains(actual)) {
            throw new FingerprintMismatchException(actual,
                    "certificate fingerprint mismatch: expected one of "
                            + expectedFingerprints + ", got " + actual);
        }
    }

    public static FingerprintMismatchException findFingerprintMismatch(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof FingerprintMismatchException mismatch) {
                return mismatch;
            }
            current = current.getCause();
        }
        return null;
    }

    public static final class FingerprintMismatchException extends CertificateException {

        private final String actualFingerprint;

        private FingerprintMismatchException(String actualFingerprint, String message) {
            super(message);
            this.actualFingerprint = actualFingerprint;
        }

        public String actualFingerprint() {
            return actualFingerprint;
        }
    }
}
