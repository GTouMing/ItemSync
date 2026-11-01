package github.gtouming.itemsync.domain.platform;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class PlatformCapabilities {

    private static final Set<PlatformCapability> WINDOWS = Collections.unmodifiableSet(EnumSet.of(
            PlatformCapability.TRAY_UI,
            PlatformCapability.AUTOSTART,
            PlatformCapability.DIRECTORY_WATCH,
            PlatformCapability.PROCESS_WATCH,
            PlatformCapability.ALARM_SCHEDULING,
            PlatformCapability.LONG_PATH_SUPPORT));

    private static final Set<PlatformCapability> ANDROID = Collections.unmodifiableSet(EnumSet.of(
            PlatformCapability.AUTOSTART,
            PlatformCapability.FOREGROUND_SERVICE,
            PlatformCapability.DIRECTORY_WATCH,
            PlatformCapability.ALARM_SCHEDULING,
            PlatformCapability.ALL_FILES_ACCESS,
            PlatformCapability.CASE_SENSITIVE_FILESYSTEM));

    private PlatformCapabilities() {
    }

    public static Set<PlatformCapability> of(DevicePlatform platform) {
        Objects.requireNonNull(platform, "platform");
        return switch (platform) {
            case WINDOWS -> WINDOWS;
            case ANDROID -> ANDROID;
        };
    }

    public static boolean supports(DevicePlatform platform, PlatformCapability capability) {
        Objects.requireNonNull(capability, "capability");
        return of(platform).contains(capability);
    }
}
