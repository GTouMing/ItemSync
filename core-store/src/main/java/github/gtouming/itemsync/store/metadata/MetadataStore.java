package github.gtouming.itemsync.store.metadata;

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
import github.gtouming.itemsync.domain.model.Peer;
import github.gtouming.itemsync.domain.model.SyncGroup;
import github.gtouming.itemsync.domain.model.SyncRun;
import github.gtouming.itemsync.domain.model.Tombstone;
import github.gtouming.itemsync.domain.model.Trigger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface MetadataStore extends AutoCloseable {

    <T> T inTransaction(Function<MetadataStore, T> work);

    PeerRepository peers();

    SyncGroupRepository groups();

    GroupDeviceRepository devices();

    ItemRepository items();

    VersionRepository versions();

    TombstoneRepository tombstones();

    ConflictRepository conflicts();

    TriggerRepository triggers();

    SyncRunRepository runs();

    CursorRepository cursors();

    @Override
    void close();

    interface PeerRepository {

        void upsert(Peer peer);

        Optional<Peer> find(NodeId nodeId);

        List<Peer> findAll();

        void delete(NodeId nodeId);
    }

    interface SyncGroupRepository {

        void insert(SyncGroup group);

        Optional<SyncGroup> find(GroupId id);

        List<SyncGroup> findAll();

        void updateLogicalClock(GroupId id, long logicalClock);

        void delete(GroupId id);
    }

    interface GroupDeviceRepository {

        void upsert(GroupDevice device);

        Optional<GroupDevice> find(GroupId groupId, DeviceId deviceId);

        List<GroupDevice> findByGroup(GroupId groupId);

        void delete(GroupId groupId, DeviceId deviceId);
    }

    interface ItemRepository {

        record ItemPage(List<Item> items, String nextCursor) {
        }

        record ChangedItem(Item item, long sequence) {
        }

        void upsert(Item item);

        Optional<Item> find(GroupId groupId, LogicalPath path);

        Optional<Item> findById(ItemId id);

        ItemPage page(GroupId groupId, String cursor, int limit);

        List<ChangedItem> changedSince(GroupId groupId, long sinceExclusive, long untilInclusive);

        long currentSequence(GroupId groupId);

        void delete(ItemId id);
    }

    interface VersionRepository {

        void insert(FileVersion version);

        Optional<FileVersion> find(VersionId id);

        List<FileVersion> recent(ItemId itemId, int limit);

        int deleteOlderThan(ItemId itemId, int keepNewest);
    }

    interface TombstoneRepository {

        void upsert(Tombstone tombstone);

        Optional<Tombstone> find(ItemId itemId);

        List<Tombstone> page(String cursor, int limit);

        void delete(ItemId itemId);
    }

    interface ConflictRepository {

        void insert(Conflict conflict);

        Optional<Conflict> find(ConflictId id);

        List<Conflict> findOpen(int limit);

        List<Conflict> findByItem(ItemId itemId);

        void resolve(ConflictId id, Instant resolvedAt);
    }

    interface TriggerRepository {

        void upsert(Trigger trigger);

        Optional<Trigger> find(TriggerId id);

        List<Trigger> findByGroup(GroupId groupId);

        List<Trigger> due(Instant now, int limit);

        void delete(TriggerId id);
    }

    interface SyncRunRepository {

        void insert(SyncRun run);

        Optional<SyncRun> find(SyncRunId id);

        void update(SyncRun run);

        List<SyncRun> recent(GroupId groupId, int limit);
    }

    interface CursorRepository {

        long pullSequence(GroupId groupId, NodeId peerNodeId);

        void updatePull(GroupId groupId, NodeId peerNodeId, long sequence);

        long pushSequence(GroupId groupId, NodeId peerNodeId);

        void updatePush(GroupId groupId, NodeId peerNodeId, long sequence);
    }
}
