package github.gtouming.itemsync.transport.frame;

public record ManifestEnd(int totalEntries, long sequence) {

    public ManifestEnd {
        if (totalEntries < 0) {
            throw new IllegalArgumentException("total entries must not be negative, got " + totalEntries);
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative, got " + sequence);
        }
    }

    public ManifestEnd(int totalEntries) {
        this(totalEntries, 0L);
    }
}
