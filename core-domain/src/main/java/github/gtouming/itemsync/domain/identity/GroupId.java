package github.gtouming.itemsync.domain.identity;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record GroupId(String value) {

    public static final int LENGTH = 8;

    private static final Pattern FORMAT = Pattern.compile("[0-9a-z]{" + LENGTH + "}");
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    public GroupId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "group id must be " + LENGTH + " characters of [0-9a-z], got '" + value + "'");
        }
    }

    public static GroupId parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        return new GroupId(raw.trim().toLowerCase(Locale.ROOT));
    }

    public static GroupId random() {
        return random(new SecureRandom());
    }

    public static GroupId random(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        char[] chars = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new GroupId(new String(chars));
    }

    @Override
    public String toString() {
        return value;
    }
}
