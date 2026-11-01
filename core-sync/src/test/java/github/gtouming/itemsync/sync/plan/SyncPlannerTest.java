package github.gtouming.itemsync.sync.plan;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.domain.replication.VersionVector;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SyncPlannerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final BlobHash HASH_A = BlobHash.parse("a".repeat(64));
    private static final BlobHash HASH_B = BlobHash.parse("b".repeat(64));
    private static final NodeId NODE_A = NodeId.of("node-a");
    private static final NodeId NODE_B = NodeId.of("node-b");

    private final SyncPlanner planner = new SyncPlanner();

    @Test
    void pullsEntryMissingLocally() {
        assertInstanceOf(SyncAction.Pull.class,
                planner.decide(null, live("a.txt", HASH_A, vector(NODE_A, 1))));
    }

    @Test
    void pullsTombstoneMissingLocally() {
        assertInstanceOf(SyncAction.Pull.class,
                planner.decide(null, deleted("a.txt", vector(NODE_A, 1))));
    }

    @Test
    void pushesEntryMissingRemotely() {
        assertInstanceOf(SyncAction.Push.class,
                planner.decide(live("a.txt", HASH_A, vector(NODE_A, 1)), null));
    }

    @Test
    void pullsNewerRemoteFile() {
        assertInstanceOf(SyncAction.Pull.class, planner.decide(
                live("a.txt", HASH_A, vector(NODE_A, 1)),
                live("a.txt", HASH_B, vector(NODE_A, 2))));
    }

    @Test
    void deletesLocalWhenRemoteTombstoneIsNewer() {
        assertInstanceOf(SyncAction.DeleteLocal.class, planner.decide(
                live("a.txt", HASH_A, vector(NODE_A, 1)),
                deleted("a.txt", vector(NODE_A, 2))));
    }

    @Test
    void pushesNewerLocalFile() {
        assertInstanceOf(SyncAction.Push.class, planner.decide(
                live("a.txt", HASH_B, vector(NODE_A, 2)),
                live("a.txt", HASH_A, vector(NODE_A, 1))));
    }

    @Test
    void pushesNewerLocalTombstone() {
        assertInstanceOf(SyncAction.DeleteRemote.class, planner.decide(
                deleted("a.txt", vector(NODE_A, 2)),
                live("a.txt", HASH_A, vector(NODE_A, 1))));
    }

    @Test
    void skipsEqualVectors() {
        assertInstanceOf(SyncAction.Skip.class, planner.decide(
                live("a.txt", HASH_A, vector(NODE_A, 1)),
                live("a.txt", HASH_B, vector(NODE_A, 1))));
    }

    @Test
    void conflictsOnConcurrentVectors() {
        SyncAction action = planner.decide(
                live("a.txt", HASH_A, vector(NODE_A, 1)),
                live("a.txt", HASH_B, vector(NODE_B, 1)));

        assertInstanceOf(SyncAction.Conflict.class, action);
    }

    @Test
    void planCoversUnionAndSkipsEqualEntries() {
        Map<LogicalPath, ManifestEntry> local = Map.of(
                LogicalPath.of("a.txt"), live("a.txt", HASH_A, vector(NODE_A, 1)),
                LogicalPath.of("b.txt"), live("b.txt", HASH_B, vector(NODE_A, 2)),
                LogicalPath.of("c.txt"), live("c.txt", HASH_A, vector(NODE_A, 1)),
                LogicalPath.of("d.txt"), live("d.txt", HASH_A, vector(NODE_A, 1)));

        List<SyncAction> actions = planner.plan(local, List.of(
                live("a.txt", HASH_A, vector(NODE_A, 1)),
                live("b.txt", HASH_A, vector(NODE_A, 1)),
                live("d.txt", HASH_B, vector(NODE_B, 1)),
                live("f.txt", HASH_A, vector(NODE_A, 1))));

        assertEquals(List.of("b.txt", "c.txt", "d.txt", "f.txt"),
                actions.stream().map(action -> action.path().value()).toList());
        assertInstanceOf(SyncAction.Push.class, actions.get(0));
        assertInstanceOf(SyncAction.Push.class, actions.get(1));
        assertInstanceOf(SyncAction.Conflict.class, actions.get(2));
        assertInstanceOf(SyncAction.Pull.class, actions.get(3));
    }

    @Test
    void rejectsDuplicateRemoteEntries() {
        assertThrows(IllegalArgumentException.class, () -> planner.plan(
                Map.of(),
                List.of(live("a.txt", HASH_A, vector(NODE_A, 1)),
                        live("a.txt", HASH_B, vector(NODE_A, 2)))));
    }

    private static ManifestEntry live(String path, BlobHash hash, VersionVector vector) {
        return new ManifestEntry(LogicalPath.of(path), ItemKind.FILE, hash, 10, T0, vector, false);
    }

    private static ManifestEntry deleted(String path, VersionVector vector) {
        return new ManifestEntry(LogicalPath.of(path), ItemKind.FILE, null, 0, T0, vector, true);
    }

    private static VersionVector vector(NodeId node, long counter) {
        return VersionVector.of(Map.of(node, counter));
    }
}
