package github.gtouming.itemsync.domain.identity;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record BlobHash(String value) {

    public static final int LENGTH = 64;

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{" + LENGTH + "}");
    private static final int SHA_256_BYTES = 32;

    public BlobHash {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "blob hash must be " + LENGTH + " lowercase hex characters, got '" + value + "'");
        }
    }

    public static BlobHash parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        return new BlobHash(raw.trim().toLowerCase(Locale.ROOT));
    }

    public static BlobHash ofSha256(byte[] digest) {
        Objects.requireNonNull(digest, "digest");
        if (digest.length != SHA_256_BYTES) {
            throw new IllegalArgumentException(
                    "sha-256 digest must be " + SHA_256_BYTES + " bytes, got " + digest.length);
        }
        return new BlobHash(HexFormat.of().formatHex(digest));
    }

    @Override
    public String toString() {
        return value;
    }
}
