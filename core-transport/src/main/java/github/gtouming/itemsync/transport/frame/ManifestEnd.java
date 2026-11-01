package github.gtouming.itemsync.transport.frame;

public record ManifestEnd(int totalEntries) {

    public ManifestEnd {
        if (totalEntries < 0) {
            throw new IllegalArgumentException("total entries must not be negative, got " + totalEntries);
        }
    }
}
