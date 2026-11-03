package github.gtouming.itemsync.domain.model;

import java.util.Objects;

public record SyncRoot(String physicalPath, String displayName, String ignoreSnapshot) {

    public SyncRoot {
        Objects.requireNonNull(physicalPath, "physicalPath");
        if (physicalPath.trim().isEmpty()) {
            throw new IllegalArgumentException("physical path must not be blank");
        }
    }
}
