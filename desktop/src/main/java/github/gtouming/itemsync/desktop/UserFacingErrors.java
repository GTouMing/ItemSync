package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.transport.tls.PinnedTrustManager;

import java.io.IOException;

final class UserFacingErrors {

    private UserFacingErrors() {
    }

    static String describe(IOException error) {
        if (error == null) {
            return "";
        }
        PinnedTrustManager.FingerprintMismatchException mismatch =
                PinnedTrustManager.findFingerprintMismatch(error);
        if (mismatch != null) {
            return "对端证书与已固定指纹不匹配（新指纹: "
                    + mismatch.actualFingerprint()
                    + "）。请在设备列表中找到该指纹的设备，确认无误后重新开启信任以固定新证书；"
                    + "如果这不是预期设备，请不要重新信任。";
        }
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
