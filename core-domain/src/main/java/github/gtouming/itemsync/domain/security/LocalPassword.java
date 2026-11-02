package github.gtouming.itemsync.domain.security;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class LocalPassword {

    private static final String ALGORITHM = "argon2id";
    private static final int VERSION = Argon2Parameters.ARGON2_VERSION_13;
    private static final int ITERATIONS = 3;
    private static final int MEMORY_KIB = 65536;
    private static final int PARALLELISM = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private LocalPassword() {
    }

    public static String hash(char[] password) {
        Objects.requireNonNull(password, "password");
        if (password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS, MEMORY_KIB, PARALLELISM);
        return ALGORITHM
                + "$v=" + VERSION
                + "$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM
                + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean verify(char[] password, String encoded) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(encoded, "encoded");
        Parsed parsed = parse(encoded);
        byte[] actual = derive(password, parsed.salt, parsed.iterations, parsed.memoryKib,
                parsed.parallelism);
        try {
            return MessageDigest.isEqual(actual, parsed.hash);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int memoryKib,
                                 int parallelism) {
        byte[] passwordBytes = utf8(password);
        try {
            Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(VERSION)
                    .withIterations(iterations)
                    .withMemoryAsKB(memoryKib)
                    .withParallelism(parallelism)
                    .withSalt(salt)
                    .build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(parameters);
            byte[] hash = new byte[HASH_BYTES];
            generator.generateBytes(passwordBytes, hash);
            return hash;
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }

    private static Parsed parse(String encoded) {
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 5 || !ALGORITHM.equals(parts[0])) {
            throw new IllegalArgumentException("invalid local password hash");
        }
        if (!("v=" + VERSION).equals(parts[1])) {
            throw new IllegalArgumentException("unsupported local password version");
        }
        Map<String, Integer> parameters = new HashMap<>();
        for (String entry : parts[2].split(",", -1)) {
            String[] pair = entry.split("=", -1);
            if (pair.length != 2) {
                throw new IllegalArgumentException("invalid local password parameters");
            }
            try {
                parameters.put(pair[0], Integer.parseInt(pair[1]));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid local password parameters", e);
            }
        }
        int memory = required(parameters, "m");
        int iterations = required(parameters, "t");
        int parallelism = required(parameters, "p");
        if (memory < 8192 || memory > 262144
                || iterations < 1 || iterations > 10
                || parallelism < 1 || parallelism > 4) {
            throw new IllegalArgumentException("local password parameters out of range");
        }
        byte[] salt;
        byte[] hash;
        try {
            salt = Base64.getDecoder().decode(parts[3]);
            hash = Base64.getDecoder().decode(parts[4]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid local password encoding", e);
        }
        if (salt.length < SALT_BYTES || hash.length != HASH_BYTES) {
            throw new IllegalArgumentException("invalid local password salt or hash length");
        }
        return new Parsed(salt, hash, iterations, memory, parallelism);
    }

    private static int required(Map<String, Integer> parameters, String key) {
        Integer value = parameters.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing local password parameter: " + key);
        }
        return value;
    }

    private static byte[] utf8(char[] password) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private record Parsed(byte[] salt, byte[] hash, int iterations, int memoryKib,
                          int parallelism) {
    }
}
