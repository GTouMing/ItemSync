package github.gtouming.itemsync.sync.apply;

import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.platform.DevicePlatform;
import github.gtouming.itemsync.sync.path.PathCodec;

import java.nio.file.Path;

public final class PathResolvers {

    private PathResolvers() {
    }

    public static PhysicalPathResolver windows() {
        return (syncRoot, logicalPath) -> {
            Path resolved = syncRoot;
            for (String segment : PathCodec.encodeWindowsSegments(logicalPath)) {
                resolved = resolved.resolve(segment);
            }
            return resolved;
        };
    }

    public static PhysicalPathResolver posix() {
        return (syncRoot, logicalPath) -> {
            Path resolved = syncRoot;
            for (String segment : logicalPath.segments()) {
                resolved = resolved.resolve(segment);
            }
            return resolved;
        };
    }

    public static PhysicalPathResolver forPlatform(DevicePlatform platform) {
        return switch (platform) {
            case WINDOWS -> windows();
            case ANDROID -> posix();
        };
    }
}
