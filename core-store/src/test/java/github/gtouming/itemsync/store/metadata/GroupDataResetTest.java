package github.gtouming.itemsync.store.metadata;

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
import github.gtouming.itemsync.domain.model.SyncGroup;
import github.gtouming.itemsync.domain.model.SyncRoot;
import github.gtouming.itemsync.domain.model.SyncRun;
import github.gtouming.itemsync.domain.model.SyncRunState;
import github.gtouming.itemsync.domain.model.Tombstone;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.domain.model.TriggerType;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupDataResetTest {

    private static final GroupId GROUP_A = GroupId.parse("group001");
    private static final GroupId GROUP_B = GroupId.parse("group002");
    private static final NodeId NODE = NodeId.of("node-a");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path temp;

    @Test
    void resetClearsSyncStateButKeepsRulesDevicesAndOtherGroups() throws Exception {
        try (JdbcMetadataStore store = open()) {
            seed(store, GROUP_A, "a");
            seed(store, GROUP_B, "b");

            GroupDataReset.reset(store, GROUP_A);

            assertTrue(store.items().findById(item(GROUP_A)).isEmpty());
            assertEquals(0, store.versions().recent(item(GROUP_A), 10).size());
            assertTrue(store.tombstones().find(item(GROUP_A)).isEmpty());
            assertTrue(store.conflicts().findByItem(item(GROUP_A)).isEmpty());
            assertEquals(0, store.cursors().pullSequence(GROUP_A, NODE));
            assertTrue(store.runs().recent(GROUP_A, 10).isEmpty());
            assertEquals(1, store.triggers().findByGroup(GROUP_A).size(),
                    "a root reset keeps the sync rules");
            assertEquals(1, store.devices().findByGroup(GROUP_A).size(),
                    "a root reset keeps the known devices");
            assertTrue(store.groups().find(GROUP_A).isPresent(),
                    "a root reset keeps the group row");

            assertTrue(store.items().findById(item(GROUP_B)).isPresent());
            assertEquals(1, store.runs().recent(GROUP_B, 10).size());
            assertTrue(store.groups().find(GROUP_B).isPresent());
        }
    }

    @Test
    void deleteAlsoRemovesRulesDevicesAndTheGroupRow() throws Exception {
        try (JdbcMetadataStore store = open()) {
            seed(store, GROUP_A, "a");

            GroupDataReset.delete(store, GROUP_A);

            assertTrue(store.items().findById(item(GROUP_A)).isEmpty());
            assertTrue(store.triggers().findByGroup(GROUP_A).isEmpty());
            assertTrue(store.devices().findByGroup(GROUP_A).isEmpty());
            assertTrue(store.groups().find(GROUP_A).isEmpty());
        }
    }

    private JdbcMetadataStore open() throws Exception {
        return JdbcMetadataStore.open(
                "jdbc:sqlite:" + temp.resolve("meta.db").toAbsolutePath());
    }

    private static void seed(JdbcMetadataStore store, GroupId group, String suffix) {
        store.groups().insert(new SyncGroup(group, "name-" + suffix,
                new SyncRoot("root-" + suffix, null, null), 0, T0));
        store.items().upsert(new Item(item(group), group,
                LogicalPath.of("file-" + suffix + ".txt"), ItemKind.FILE, null,
                VersionVector.empty(), null));
        store.versions().insert(new FileVersion(VersionId.of("version-" + suffix),
                item(group), BlobHash.parse("a".repeat(BlobHash.LENGTH)), 1, T0, NODE,
                VersionVector.empty(), null, T0));
        store.tombstones().upsert(new Tombstone(item(group), VersionVector.empty(), T0, NODE));
        store.conflicts().insert(new Conflict(ConflictId.of("conflict-" + suffix), item(group),
                null, null, VersionVector.empty(), null, T0, null));
        store.cursors().updatePull(group, NODE, 7);
        store.runs().insert(new SyncRun(SyncRunId.of("run-" + suffix), group, null, NODE,
                SyncRunState.COMPLETED, T0, T0, "{}", null));
        store.triggers().upsert(new Trigger(TriggerId.of("trigger-" + suffix), group,
                TriggerType.CONDITION, "{\"type\":\"directory\"}", true, null));
        store.devices().upsert(new GroupDevice(group, DeviceId.of("device-" + suffix),
                "Device " + suffix, "fingerprint-" + suffix, T0, T0));
    }

    private static ItemId item(GroupId group) {
        return ItemId.of("item-" + group.value());
    }
}
