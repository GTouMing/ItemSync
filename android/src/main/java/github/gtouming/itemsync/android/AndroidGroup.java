package github.gtouming.itemsync.android;

import github.gtouming.itemsync.domain.identity.GroupId;

import java.io.File;
import java.util.Objects;

/**
 * One persisted sync group of the Android installation: the immutable group id, the local
 * name (never synced), the single local sync root and whether the group is enabled.
 */
public record AndroidGroup(GroupId id, String name, String rootPath, boolean enabled) {

    public AndroidGroup(GroupId id, String name, String rootPath) {
        this(id, name, rootPath, true);
    }

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
