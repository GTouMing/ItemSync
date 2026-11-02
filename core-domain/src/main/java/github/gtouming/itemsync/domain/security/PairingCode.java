package github.gtouming.itemsync.domain.security;

import github.gtouming.itemsync.domain.identity.GroupId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

public final class PairingCode {

    private PairingCode() {
    }

    public static String between(GroupId group, String localFingerprint, String peerFingerprint) {
        Objects.requireNonNull(group, "group");
        String first = normalized(localFingerprint);
        String second = normalized(peerFingerprint);
        String ordered = first.compareTo(second) <= 0
                ? first + "\n" + second
                : second + "\n" + first;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((group.value() + "\n" + ordered).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 6; i++) {
                value = (value << 8) | (digest[i] & 0xFFL);
            }
            return String.format(Locale.ROOT, "%06d", value % 1_000_000L);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String normalized(String fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        String value = fingerprint.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
        return value;
    }
}
