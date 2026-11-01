package github.gtouming.itemsync.sync.trigger;

import github.gtouming.itemsync.domain.identity.TriggerId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlarmSchedulerTest {

    @Test
    void firesScheduledTask() throws Exception {
        try (AlarmScheduler scheduler = new AlarmScheduler()) {
            CountDownLatch latch = new CountDownLatch(1);

            scheduler.scheduleAt(TriggerId.of("trig-1"), Instant.now().plusMillis(100), latch::countDown);

            assertTrue(latch.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelPreventsFiring() throws Exception {
        try (AlarmScheduler scheduler = new AlarmScheduler()) {
            CountDownLatch latch = new CountDownLatch(1);
            TriggerId id = TriggerId.of("trig-1");
            scheduler.scheduleAt(id, Instant.now().plusSeconds(1), latch::countDown);

            scheduler.cancel(id);

            assertFalse(latch.await(300, TimeUnit.MILLISECONDS));
        }
    }
}
