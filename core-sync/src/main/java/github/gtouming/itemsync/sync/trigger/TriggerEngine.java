package github.gtouming.itemsync.sync.trigger;

import github.gtouming.itemsync.domain.error.ErrorCode;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.model.SyncRun;
import github.gtouming.itemsync.domain.model.SyncRunState;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.store.metadata.MetadataStore;
import github.gtouming.itemsync.sync.engine.SyncRunResult;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class TriggerEngine implements AutoCloseable {

    @FunctionalInterface
    public interface SyncRunner {
        List<SyncRunResult> run() throws IOException;
    }

    @FunctionalInterface
    public interface ExclusiveRunner {
        List<SyncRunResult> run(SyncRunner work) throws IOException;
    }

    private final MetadataStore store;
    private final GroupId groupId;
    private final SyncRootId rootId;
    private final NodeId nodeId;
    private final SyncRunner runner;
    private final ExclusiveRunner exclusive;
    private final Supplier<Instant> clock;
    private final Duration debounce;
    private final AlarmScheduler alarms = new AlarmScheduler();
    private final ScheduledExecutorService debounceScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "itemsync-trigger-debounce");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<TriggerId, TriggerConfig> configs = new ConcurrentHashMap<>();
    private final Map<TriggerId, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile Instant suppressUntil = Instant.EPOCH;
    private volatile IOException lastError;

    public TriggerEngine(MetadataStore store, GroupId groupId, SyncRootId rootId, NodeId nodeId,
                         SyncRunner runner, ExclusiveRunner exclusive,
                         Supplier<Instant> clock, Duration debounce) {
        this.store = Objects.requireNonNull(store, "store");
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.rootId = Objects.requireNonNull(rootId, "rootId");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.exclusive = Objects.requireNonNull(exclusive, "exclusive");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.debounce = Objects.requireNonNull(debounce, "debounce");
        if (debounce.isNegative()) {
            throw new IllegalArgumentException("debounce must not be negative");
        }
    }

    public void registerAll(Collection<Trigger> triggers) {
        for (Trigger trigger : triggers) {
            register(trigger);
        }
    }

    public void register(Trigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        if (!trigger.enabled()) {
            return;
        }
        TriggerConfig config = TriggerConfigCodec.decode(trigger.configJson());
        configs.put(trigger.id(), config);
        if (config instanceof TriggerConfig.Alarm alarm) {
            alarms.schedule(trigger.id(), alarm.schedule(), () -> execute(trigger.id()));
        }
    }

    public TriggerConfig config(TriggerId id) {
        return configs.get(id);
    }

    public void onDirectoryChanged(TriggerId id) {
        onCondition(id);
    }

    public void onProcessEvent(TriggerId id) {
        onCondition(id);
    }

    public void executeNow(TriggerId id) {
        execute(id);
    }

    public IOException lastError() {
        return lastError;
    }

    @Override
    public void close() {
        for (ScheduledFuture<?> future : pending.values()) {
            future.cancel(false);
        }
        pending.clear();
        alarms.close();
        debounceScheduler.shutdownNow();
    }

    private void onCondition(TriggerId id) {
        if (running.get() || clock.get().isBefore(suppressUntil)) {
            return;
        }
        scheduleDebounced(id);
    }

    private void scheduleDebounced(TriggerId id) {
        pending.compute(id, (key, existing) -> {
            if (existing != null) {
                existing.cancel(false);
            }
            return debounceScheduler.schedule(() -> {
                pending.remove(key);
                execute(key);
            }, Math.max(0, debounce.toMillis()), TimeUnit.MILLISECONDS);
        });
    }

    private void execute(TriggerId id) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            exclusive.run(() -> {
                SyncRunId runId = new SyncRunId(UUID.randomUUID().toString());
                Instant started = clock.get();
                store.runs().insert(new SyncRun(runId, groupId, rootId, id, nodeId,
                        SyncRunState.SCANNING, started, null, null, null));
                try {
                    List<SyncRunResult> results = runner.run();
                    store.runs().update(new SyncRun(runId, groupId, rootId, id, nodeId,
                            SyncRunState.COMPLETED, started, clock.get(), stats(results), null));
                } catch (IOException | RuntimeException failure) {
                    store.runs().update(new SyncRun(runId, groupId, rootId, id, nodeId,
                            SyncRunState.FAILED, started, clock.get(), null,
                            ErrorCode.SYNC_RUN_FAILED));
                }
                return List.of();
            });
        } catch (IOException e) {
            lastError = e;
        } catch (RuntimeException e) {
            lastError = new IOException("trigger run failed", e);
        } finally {
            running.set(false);
            suppressUntil = clock.get().plus(debounce);
        }
    }

    private static String stats(List<SyncRunResult> results) {
        int pulled = 0;
        int deleted = 0;
        int conflicts = 0;
        int pendingPush = 0;
        for (SyncRunResult result : results) {
            pulled += result.pulled();
            deleted += result.deleted();
            conflicts += result.conflicts();
            pendingPush += result.pendingPush();
        }
        return "{\"pulled\":" + pulled
                + ",\"deleted\":" + deleted
                + ",\"conflicts\":" + conflicts
                + ",\"pendingPush\":" + pendingPush + "}";
    }
}
