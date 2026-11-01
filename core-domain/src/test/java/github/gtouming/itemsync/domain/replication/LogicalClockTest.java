package github.gtouming.itemsync.domain.replication;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicalClockTest {

    @Test
    void startsAtZero() {
        assertEquals(0L, LogicalClock.ZERO.value());
        assertSame(LogicalClock.ZERO, LogicalClock.of(0));
    }

    @Test
    void tickAdvancesByOne() {
        assertEquals(1L, LogicalClock.ZERO.tick().value());
        assertEquals(2L, LogicalClock.ZERO.tick().tick().value());
    }

    @Test
    void observeLargerRemoteJumpsAhead() {
        LogicalClock local = LogicalClock.of(3);

        assertEquals(11L, local.observe(10).value());
    }

    @Test
    void observeSmallerRemoteStillAdvancesByOne() {
        LogicalClock local = LogicalClock.of(10);

        assertEquals(11L, local.observe(3).value());
    }

    @Test
    void observeEqualAdvancesByOne() {
        LogicalClock local = LogicalClock.of(10);

        assertEquals(11L, local.observe(10).value());
    }

    @Test
    void neverRegressesAcrossMixedObservations() {
        LogicalClock clock = LogicalClock.ZERO;
        long previous = clock.value();

        for (long remote : new long[] {5, 3, 9, 1, 9, 100, 42}) {
            clock = clock.observe(remote);
            assertTrue(clock.value() > previous);
            assertTrue(clock.value() > remote);
            previous = clock.value();
        }
    }

    @Test
    void rejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> LogicalClock.of(-1));
        assertThrows(IllegalArgumentException.class, () -> LogicalClock.ZERO.observe(-1));
    }

    @Test
    void orderingIsByValue() {
        assertTrue(LogicalClock.of(2).compareTo(LogicalClock.of(3)) < 0);
        assertEquals(0, LogicalClock.of(3).compareTo(LogicalClock.of(3)));
    }

    @Test
    void overflowIsRejected() {
        LogicalClock maxed = LogicalClock.of(Long.MAX_VALUE);

        assertThrows(ArithmeticException.class, maxed::tick);
        assertThrows(ArithmeticException.class, () -> maxed.observe(Long.MAX_VALUE));
    }

    @Test
    void observeOverloadUsesRemoteValue() {
        LogicalClock local = LogicalClock.of(3);
        LogicalClock remote = LogicalClock.of(10);

        assertEquals(11L, local.observe(remote).value());
    }

    @Test
    void observeNullRemoteIsRejected() {
        assertThrows(NullPointerException.class, () -> LogicalClock.ZERO.observe((LogicalClock) null));
    }
}
