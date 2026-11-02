package github.gtouming.itemsync.store.metadata;

import github.gtouming.itemsync.domain.identity.ConflictId;
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
import github.gtouming.itemsync.domain.model.Peer;
import github.gtouming.itemsync.domain.model.SyncGroup;
import github.gtouming.itemsync.domain.model.SyncRoot;
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

    SyncRootRepository roots();

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

        void updateLogicalClock(GroupId id, long logicalClock);

        void upsertMember(GroupMember member);

        Optional<GroupMember> findMember(GroupId groupId, NodeId nodeId);

        List<GroupMember> members(GroupId groupId);

        void deleteMember(GroupId groupId, NodeId nodeId);
    }

    interface SyncRootRepository {

        void insert(SyncRoot root);

        Optional<SyncRoot> find(SyncRootId id);

        List<SyncRoot> findByGroup(GroupId groupId);

        void updateIgnoreSnapshot(SyncRootId id, String ignoreSnapshot);

        void delete(SyncRootId id);
    }

    interface ItemRepository {

        record ItemPage(List<Item> items, String nextCursor) {
        }

        record ChangedItem(Item item, long sequence) {
        }

        void upsert(Item item);

        Optional<Item> find(SyncRootId rootId, LogicalPath path);

        Optional<Item> findById(ItemId id);

        ItemPage page(SyncRootId rootId, String cursor, int limit);

        List<ChangedItem> changedSince(SyncRootId rootId, long sinceExclusive, long untilInclusive);

        long currentSequence(SyncRootId rootId);

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

        List<SyncRun> recent(SyncRootId rootId, int limit);
    }

    interface CursorRepository {

        long pullSequence(SyncRootId rootId, NodeId peerNodeId);

        void updatePull(SyncRootId rootId, NodeId peerNodeId, long sequence);

        long pushSequence(SyncRootId rootId, NodeId peerNodeId);

        void updatePush(SyncRootId rootId, NodeId peerNodeId, long sequence);
    }
}
