package github.gtouming.itemsync.platform.desktop;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSecurityTest {

    @TempDir
    Path temp;

    @Test
    void storesAndVerifiesTheLocalPassword() throws Exception {
        DesktopSecurity security = new DesktopSecurity(temp);

        security.setLocalPassword("correct horse".toCharArray());

        assertTrue(security.hasLocalPassword());
        assertTrue(security.verifyLocalPassword("correct horse".toCharArray()));
        assertFalse(security.verifyLocalPassword("wrong horse".toCharArray()));
    }

    @Test
    void fallsBackWhenNoNukExists() throws Exception {
        DesktopSecurity security = new DesktopSecurity(temp);

        assertFalse(security.hasNuk());
        assertNull(security.loadNuk());
        assertArrayEquals("legacy".toCharArray(),
                security.identitySecret("legacy".toCharArray()));
    }

    @Test
    void roundTripsTheNukThroughDpapi() throws Exception {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));

        DesktopSecurity security = new DesktopSecurity(temp);
        char[] secret = "migrated-identity-secret".toCharArray();

        security.saveNuk(secret);

        assertTrue(security.hasNuk());
        assertArrayEquals(secret, security.loadNuk());
        assertArrayEquals(secret, security.identitySecret("fallback".toCharArray()));
    }
}
