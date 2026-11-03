package github.gtouming.itemsync.platform.desktop;

import github.gtouming.itemsync.domain.identity.GroupId;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One persisted sync group of a desktop installation: the immutable group id, the local
 * group name (shown on this device only, never synced) and the single local sync root of
 * that group (ADR-0035).
 */
public record DesktopGroup(GroupId id, String name, Path root) {

    public DesktopGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(root, "root");
        if (root.toString().isBlank()) {
            throw new IllegalArgumentException("sync root must not be blank");
        }
        // A group without a name falls back to its id; the name never leaves this device.
        name = name == null || name.isBlank() ? id.value() : name.trim();
        root = root.toAbsolutePath().normalize();
    }
}
