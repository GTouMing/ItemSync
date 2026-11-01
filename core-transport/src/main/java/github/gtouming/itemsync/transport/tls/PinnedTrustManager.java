package github.gtouming.itemsync.transport.tls;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class PinnedTrustManager implements X509TrustManager {

    private final Set<String> expectedFingerprints;

    public PinnedTrustManager(String expectedFingerprint) {
        this(Set.of(expectedFingerprint));
    }

    public PinnedTrustManager(Set<String> expectedFingerprints) {
        Objects.requireNonNull(expectedFingerprints, "expectedFingerprints");
        if (expectedFingerprints.isEmpty()) {
            throw new IllegalArgumentException("expected fingerprints must not be empty");
        }
        this.expectedFingerprints = expectedFingerprints.stream()
                .map(fingerprint -> {
                    Objects.requireNonNull(fingerprint, "fingerprint");
                    if (fingerprint.isBlank()) {
                        throw new IllegalArgumentException("expected fingerprint must not be blank");
                    }
                    return fingerprint.toLowerCase(Locale.ROOT);
                })
                .collect(Collectors.toUnmodifiableSet());
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
            throw new CertificateException(
                    "certificate fingerprint mismatch: expected one of "
                            + expectedFingerprints + ", got " + actual);
        }
    }
}
