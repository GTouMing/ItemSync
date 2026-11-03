package github.gtouming.itemsync.transport.net;

import java.io.IOException;

public final class PeerCertificateMismatchException extends IOException {

    private final String actualFingerprint;

    public PeerCertificateMismatchException(String actualFingerprint, Throwable cause) {
        super("peer certificate does not match the pinned fingerprint", cause);
        this.actualFingerprint = actualFingerprint;
    }

    public String actualFingerprint() {
        return actualFingerprint;
    }
}
