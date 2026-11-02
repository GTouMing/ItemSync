package github.gtouming.itemsync.sync.engine.transport;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.model.ManifestEntry;

import java.util.List;
import java.util.Objects;

public record ManifestSnapshot(List<ManifestEntry> entries, long sequence) {

    public ManifestSnapshot {
        entries = Compat.copyList(Objects.requireNonNull(entries, "entries"));
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative: " + sequence);
        }
    }
}
