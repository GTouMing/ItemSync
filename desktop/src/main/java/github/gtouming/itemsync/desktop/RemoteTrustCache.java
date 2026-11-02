package github.gtouming.itemsync.desktop;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class RemoteTrustCache {

    private record Entry(RemoteTrust trust, Instant fetchedAt, Duration ttl) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Supplier<Instant> clock;

    RemoteTrustCache(Supplier<Instant> clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Optional<RemoteTrust> get(String fingerprint) {
        String key = key(fingerprint);
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (clock.get().isBefore(entry.fetchedAt().plus(entry.ttl()))) {
            return Optional.of(entry.trust());
        }
        entries.remove(key, entry);
        return Optional.empty();
    }

    void put(String fingerprint, RemoteTrust trust, Duration ttl) {
        Objects.requireNonNull(trust, "trust");
        Objects.requireNonNull(ttl, "ttl");
        entries.put(key(fingerprint), new Entry(trust, clock.get(), ttl));
    }

    void invalidate(String fingerprint) {
        entries.remove(key(fingerprint));
    }

    private static String key(String fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        String value = fingerprint.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
        return value;
    }
}
