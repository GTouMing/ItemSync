package github.gtouming.itemsync.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

final class ProcessWatcher implements AutoCloseable {

    private final Set<String> executables;
    private final Consumer<String> onStarted;
    private final Consumer<String> onStopped;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "itemsync-process-watcher");
        thread.setDaemon(true);
        return thread;
    });

    private Set<String> running;

    ProcessWatcher(Set<String> executables, Consumer<String> onStarted, Consumer<String> onStopped,
                   Duration interval) {
        this.executables = Objects.requireNonNull(executables, "executables").stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.onStarted = Objects.requireNonNull(onStarted, "onStarted");
        this.onStopped = Objects.requireNonNull(onStopped, "onStopped");
        this.running = snapshot();
        long millis = Math.max(100, interval.toMillis());
        scheduler.scheduleWithFixedDelay(this::poll, millis, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private void poll() {
        Set<String> current = snapshot();
        ProcessDiff diff = ProcessDiff.between(running, current);
        running = current;
        for (String executable : diff.started()) {
            onStarted.accept(executable);
        }
        for (String executable : diff.stopped()) {
            onStopped.accept(executable);
        }
    }

    private Set<String> snapshot() {
        try {
            return ProcessHandle.allProcesses()
                    .map(ProcessHandle::info)
                    .map(info -> info.command().orElse(""))
                    .filter(command -> !command.isBlank())
                    .map(command -> Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT))
                    .filter(executables::contains)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }
}
