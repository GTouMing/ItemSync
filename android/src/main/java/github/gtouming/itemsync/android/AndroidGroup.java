package github.gtouming.itemsync.android;

import github.gtouming.itemsync.domain.identity.GroupId;

import java.io.File;
import java.util.Objects;

/**
 * One persisted sync group of the Android installation: the immutable group id, the local
 * name (never synced) and the single local sync root of that group (ADR-0035).
 */
public record AndroidGroup(GroupId id, String name, String rootPath) {

    public AndroidGroup {
        Objects.requireNonNull(id, "id");
        if (rootPath == null || rootPath.trim().isEmpty()) {
            throw new IllegalArgumentException("sync root must not be blank");
        }
        name = name == null || name.isBlank() ? id.value() : name.trim();
        rootPath = new File(rootPath.trim()).getAbsolutePath();
    }

    public File root() {
        return new File(rootPath);
    }
}
