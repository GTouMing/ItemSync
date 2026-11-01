package github.gtouming.itemsync.sync.engine;

public record SyncRunResult(int pulled, int deleted, int conflicts, int pendingPush) {
}
