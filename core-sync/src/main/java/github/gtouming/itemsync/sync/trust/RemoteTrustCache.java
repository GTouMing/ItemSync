package github.gtouming.itemsync.sync.trust;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class RemoteTrustCache {

    private record Entry(RemoteTrustOutcome outcome, Instant fetchedAt, Duration ttl) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Supplier<Instant> clock;

    RemoteTrustCache(Supplier<Instant> clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Optional<RemoteTrustOutcome> get(String fingerprint) {
        String key = key(fingerprint);
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (clock.get().isBefore(entry.fetchedAt().plus(entry.ttl()))) {
            return Optional.of(entry.outcome());
        }
        entries.remove(key, entry);
        return Optional.empty();
    }

    void put(String fingerprint, RemoteTrustOutcome outcome, Duration ttl) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(ttl, "ttl");
        entries.put(key(fingerprint), new Entry(outcome, clock.get(), ttl));
    }

    void invalidate(String fingerprint) {
        entries.remove(key(fingerprint));
    }

    void prune(Set<String> fingerprints) {
        Set<String> keep = new HashSet<>();
        for (String fingerprint : fingerprints) {
            keep.add(key(fingerprint));
        }
        entries.keySet().retainAll(keep);
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
