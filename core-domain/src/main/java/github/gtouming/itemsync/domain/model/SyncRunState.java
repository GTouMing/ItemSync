package github.gtouming.itemsync.domain.model;

public enum SyncRunState {
    PENDING,
    QUEUED,
    SCANNING,
    PLANNING,
    TRANSFERRING,
    APPLYING,
    REVERSE_PUSH,
    COMPLETED,
    FAILED,
    CANCELLED
}
