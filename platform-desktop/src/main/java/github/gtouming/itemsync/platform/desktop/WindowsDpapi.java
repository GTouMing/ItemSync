package github.gtouming.itemsync.platform.desktop;

import com.sun.jna.platform.win32.Crypt32Util;

public final class WindowsDpapi {

    private WindowsDpapi() {
    }

    public static byte[] protect(byte[] plaintext) {
        return Crypt32Util.cryptProtectData(plaintext);
    }

    public static byte[] unprotect(byte[] protectedBytes) {
        return Crypt32Util.cryptUnprotectData(protectedBytes);
    }
}
