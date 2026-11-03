package github.gtouming.itemsync.store.metadata;

import github.gtouming.itemsync.domain.error.ErrorCode;
import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.ConflictId;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.Conflict;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.model.GroupDevice;
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
import github.gtouming.itemsync.store.metadata.MetadataStore.ItemRepository;
import github.gtouming.itemsync.store.metadata.MetadataStore.ItemRepository.ItemPage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class MetadataStoreConformance {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final GroupId GROUP = GroupId.parse("group001");
    private static final GroupId OTHER_GROUP = GroupId.parse("group002");
    private static final NodeId NODE_A = NodeId.of("node-a");
    private static final NodeId NODE_B = NodeId.of("node-b");
    private static final DeviceId DEVICE_A = DeviceId.of("device-a");
    private static final DeviceId DEVICE_B = DeviceId.of("device-b");
    private static final ItemId ITEM = ItemId.of("item-1");
    private static final LogicalPath PATH = LogicalPath.of("docs/a.txt");
    private static final BlobHash BLOB = BlobHash.parse("a".repeat(64));

    private final MetadataStore store;

    public MetadataStoreConformance(MetadataStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public void storesFindsAndDeletesPeer() {
        Peer peer = new Peer(NODE_A, DeviceId.of("device-a"), "Alpha",
                DevicePlatform.WINDOWS, "fingerprint-1", T0);

        store.peers().upsert(peer);

        assertEquals(Optional.of(peer), store.peers().find(NODE_A));
        assertEquals(List.of(peer), store.peers().findAll());

        store.peers().delete(NODE_A);

        assertTrue(store.peers().find(NODE_A).isEmpty());
        assertTrue(store.peers().findAll().isEmpty());
    }

    public void peerUpsertReplacesExistingRow() {
        store.peers().upsert(new Peer(NODE_A, DeviceId.of("device-a"), "Alpha",
                DevicePlatform.WINDOWS, "fingerprint-1", T0));
        Peer updated = new Peer(NODE_A, DeviceId.of("device-a"), "Alpha Renamed",
                DevicePlatform.WINDOWS, "fingerprint-2", T0.plusSeconds(60));

        store.peers().upsert(updated);

        assertEquals(Optional.of(updated), store.peers().find(NODE_A));
        assertEquals(1, store.peers().findAll().size());
    }

    public void storesGroupAndUpdatesLogicalClock() {
        SyncGroup group = new SyncGroup(GROUP, "Home", root("C:\\Docs"), 0, T0);

        store.groups().insert(group);

        assertEquals(Optional.of(group), store.groups().find(GROUP));

        store.groups().updateLogicalClock(GROUP, 42);

        assertEquals(42, store.groups().find(GROUP).orElseThrow().logicalClock());
    }

    public void managesGroupDevices() {
        GroupDevice first = new GroupDevice(GROUP, DEVICE_A, "Alpha", "fingerprint-1", T0, T0);
        GroupDevice second = new GroupDevice(GROUP, DEVICE_B, "Beta", "fingerprint-2",
                T0.plusSeconds(30), T0.plusSeconds(5));

        store.devices().upsert(first);
        store.devices().upsert(second);

        assertEquals(Optional.of(first), store.devices().find(GROUP, DEVICE_A));
        assertEquals(List.of(first, second), store.devices().findByGroup(GROUP));
        assertTrue(store.devices().find(OTHER_GROUP, DEVICE_A).isEmpty());

        store.devices().delete(GROUP, DEVICE_A);

        assertEquals(List.of(second), store.devices().findByGroup(GROUP));
    }

    public void storesGroupRootAndListsGroups() {
        SyncGroup first = new SyncGroup(GROUP, "Home", root("C:\\Docs"), 0, T0);
        SyncGroup second = new SyncGroup(OTHER_GROUP, "Other",
                new SyncRoot("D:\\Other", "Other", "*.tmp"), 3, T0.plusSeconds(60));

        store.groups().insert(second);
        store.groups().insert(first);

        assertEquals(Optional.of(first), store.groups().find(GROUP));
        assertEquals(List.of(first, second), store.groups().findAll());
        assertEquals(new SyncRoot("D:\\Other", "Other", "*.tmp"),
                store.groups().find(OTHER_GROUP).orElseThrow().root());

        store.groups().delete(OTHER_GROUP);

        assertEquals(List.of(first), store.groups().findAll());
        assertTrue(store.groups().find(OTHER_GROUP).isEmpty());
    }

    public void storesItemWithVector() {
        Item item = item(ITEM, PATH, "ver-1", Map.of(NODE_A, 2L, NODE_B, 1L), null);

        store.items().upsert(item);

        assertEquals(Optional.of(item), store.items().find(GROUP, PATH));
        assertEquals(Optional.of(item), store.items().findById(ITEM));
    }

    public void itemUpsertReplacesVector() {
        store.items().upsert(item(ITEM, PATH, "ver-1", Map.of(NODE_A, 1L), null));
        Item updated = item(ITEM, PATH, "ver-2", Map.of(NODE_A, 2L, NODE_B, 1L), T0);

        store.items().upsert(updated);

        assertEquals(Optional.of(updated), store.items().find(GROUP, PATH));
    }

    public void pagesItemsAfterCursor() {
        for (int i = 0; i < 5; i++) {
            store.items().upsert(item(ItemId.of("item-" + i), LogicalPath.of("f" + i),
                    "ver-" + i, Map.of(NODE_A, 1L), null));
        }

        ItemPage first = store.items().page(GROUP, null, 2);
        assertEquals(List.of("f0", "f1"), paths(first));
        assertEquals("f1", first.nextCursor());

        ItemPage second = store.items().page(GROUP, first.nextCursor(), 2);
        assertEquals(List.of("f2", "f3"), paths(second));
        assertEquals("f3", second.nextCursor());

        ItemPage third = store.items().page(GROUP, second.nextCursor(), 2);
        assertEquals(List.of("f4"), paths(third));
        assertNull(third.nextCursor());
    }

    public void advancesItemChangeSequenceAndReturnsOnlyChangedItems() {
        long before = store.items().currentSequence(GROUP);

        store.items().upsert(item(ITEM, PATH, "ver-1", Map.of(NODE_A, 1L), null));

        long first = store.items().currentSequence(GROUP);
        assertTrue(first > before);
        List<ItemRepository.ChangedItem> changed = store.items().changedSince(GROUP, before, first);
        assertEquals(1, changed.size());
        assertEquals(PATH, changed.get(0).item().logicalPath());
        assertTrue(store.items().changedSince(GROUP, first, first).isEmpty());

        LogicalPath next = LogicalPath.of("docs/b.txt");
        store.items().upsert(item(ItemId.of("item-2"), next, "ver-2",
                Map.of(NODE_A, 2L), null));

        long second = store.items().currentSequence(GROUP);
        assertTrue(second > first);
        List<ItemRepository.ChangedItem> changedAgain = store.items().changedSince(GROUP, first, second);
        assertEquals(1, changedAgain.size());
        assertEquals(next, changedAgain.get(0).item().logicalPath());

        store.items().upsert(item(ITEM, PATH, "ver-3", Map.of(NODE_A, 3L), null));
        long third = store.items().currentSequence(GROUP);
        assertTrue(third > second);
        List<ItemRepository.ChangedItem> changedSamePath = store.items().changedSince(GROUP, second, third);
        assertEquals(1, changedSamePath.size());
        assertEquals(PATH, changedSamePath.get(0).item().logicalPath());
    }

    public void keepsChangeSequencesIsolatedPerGroup() {
        store.items().upsert(item(ITEM, PATH, "ver-1", Map.of(NODE_A, 1L), null));
        long groupSequence = store.items().currentSequence(GROUP);

        Item other = new Item(ItemId.of("item-other"), OTHER_GROUP, PATH, ItemKind.FILE,
                VersionId.of("ver-1"), VersionVector.of(Map.of(NODE_A, 1L)), null);
        store.items().upsert(other);

        long otherSequence = store.items().currentSequence(OTHER_GROUP);
        assertEquals(groupSequence, store.items().currentSequence(GROUP));
        assertTrue(otherSequence > groupSequence);
        assertEquals(1, store.items().changedSince(OTHER_GROUP, groupSequence, otherSequence).size());
        assertEquals(Optional.of(other), store.items().find(OTHER_GROUP, PATH));
    }

    public void storesAndUpdatesManifestCursors() {
        assertEquals(0L, store.cursors().pullSequence(GROUP, NODE_A));
        assertEquals(0L, store.cursors().pushSequence(GROUP, NODE_A));

        store.cursors().updatePull(GROUP, NODE_A, 7);
        store.cursors().updatePush(GROUP, NODE_A, 3);

        assertEquals(7L, store.cursors().pullSequence(GROUP, NODE_A));
        assertEquals(3L, store.cursors().pushSequence(GROUP, NODE_A));

        store.cursors().updatePull(GROUP, NODE_A, 9);
        store.cursors().updatePush(GROUP, NODE_A, 5);

        assertEquals(9L, store.cursors().pullSequence(GROUP, NODE_A));
        assertEquals(5L, store.cursors().pushSequence(GROUP, NODE_A));

        assertEquals(0L, store.cursors().pullSequence(OTHER_GROUP, NODE_A));
    }

    public void deletesItem() {
        store.items().upsert(item(ITEM, PATH, "ver-1", Map.of(NODE_A, 1L), null));

        store.items().delete(ITEM);

        assertTrue(store.items().find(GROUP, PATH).isEmpty());
        assertTrue(store.items().findById(ITEM).isEmpty());
    }

    public void storesAndFindsVersion() {
        FileVersion version = version("ver-1", 0);

        store.versions().insert(version);

        assertEquals(Optional.of(version), store.versions().find(VersionId.of("ver-1")));
    }

    public void listsRecentVersionsNewestFirst() {
        store.versions().insert(version("ver-1", 0));
        store.versions().insert(version("ver-2", 1));
        store.versions().insert(version("ver-3", 2));

        List<FileVersion> recent = store.versions().recent(ITEM, 2);

        assertEquals(List.of("ver-3", "ver-2"),
                recent.stream().map(v -> v.id().value()).toList());
    }

    public void retentionKeepsNewestVersions() {
        for (int i = 0; i < 12; i++) {
            store.versions().insert(version("ver-" + i, i));
        }

        assertEquals(2, store.versions().deleteOlderThan(ITEM, 10));

        List<FileVersion> remaining = store.versions().recent(ITEM, 20);
        assertEquals(10, remaining.size());
        assertEquals("ver-11", remaining.get(0).id().value());
    }

    public void storesPagesAndDeletesTombstones() {
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

    public void resolvesConflicts() {
        Conflict first = new Conflict(ConflictId.of("conf-1"), ITEM, VersionId.of("ver-1"),
                VersionId.of("ver-2"), VersionVector.of(Map.of(NodeId.of("node-a"), 2L)),
                LogicalPath.of("docs/a (conflict node-a).txt"), T0, null);
        Conflict second = new Conflict(ConflictId.of("conf-2"), ITEM, VersionId.of("ver-1"),
                null, VersionVector.empty(), null, T0.plusSeconds(1), null);

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

    public void listsDueTriggers() {
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

    public void storesAndUpdatesSyncRun() {
        SyncRun run = new SyncRun(SyncRunId.of("run-1"), GROUP, TriggerId.of("trig-1"),
                NODE_A, SyncRunState.SCANNING, T0, null, null, null);

        store.runs().insert(run);
        assertEquals(Optional.of(run), store.runs().find(SyncRunId.of("run-1")));

        SyncRun finished = new SyncRun(SyncRunId.of("run-1"), GROUP, TriggerId.of("trig-1"),
                NODE_A, SyncRunState.COMPLETED, T0, T0.plusSeconds(10),
                "{\"items\":3}", ErrorCode.BLOB_HASH_MISMATCH);

        store.runs().update(finished);

        assertEquals(Optional.of(finished), store.runs().find(SyncRunId.of("run-1")));
        assertEquals(List.of(finished), store.runs().recent(GROUP, 5));
        assertTrue(store.runs().recent(OTHER_GROUP, 5).isEmpty());
    }

    public void transactionCommits() {
        SyncGroup group = new SyncGroup(GROUP, "Home", root("C:\\Docs"), 0, T0);

        store.inTransaction(transaction -> {
            transaction.groups().insert(group);
            return null;
        });

        assertEquals(Optional.of(group), store.groups().find(GROUP));
    }

    public void transactionRollsBackOnException() {
        SyncGroup group = new SyncGroup(GROUP, "Home", root("C:\\Docs"), 0, T0);

        assertThrows(IllegalStateException.class, () -> store.inTransaction(transaction -> {
            transaction.groups().insert(group);
            throw new IllegalStateException("boom");
        }));

        assertTrue(store.groups().find(GROUP).isEmpty());
    }

    public void nestedTransactionUsesOuterTransaction() {
        SyncGroup group = new SyncGroup(GROUP, "Home", root("C:\\Docs"), 0, T0);
        SyncGroup other = new SyncGroup(OTHER_GROUP, "Other", root("D:\\Other"), 0, T0);

        store.inTransaction(outer -> {
            outer.groups().insert(group);
            return outer.inTransaction(inner -> {
                inner.groups().insert(other);
                return null;
            });
        });

        assertEquals(Optional.of(group), store.groups().find(GROUP));
        assertEquals(Optional.of(other), store.groups().find(OTHER_GROUP));
    }

    private Item item(ItemId id, LogicalPath path, String versionId,
                      Map<NodeId, Long> counters, Instant deletedAt) {
        return new Item(id, GROUP, path, ItemKind.FILE, VersionId.of(versionId),
                VersionVector.of(counters), deletedAt);
    }

    private FileVersion version(String id, int secondsAfterT0) {
        Instant createdAt = T0.plusSeconds(secondsAfterT0);
        return new FileVersion(VersionId.of(id), ITEM, BLOB, 42, T0, NODE_A,
                VersionVector.of(Map.of(NODE_A, secondsAfterT0 + 1L)),
                SyncRunId.of("run-1"), createdAt);
    }

    private Trigger trigger(String id, boolean enabled, Instant nextFireAt) {
        return new Trigger(TriggerId.of(id), GROUP, TriggerType.ALARM, "{}", enabled, nextFireAt);
    }

    private static SyncRoot root(String physicalPath) {
        return new SyncRoot(physicalPath, "Docs", null);
    }

    private static List<String> paths(ItemPage page) {
        return page.items().stream().map(item -> item.logicalPath().value()).toList();
    }
}
