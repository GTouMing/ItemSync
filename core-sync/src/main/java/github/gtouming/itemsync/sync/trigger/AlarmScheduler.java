package github.gtouming.itemsync.sync.trigger;

import github.gtouming.itemsync.domain.identity.TriggerId;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class AlarmScheduler implements AutoCloseable {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "itemsync-alarm-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<TriggerId, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public void schedule(TriggerId id, AlarmSchedule schedule, Runnable task) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(task, "task");
        cancel(id);
        scheduleNext(id, schedule, task);
    }

    public void cancel(TriggerId id) {
        ScheduledFuture<?> future = futures.remove(id);
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public void close() {
        futures.values().forEach(future -> future.cancel(false));
        futures.clear();
        executor.shutdownNow();
    }

    void scheduleAt(TriggerId id, Instant when, Runnable task) {
        long delay = Math.max(0, Duration.between(Instant.now(), when).toMillis());
        ScheduledFuture<?> future = executor.schedule(task, delay, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = futures.put(id, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void scheduleNext(TriggerId id, AlarmSchedule schedule, Runnable task) {
        Instant now = Instant.now();
        Instant next = schedule.nextFire(now);
        long delay = Math.max(0, Duration.between(now, next).toMillis());
        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                task.run();
            } finally {
                scheduleNext(id, schedule, task);
            }
        }, delay, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = futures.put(id, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }
}
