package github.gtouming.itemsync.transport.pairing;

import java.io.IOException;

public final class PairingStatusException extends IOException {

    public enum Reason {
        TIMEOUT,
        IDENTITY_MISMATCH,
        CONNECTION_FAILED
    }

    private final Reason reason;

    public PairingStatusException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PairingStatusException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
