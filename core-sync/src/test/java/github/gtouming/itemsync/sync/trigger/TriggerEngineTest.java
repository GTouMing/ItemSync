package github.gtouming.itemsync.sync.trigger;

import github.gtouming.itemsync.domain.error.ErrorCode;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.model.SyncRun;
import github.gtouming.itemsync.domain.model.SyncRunState;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.domain.model.TriggerType;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.engine.SyncRunResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class TriggerEngineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final GroupId GROUP = GroupId.parse("group001");
    private static final NodeId NODE = NodeId.of("node-a");
    private static final TriggerId TRIGGER = TriggerId.of("trig-1");

    @TempDir
    Path temp;

    private JdbcMetadataStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = JdbcMetadataStore.open("jdbc:sqlite:" + temp.resolve("meta.db").toAbsolutePath());
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void debounceRunsOnceAndRecordsCompletedRun() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        try (TriggerEngine engine = engine(
                () -> {
                    runs.incrementAndGet();
                    return List.of(new SyncRunResult(1, 2, 3, 4));
                },
                Duration.ofMillis(50))) {
            engine.register(directoryTrigger());

            engine.onDirectoryChanged(TRIGGER);
            engine.onDirectoryChanged(TRIGGER);
            engine.onDirectoryChanged(TRIGGER);

            await(() -> runs.get() == 1, 5000);
            Thread.sleep(150);
            assertEquals(1, runs.get());

            List<SyncRun> recorded = store.runs().recent(GROUP, 10);
            assertEquals(1, recorded.size());
            assertEquals(SyncRunState.COMPLETED, recorded.get(0).state());
            assertEquals(TRIGGER, recorded.get(0).triggerId());
            assertEquals(
                    "{\"pulled\":1,\"deleted\":2,\"conflicts\":3,\"pendingPush\":4}",
                    recorded.get(0).statsJson());
        }
    }

    @Test
    void recordsFailedRun() throws Exception {
        try (TriggerEngine engine = engine(
                () -> {
                    throw new IOException("boom");
                },
                Duration.ZERO)) {
            engine.register(directoryTrigger());

            engine.executeNow(TRIGGER);

            SyncRun run = store.runs().recent(GROUP, 1).get(0);
            assertEquals(SyncRunState.FAILED, run.state());
            assertEquals(ErrorCode.SYNC_RUN_FAILED, run.errorCode());
        }
    }

    @Test
    void suppressesConditionEventsAfterARun() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        try (TriggerEngine engine = engine(
                () -> {
                    runs.incrementAndGet();
                    return List.of(new SyncRunResult(1, 0, 0, 0));
                },
                Duration.ofMillis(50))) {
            engine.register(directoryTrigger());
            engine.executeNow(TRIGGER);

            engine.onDirectoryChanged(TRIGGER);
            Thread.sleep(150);

            assertEquals(1, runs.get());
        }
    }

    private TriggerEngine engine(TriggerEngine.SyncRunner runner, Duration debounce) {
        return new TriggerEngine(store, GROUP, NODE, runner, work -> work.run(),
                () -> T0, debounce);
    }

    private static Trigger directoryTrigger() {
        return new Trigger(TRIGGER, GROUP, TriggerType.CONDITION,
                TriggerConfigCodec.encode(new TriggerConfig.DirectoryChange()), true, null);
    }

    private static void await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("condition not met in time");
    }
}
