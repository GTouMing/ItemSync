package github.gtouming.itemsync.sync.trust;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

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

        cache.put("fingerprint", RemoteTrust.TRUSTED, Duration.ofSeconds(30));

        assertEquals(RemoteTrust.TRUSTED, cache.get("fingerprint").orElseThrow());
    }

    @Test
    void expiresCachedValueAfterTtl() {
        Instant[] now = {T0};
        RemoteTrustCache cache = new RemoteTrustCache(() -> now[0]);
        cache.put("fingerprint", RemoteTrust.UNTRUSTED, Duration.ofSeconds(30));

        now[0] = T0.plusSeconds(30);

        assertTrue(cache.get("fingerprint").isEmpty());
    }

    @Test
    void invalidateRemovesTheEntry() {
        RemoteTrustCache cache = new RemoteTrustCache(() -> T0);
        cache.put("fingerprint", RemoteTrust.TRUSTED, Duration.ofSeconds(30));

        cache.invalidate("fingerprint");

        assertTrue(cache.get("fingerprint").isEmpty());
    }

    @Test
    void keysAreCaseInsensitive() {
        RemoteTrustCache cache = new RemoteTrustCache(() -> T0);
        cache.put("ABCDEF", RemoteTrust.TRUSTED, Duration.ofSeconds(30));

        assertEquals(RemoteTrust.TRUSTED, cache.get("abcdef").orElseThrow());
    }
}
