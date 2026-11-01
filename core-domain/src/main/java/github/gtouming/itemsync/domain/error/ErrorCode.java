package github.gtouming.itemsync.domain.error;

public enum ErrorCode {
    PATH_ENCODE_FAILED(false),
    PATH_DECODE_FAILED(false),
    BLOB_HASH_MISMATCH(true),
    BLOB_WRITE_FAILED(true),
    STORAGE_FULL(false),
    STORE_BUSY(true),
    PEER_UNREACHABLE(true),
    TLS_HANDSHAKE_FAILED(true),
    PROTOCOL_VERSION_MISMATCH(false),
    GROUP_ID_INVALID(false),
    GROUP_ID_RATE_LIMITED(true),
    CONSENT_REQUIRED(false),
    IGNORE_RULE_INVALID(false),
    TRANSFER_CANCELLED(false),
    SYNC_RUN_FAILED(true),
    UNKNOWN(false);

    private final boolean retryable;

    ErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
