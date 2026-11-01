package github.gtouming.itemsync.store.metadata;

import github.gtouming.itemsync.domain.error.ErrorCode;
import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.ConflictId;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.Conflict;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.model.GroupMember;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.domain.model.Peer;
import github.gtouming.itemsync.domain.model.SyncGroup;
import github.gtouming.itemsync.domain.model.SyncRoot;
import github.gtouming.itemsync.domain.model.SyncRun;
import github.gtouming.itemsync.domain.model.SyncRunState;
import github.gtouming.itemsync.domain.model.Tombstone;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.domain.model.TriggerType;
import github.gtouming.itemsync.domain.platform.DevicePlatform;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.metadata.MetadataStore.ItemRepository.ItemPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class MetadataStoreContract {

    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    protected static final GroupId GROUP = GroupId.parse("group001");
    protected static final GroupId OTHER_GROUP = GroupId.parse("group002");
    protected static final NodeId NODE_A = NodeId.of("node-a");
    protected static final NodeId NODE_B = NodeId.of("node-b");
    protected static final SyncRootId ROOT = SyncRootId.of("root-1");
    protected static final ItemId ITEM = ItemId.of("item-1");
    protected static final LogicalPath PATH = LogicalPath.of("docs/a.txt");
    protected static final BlobHash BLOB = BlobHash.parse("a".repeat(64));

    private MetadataStore store;

    protected abstract MetadataStore openStore();

    @BeforeEach
    void openStoreForTest() {
        store = openStore();
    }

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void storesFindsAndDeletesPeer() {
        Peer peer = new Peer(NODE_A, DeviceId.of("device-a"), "Alpha",
                DevicePlatform.WINDOWS, "fingerprint-1", T0);

        store.peers().upsert(peer);

        assertEquals(Optional.of(peer), store.peers().find(NODE_A));
        assertEquals(List.of(peer), store.peers().findAll());

        store.peers().delete(NODE_A);

        assertTrue(store.peers().find(NODE_A).isEmpty());
        assertTrue(store.peers().findAll().isEmpty());
    }

    @Test
    void peerUpsertReplacesExistingRow() {
        store.peers().upsert(new Peer(NODE_A, DeviceId.of("device-a"), "Alpha",
                DevicePlatform.WINDOWS, "fingerprint-1", T0));
        Peer updated = new Peer(NODE_A, DeviceId.of("device-a"), "Alpha Renamed",
                DevicePlatform.WINDOWS, "fingerprint-2", T0.plusSeconds(60));

        store.peers().upsert(updated);

        assertEquals(Optional.of(updated), store.peers().find(NODE_A));
        assertEquals(1, store.peers().findAll().size());
    }

    @Test
    void storesGroupAndUpdatesLogicalClock() {
        SyncGroup group = new SyncGroup(GROUP, "Home", 0, T0);

        store.groups().insert(group);

        assertEquals(Optional.of(group), store.groups().find(GROUP));

        store.groups().updateLogicalClock(GROUP, 42);

        assertEquals(42, store.groups().find(GROUP).orElseThrow().logicalClock());
    }

    @Test
    void managesGroupMembers() {
        GroupMember first = new GroupMember(GROUP, NODE_A, "Alpha", false, false, T0);
        GroupMember second = new GroupMember(GROUP, NODE_B, "Beta", true, false, T0);

        store.groups().upsertMember(first);
        store.groups().upsertMember(second);

        assertEquals(Optional.of(first), store.groups().findMember(GROUP, NODE_A));
        assertEquals(List.of(first, second), store.groups().members(GROUP));

        store.groups().deleteMember(GROUP, NODE_A);

        assertEquals(List.of(second), store.groups().members(GROUP));
    }

    @Test
    void storesRootsPerGroup() {
        SyncRoot first = new SyncRoot(ROOT, GROUP, "C:\\Docs", "Docs", null, T0);
        SyncRoot second = new SyncRoot(SyncRootId.of("root-2"), GROUP, "C:\\Photos", "Photos", "*.tmp", T0);
        SyncRoot other = new SyncRoot(SyncRootId.of("root-3"), OTHER_GROUP, "D:\\Other", "Other", null, T0);

        store.roots().insert(first);
        store.roots().insert(second);
        store.roots().insert(other);

        assertEquals(Optional.of(first), store.roots().find(ROOT));
        assertEquals(List.of(first, second), store.roots().findByGroup(GROUP));
        assertEquals(List.of(other), store.roots().findByGroup(OTHER_GROUP));

        store.roots().updateIgnoreSnapshot(ROOT, "*.log");
        assertEquals("*.log", store.roots().find(ROOT).orElseThrow().ignoreSnapshot());

        store.roots().delete(SyncRootId.of("root-2"));
        assertEquals(1, store.roots().findByGroup(GROUP).size());
    }

    @Test
    void storesItemWithVector() {
        Item item = item(ITEM, PATH, "ver-1", Map.of(NODE_A, 2L, NODE_B, 1L), null);

        store.items().upsert(item);

        assertEquals(Optional.of(item), store.items().find(ROOT, PATH));
        assertEquals(Optional.of(item), store.items().findById(ITEM));
    }

    @Test
    void itemUpsertReplacesVector() {
        store.items().upsert(item(ITEM, PATH, "ver-1", Map.of(NODE_A, 1L), null));
        Item updated = item(ITEM, PATH, "ver-2", Map.of(NODE_A, 2L, NODE_B, 1L), T0);

        store.items().upsert(updated);

        assertEquals(Optional.of(updated), store.items().find(ROOT, PATH));
    }

    @Test
    void pagesItemsAfterCursor() {
        for (int i = 0; i < 5; i++) {
            store.items().upsert(item(ItemId.of("item-" + i), LogicalPath.of("f" + i),
                    "ver-" + i, Map.of(NODE_A, 1L), null));
        }

        ItemPage first = store.items().page(ROOT, null, 2);
        assertEquals(List.of("f0", "f1"), paths(first));
        assertEquals("f1", first.nextCursor());

        ItemPage second = store.items().page(ROOT, first.nextCursor(), 2);
        assertEquals(List.of("f2", "f3"), paths(second));
        assertEquals("f3", second.nextCursor());

        ItemPage third = store.items().page(ROOT, second.nextCursor(), 2);
        assertEquals(List.of("f4"), paths(third));
        assertNull(third.nextCursor());
    }

    @Test
    void deletesItem() {
        store.items().upsert(item(ITEM, PATH, "ver-1", Map.of(NODE_A, 1L), null));

        store.items().delete(ITEM);

        assertTrue(store.items().find(ROOT, PATH).isEmpty());
        assertTrue(store.items().findById(ITEM).isEmpty());
    }

    @Test
    void storesAndFindsVersion() {
        FileVersion version = version("ver-1", 0);

        store.versions().insert(version);

        assertEquals(Optional.of(version), store.versions().find(VersionId.of("ver-1")));
    }

    @Test
    void listsRecentVersionsNewestFirst() {
        store.versions().insert(version("ver-1", 0));
        store.versions().insert(version("ver-2", 1));
        store.versions().insert(version("ver-3", 2));

        List<FileVersion> recent = store.versions().recent(ITEM, 2);

        assertEquals(List.of("ver-3", "ver-2"),
                recent.stream().map(v -> v.id().value()).toList());
    }

    @Test
    void retentionKeepsNewestVersions() {
        for (int i = 0; i < 12; i++) {
            store.versions().insert(version("ver-" + i, i));
        }

        assertEquals(2, store.versions().deleteOlderThan(ITEM, 10));

        List<FileVersion> remaining = store.versions().recent(ITEM, 20);
        assertEquals(10, remaining.size());
        assertEquals("ver-11", remaining.get(0).id().value());
    }

    @Test
    void storesPagesAndDeletesTombstones() {
        Tombstone first = new Tombstone(ItemId.of("item-1"),
                VersionVector.of(Map.of(NODE_A, 1L)), T0, NODE_A);
        Tombstone second = new Tombstone(ItemId.of("item-2"),
                VersionVector.of(Map.of(NODE_A, 1L)), T0, NODE_A);

        store.tombstones().upsert(first);
        store.tombstones().upsert(second);

        assertEquals(Optional.of(first), store.tombstones().find(ItemId.of("item-1")));

        List<Tombstone> page = store.tombstones().page(null, 1);
        assertEquals(List.of(first), page);

        assertEquals(List.of(second), store.tombstones().page(page.get(0).itemId().value(), 10));

        store.tombstones().delete(ItemId.of("item-1"));
        assertTrue(store.tombstones().find(ItemId.of("item-1")).isEmpty());
    }

    @Test
    void resolvesConflicts() {
        Conflict first = new Conflict(ConflictId.of("conf-1"), ITEM, VersionId.of("ver-1"),
                VersionId.of("ver-2"), LogicalPath.of("docs/a (conflict node-a).txt"), T0, null);
        Conflict second = new Conflict(ConflictId.of("conf-2"), ITEM, VersionId.of("ver-1"),
                null, null, T0.plusSeconds(1), null);

        store.conflicts().insert(first);
        store.conflicts().insert(second);

        assertEquals(Optional.of(first), store.conflicts().find(ConflictId.of("conf-1")));
        assertEquals(2, store.conflicts().findOpen(10).size());
        assertEquals(2, store.conflicts().findByItem(ITEM).size());

        store.conflicts().resolve(ConflictId.of("conf-1"), T0.plusSeconds(5));

        assertEquals(1, store.conflicts().findOpen(10).size());
        assertEquals(T0.plusSeconds(5),
                store.conflicts().find(ConflictId.of("conf-1")).orElseThrow().resolvedAt());
    }

    @Test
    void listsDueTriggers() {
        Trigger due = trigger("trig-1", true, T0);
        Trigger future = trigger("trig-2", true, T0.plusSeconds(3600));
        Trigger disabled = trigger("trig-3", false, T0);

        store.triggers().upsert(due);
        store.triggers().upsert(future);
        store.triggers().upsert(disabled);

        assertEquals(Optional.of(due), store.triggers().find(TriggerId.of("trig-1")));
        assertEquals(List.of(due), store.triggers().due(T0.plusSeconds(60), 10));
        assertEquals(List.of(due, future, disabled), store.triggers().findByGroup(GROUP));

        store.triggers().delete(TriggerId.of("trig-1"));
        assertTrue(store.triggers().find(TriggerId.of("trig-1")).isEmpty());
    }

    @Test
    void storesAndUpdatesSyncRun() {
        SyncRun run = new SyncRun(SyncRunId.of("run-1"), GROUP, ROOT, TriggerId.of("trig-1"),
                NODE_A, SyncRunState.SCANNING, T0, null, null, null);

        store.runs().insert(run);
        assertEquals(Optional.of(run), store.runs().find(SyncRunId.of("run-1")));

        SyncRun finished = new SyncRun(SyncRunId.of("run-1"), GROUP, ROOT, TriggerId.of("trig-1"),
                NODE_A, SyncRunState.COMPLETED, T0, T0.plusSeconds(10),
                "{\"items\":3}", ErrorCode.BLOB_HASH_MISMATCH);

        store.runs().update(finished);

        assertEquals(Optional.of(finished), store.runs().find(SyncRunId.of("run-1")));
        assertEquals(List.of(finished), store.runs().recent(ROOT, 5));
    }

    @Test
    void transactionCommits() {
        SyncGroup group = new SyncGroup(GROUP, "Home", 0, T0);

        store.inTransaction(transaction -> {
            transaction.groups().insert(group);
            return null;
        });

        assertEquals(Optional.of(group), store.groups().find(GROUP));
    }

    @Test
    void transactionRollsBackOnException() {
        SyncGroup group = new SyncGroup(GROUP, "Home", 0, T0);

        assertThrows(IllegalStateException.class, () -> store.inTransaction(transaction -> {
            transaction.groups().insert(group);
            throw new IllegalStateException("boom");
        }));

        assertTrue(store.groups().find(GROUP).isEmpty());
    }

    @Test
    void nestedTransactionUsesOuterTransaction() {
        SyncGroup group = new SyncGroup(GROUP, "Home", 0, T0);
        SyncRoot root = new SyncRoot(ROOT, GROUP, "C:\\Docs", "Docs", null, T0);

        store.inTransaction(outer -> {
            outer.groups().insert(group);
            return outer.inTransaction(inner -> {
                inner.roots().insert(root);
                return null;
            });
        });

        assertEquals(Optional.of(group), store.groups().find(GROUP));
        assertEquals(Optional.of(root), store.roots().find(ROOT));
    }

    private Item item(ItemId id, LogicalPath path, String versionId,
                      Map<NodeId, Long> counters, Instant deletedAt) {
        return new Item(id, ROOT, path, ItemKind.FILE, VersionId.of(versionId),
                VersionVector.of(counters), deletedAt);
    }

    private FileVersion version(String id, int secondsAfterT0) {
        Instant createdAt = T0.plusSeconds(secondsAfterT0);
        return new FileVersion(VersionId.of(id), ITEM, BLOB, 42, T0, NODE_A,
                VersionVector.of(Map.of(NODE_A, secondsAfterT0 + 1L)),
                SyncRunId.of("run-1"), createdAt);
    }

    private Trigger trigger(String id, boolean enabled, Instant nextFireAt) {
        return new Trigger(TriggerId.of(id), GROUP, ROOT, TriggerType.ALARM, "{}", enabled, nextFireAt);
    }

    private static List<String> paths(ItemPage page) {
        return page.items().stream().map(item -> item.logicalPath().value()).toList();
    }
}
