package github.gtouming.itemsync.transport.pairing;

import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * Accepts any certificate. This is only safe on the metadata-only pairing endpoint,
 * which never serves manifests, blobs, or sync data.
 */
public final class PairingTrustManager implements X509TrustManager {

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {
        // The pairing endpoint identifies the caller by the certificate fingerprint.
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {
        // The pairing endpoint is never used as a client TLS configuration.
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
