package github.gtouming.itemsync.desktop;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CoalescingRefresherTest {

    @Test
    void coalescesRequestsWhileARefreshIsRunning() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger published = new AtomicInteger();

        try (CoalescingRefresher<String> refresher = new CoalescingRefresher<>(
                "test-refresh",
                force -> {
                    int run = loads.incrementAndGet();
                    if (run == 1) {
                        firstEntered.countDown();
                        awaitLatch(release);
                    }
                    return List.of("value-" + run);
                },
                (generation, items) -> published.incrementAndGet(),
                failure -> fail(failure))) {

            refresher.request(false);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 5; i++) {
                refresher.request(false);
            }
            release.countDown();

            await(() -> published.get() == 2, "two refreshes");
            Thread.sleep(200);
            assertEquals(2, loads.get());
            assertEquals(2, published.get());
        }
    }

    @Test
    void aForceRequestDropsTheOlderResultAndRunsAgain() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicBoolean secondWasForced = new AtomicBoolean();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger published = new AtomicInteger();
        AtomicLong lastGeneration = new AtomicLong(-1);

        try (CoalescingRefresher<String> refresher = new CoalescingRefresher<>(
                "test-refresh",
                force -> {
                    int run = loads.incrementAndGet();
                    if (run == 1) {
                        firstEntered.countDown();
                        awaitLatch(release);
                    } else {
                        secondWasForced.set(force);
                    }
                    return List.of("value-" + run);
                },
                (generation, items) -> {
                    lastGeneration.set(generation);
                    published.incrementAndGet();
                },
                failure -> fail(failure))) {

            refresher.request(false);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            refresher.request(true);
            release.countDown();

            await(() -> published.get() == 1, "the refreshed result");
            Thread.sleep(200);
            assertEquals(2, loads.get());
            assertEquals(1, published.get());
            assertTrue(secondWasForced.get());
            assertTrue(refresher.isCurrent(lastGeneration.get()));
            assertFalse(refresher.isCurrent(lastGeneration.get() - 1));
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("timed out waiting for " + description);
    }
}
