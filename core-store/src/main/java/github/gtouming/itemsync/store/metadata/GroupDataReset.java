package github.gtouming.itemsync.store.metadata;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.model.SyncRoot;

import java.util.Objects;

/**
 * Clears the local sync metadata of one group. User files, the group id, the trust store,
 * the sync rules and the known device list are not touched by {@link #reset}; a root change
 * uses that shape so the group can be rescanned from its new directory (ADR-0035).
 *
 * <p>{@link #delete} additionally removes the rules, the known devices and the group row;
 * the caller still removes the persisted configuration and keeps the user files.
 */
public final class GroupDataReset {

    private GroupDataReset() {
    }

    public static void reset(MetadataStore store, GroupId groupId) {
        reset(store, groupId, null);
    }

    /**
     * Resets the group and, when {@code newRoot} is given, updates the root stored in the
     * group row. The sync root in the desktop/Android configuration is updated by the caller.
     */
    public static void reset(MetadataStore store, GroupId groupId, SyncRoot newRoot) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(groupId, "groupId");
        store.inTransaction(metadata -> {
            clearSyncState(metadata, groupId);
            if (newRoot != null) {
                metadata.groups().updateRoot(groupId, newRoot);
            }
            return null;
        });
    }

    public static void delete(MetadataStore store, GroupId groupId) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(groupId, "groupId");
        store.inTransaction(metadata -> {
            clearSyncState(metadata, groupId);
            metadata.triggers().deleteByGroup(groupId);
            metadata.devices().deleteByGroup(groupId);
            metadata.groups().delete(groupId);
            return null;
        });
    }

    private static void clearSyncState(MetadataStore metadata, GroupId groupId) {
        // Version, tombstone and conflict rows reference items, so they must go first.
        metadata.versions().deleteByGroup(groupId);
        metadata.tombstones().deleteByGroup(groupId);
        metadata.conflicts().deleteByGroup(groupId);
        metadata.cursors().deleteByGroup(groupId);
        metadata.runs().deleteByGroup(groupId);
        metadata.items().deleteByGroup(groupId);
    }
}
