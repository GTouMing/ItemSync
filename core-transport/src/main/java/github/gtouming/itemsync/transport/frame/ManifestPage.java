package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.domain.model.ManifestEntry;

import java.util.List;
import java.util.Objects;

public record ManifestPage(List<ManifestEntry> entries, String nextCursor) {

    public ManifestPage {
        entries = Compat.copyList(Objects.requireNonNull(entries, "entries"));
    }
}
