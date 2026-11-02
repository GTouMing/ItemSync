package github.gtouming.itemsync.domain.security;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

public final class NodeUnlockKey {

    private static final int LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] bytes;

    private NodeUnlockKey(byte[] bytes) {
        this.bytes = bytes;
    }

    public static NodeUnlockKey generate() {
        byte[] bytes = new byte[LENGTH];
        RANDOM.nextBytes(bytes);
        return new NodeUnlockKey(bytes);
    }

    public static NodeUnlockKey of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != LENGTH) {
            throw new IllegalArgumentException("NUK must be " + LENGTH + " bytes");
        }
        return new NodeUnlockKey(bytes.clone());
    }

    public static NodeUnlockKey fromBase64(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        try {
            return of(Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid NUK encoding", e);
        }
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String base64() {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public char[] keystorePassword() {
        return base64().toCharArray();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NodeUnlockKey that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "NodeUnlockKey[redacted]";
    }
}
