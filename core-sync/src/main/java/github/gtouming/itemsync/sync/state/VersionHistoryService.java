package github.gtouming.itemsync.sync.state;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.blob.BlobStore;
import github.gtouming.itemsync.store.metadata.MetadataStore;
import github.gtouming.itemsync.sync.apply.PhysicalPathResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class VersionHistoryService {

    public record ItemView(ItemId id, LogicalPath path) {
    }

    private final Path syncRoot;
    private final MetadataStore store;
    private final BlobStore blobs;
    private final PhysicalPathResolver paths;
    private final SyncRootId rootId;
    private final NodeId localNode;
    private final Supplier<Instant> clock;
    private final LocalStateWriter writer;

    public VersionHistoryService(Path syncRoot, MetadataStore store, BlobStore blobs,
                                 PhysicalPathResolver paths, SyncRootId rootId, NodeId localNode,
                                 Supplier<Instant> clock) {
        this.syncRoot = Objects.requireNonNull(syncRoot, "syncRoot");
        this.store = Objects.requireNonNull(store, "store");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.rootId = Objects.requireNonNull(rootId, "rootId");
        this.localNode = Objects.requireNonNull(localNode, "localNode");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.writer = new LocalStateWriter(store, rootId, clock);
    }

    public List<ItemView> items(int limit) {
        return store.items().page(rootId, null, limit).items().stream()
                .filter(item -> item.kind() == ItemKind.FILE)
                .map(item -> new ItemView(item.id(), item.logicalPath()))
                .collect(java.util.stream.Collectors.toList());
    }

    public List<FileVersion> versions(ItemId itemId) {
        return store.versions().recent(itemId, 10);
    }

    public void restore(ItemId itemId, VersionId versionId) throws IOException {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(versionId, "versionId");
        Item item = store.items().findById(itemId)
                .orElseThrow(() -> new IOException("item not found: " + itemId.value()));
        if (item.kind() != ItemKind.FILE) {
            throw new IOException("item is not a file: " + item.logicalPath().value());
        }
        FileVersion version = store.versions().find(versionId)
                .orElseThrow(() -> new IOException("version not found: " + versionId.value()));
        if (!version.itemId().equals(itemId)) {
            throw new IOException("version does not belong to item: " + versionId.value());
        }
        Path target = paths.resolve(syncRoot, item.logicalPath());
        writeBlob(target, version.blobHash(), version.mtime());
        VersionVector next = item.vector().increment(localNode);
        writer.recordFile(item.logicalPath(), version.blobHash(), version.size(), version.mtime(),
                next, localNode);
    }

    private void writeBlob(Path target, BlobHash hash, Instant mtime) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("target has no parent: " + target);
        }
        Files.createDirectories(parent);
        if (Files.isDirectory(target)) {
            throw new IOException("cannot replace a directory: " + target);
        }
        Path temp = Files.createTempFile(parent, ".itemsync-", ".tmp");
        try {
            try (InputStream in = blobs.open(hash);
                 OutputStream out = Files.newOutputStream(temp)) {
                Compat.copy(in, out);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        Files.setLastModifiedTime(target, FileTime.from(mtime));
    }
}
