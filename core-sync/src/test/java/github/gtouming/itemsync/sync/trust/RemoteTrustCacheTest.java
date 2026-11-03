package github.gtouming.itemsync.sync.trust;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteTrustCacheTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void returnsEmptyWhenNothingWasCached() {
        RemoteTrustCache cache = new RemoteTrustCache(() -> T0);

        assertTrue(cache.get("fingerprint").isEmpty());
    }

    @Test
    void returnsCachedValueWithinTtl() {
        RemoteTrustCache cache = new RemoteTrustCache(() -> T0);

        cache.put("fingerprint", RemoteTrustOutcome.trusted(), Duration.ofSeconds(30));

        assertEquals(RemoteTrust.TRUSTED, cache.get("fingerprint").orElseThrow().trust());
    }

    @Test
    void expiresCachedValueAfterTtl() {
        Instant[] now = {T0};
        RemoteTrustCache cache = new RemoteTrustCache(() -> now[0]);
        cache.put("fingerprint", RemoteTrustOutcome.untrusted(), Duration.ofSeconds(30));

        now[0] = T0.plusSeconds(30);

        assertTrue(cache.get("fingerprint").isEmpty());
    }

    @Test
    void invalidateRemovesTheEntry() {
        RemoteTrustCache cache = new RemoteTrustCache(() -> T0);
        cache.put("fingerprint", RemoteTrustOutcome.trusted(), Duration.ofSeconds(30));

        cache.invalidate("fingerprint");

        assertTrue(cache.get("fingerprint").isEmpty());
    }

    @Test
    void keysAreCaseInsensitive() {
        RemoteTrustCache cache = new RemoteTrustCache(() -> T0);
        cache.put("ABCDEF", RemoteTrustOutcome.trusted(), Duration.ofSeconds(30));

        assertEquals(RemoteTrust.TRUSTED, cache.get("abcdef").orElseThrow().trust());
    }

    @Test
    void pruneRemovesOfflineFingerprints() {
        RemoteTrustCache cache = new RemoteTrustCache(() -> T0);
        cache.put("online", RemoteTrustOutcome.trusted(), Duration.ofSeconds(30));
        cache.put("offline", RemoteTrustOutcome.unknown(
                RemoteTrustFailure.QUERY_TIMEOUT, "timed out"), Duration.ofSeconds(5));

        cache.prune(Set.of("online"));

        assertEquals(RemoteTrust.TRUSTED, cache.get("online").orElseThrow().trust());
        assertTrue(cache.get("offline").isEmpty());
    }
}
