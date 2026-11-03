package github.gtouming.itemsync.sync.state;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.domain.model.Tombstone;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.metadata.MetadataStore;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class LocalStateWriter {

    private static final int DEFAULT_KEEP_VERSIONS = 10;

    private final MetadataStore store;
    private final GroupId groupId;
    private final Supplier<Instant> clock;
    private final int keepVersions;

    public LocalStateWriter(MetadataStore store, GroupId groupId, Supplier<Instant> clock) {
        this(store, groupId, clock, DEFAULT_KEEP_VERSIONS);
    }

    public LocalStateWriter(MetadataStore store, GroupId groupId, Supplier<Instant> clock,
                            int keepVersions) {
        this.store = Objects.requireNonNull(store, "store");
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (keepVersions <= 0) {
            throw new IllegalArgumentException("keepVersions must be positive, got " + keepVersions);
        }
        this.keepVersions = keepVersions;
    }

    public Optional<Item> find(LogicalPath path) {
        return store.items().find(groupId, path);
    }

    public VersionId currentVersionId(LogicalPath path) {
        return store.items().find(groupId, path).map(Item::currentVersion).orElse(null);
    }

    public VersionId recordFile(LogicalPath path, BlobHash hash, long size, Instant mtime,
                                VersionVector vector, NodeId sourceNode) {
        Instant now = clock.get();
        return store.inTransaction(transaction -> {
            ItemId itemId = transaction.items().find(groupId, path)
                    .map(Item::id)
                    .orElseGet(() -> new ItemId(UUID.randomUUID().toString()));
            VersionId versionId = new VersionId(UUID.randomUUID().toString());
            transaction.items().upsert(new Item(itemId, groupId, path, ItemKind.FILE,
                    versionId, vector, null));
            transaction.versions().insert(new FileVersion(versionId, itemId, hash, size, mtime,
                    sourceNode, vector, null, now));
            transaction.versions().deleteOlderThan(itemId, keepVersions);
            transaction.tombstones().delete(itemId);
            return versionId;
        });
    }

    public void recordDirectory(LogicalPath path, VersionVector vector) {
        store.inTransaction(transaction -> {
            ItemId itemId = transaction.items().find(groupId, path)
                    .map(Item::id)
                    .orElseGet(() -> new ItemId(UUID.randomUUID().toString()));
            transaction.items().upsert(new Item(itemId, groupId, path, ItemKind.DIRECTORY,
                    null, vector, null));
            transaction.tombstones().delete(itemId);
            return null;
        });
    }

    public void recordTombstone(LogicalPath path, ItemKind kind, VersionVector vector,
                                NodeId sourceNode) {
        Instant now = clock.get();
        store.inTransaction(transaction -> {
            Optional<Item> existing = transaction.items().find(groupId, path);
            ItemId itemId = existing.map(Item::id)
                    .orElseGet(() -> new ItemId(UUID.randomUUID().toString()));
            ItemKind effectiveKind = existing.map(Item::kind).orElse(kind);
            transaction.items().upsert(new Item(itemId, groupId, path, effectiveKind,
                    null, vector, now));
            transaction.tombstones().upsert(new Tombstone(itemId, vector, now, sourceNode));
            return null;
        });
    }
}
