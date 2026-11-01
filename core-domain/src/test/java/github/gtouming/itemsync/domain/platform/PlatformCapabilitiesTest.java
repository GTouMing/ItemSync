package github.gtouming.itemsync.domain.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformCapabilitiesTest {

    @Test
    void windowsSupportsProcessWatchButNotForegroundService() {
        assertTrue(PlatformCapabilities.supports(DevicePlatform.WINDOWS, PlatformCapability.PROCESS_WATCH));
        assertTrue(PlatformCapabilities.supports(DevicePlatform.WINDOWS, PlatformCapability.TRAY_UI));
        assertFalse(PlatformCapabilities.supports(DevicePlatform.WINDOWS, PlatformCapability.FOREGROUND_SERVICE));
        assertFalse(PlatformCapabilities.supports(DevicePlatform.WINDOWS, PlatformCapability.ALL_FILES_ACCESS));
    }

    @Test
    void androidSupportsForegroundServiceButNotProcessWatch() {
        assertTrue(PlatformCapabilities.supports(DevicePlatform.ANDROID, PlatformCapability.FOREGROUND_SERVICE));
        assertTrue(PlatformCapabilities.supports(DevicePlatform.ANDROID, PlatformCapability.ALL_FILES_ACCESS));
        assertTrue(PlatformCapabilities.supports(DevicePlatform.ANDROID, PlatformCapability.CASE_SENSITIVE_FILESYSTEM));
        assertFalse(PlatformCapabilities.supports(DevicePlatform.ANDROID, PlatformCapability.PROCESS_WATCH));
        assertFalse(PlatformCapabilities.supports(DevicePlatform.ANDROID, PlatformCapability.TRAY_UI));
    }

    @Test
    void bothPlatformsSupportAutostartAndDirectoryWatch() {
        for (DevicePlatform platform : DevicePlatform.values()) {
            assertTrue(PlatformCapabilities.supports(platform, PlatformCapability.AUTOSTART));
            assertTrue(PlatformCapabilities.supports(platform, PlatformCapability.DIRECTORY_WATCH));
            assertTrue(PlatformCapabilities.supports(platform, PlatformCapability.ALARM_SCHEDULING));
        }
    }

    @Test
    void capabilitySetsAreUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> PlatformCapabilities.of(DevicePlatform.WINDOWS).add(PlatformCapability.FOREGROUND_SERVICE));
    }
}
