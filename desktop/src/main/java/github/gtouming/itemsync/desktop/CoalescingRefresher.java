package github.gtouming.itemsync.desktop;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class CoalescingRefresher<T> implements AutoCloseable {

    @FunctionalInterface
    interface Loader<T> {
        List<T> load(boolean force);
    }

    @FunctionalInterface
    interface Publisher<T> {
        void publish(long generation, List<T> items);
    }

    private final ExecutorService executor;
    private final Loader<T> loader;
    private final Publisher<T> publisher;
    private final Consumer<RuntimeException> onError;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean pendingForce = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();

    CoalescingRefresher(String threadName, Loader<T> loader, Publisher<T> publisher,
                        Consumer<RuntimeException> onError) {
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        this.loader = Objects.requireNonNull(loader, "loader");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.onError = Objects.requireNonNull(onError, "onError");
    }

    void request(boolean force) {
        if (force) {
            generation.incrementAndGet();
            pendingForce.set(true);
        }
        if (running.getAndSet(true)) {
            pending.set(true);
            return;
        }
        execute(this::drain);
    }

    void submit(Runnable task) {
        execute(task);
    }

    boolean isCurrent(long expected) {
        return generation.get() == expected;
    }

    private void execute(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // the dialog is closing
        }
    }

    private void drain() {
        try {
            do {
                pending.set(false);
                boolean force = pendingForce.getAndSet(false);
                long expected = generation.get();
                List<T> items;
                try {
                    items = loader.load(force);
                } catch (RuntimeException e) {
                    onError.accept(e);
                    continue;
                }
                if (generation.get() == expected) {
                    publisher.publish(expected, items);
                }
            } while (pending.get());
        } finally {
            running.set(false);
            if (pending.get()) {
                request(false);
            }
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
