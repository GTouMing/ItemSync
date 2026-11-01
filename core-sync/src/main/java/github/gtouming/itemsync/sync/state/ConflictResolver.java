package github.gtouming.itemsync.sync.state;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.ConflictId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.Conflict;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.metadata.MetadataStore;
import github.gtouming.itemsync.sync.apply.PhysicalPathResolver;
import github.gtouming.itemsync.sync.fs.FileHashing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class ConflictResolver {

    public enum Resolution {
        KEEP_LOCAL,
        KEEP_REMOTE,
        KEEP_BOTH
    }

    public record ConflictView(ConflictId id, LogicalPath path, FileVersion local,
                               FileVersion remote, LogicalPath copyPath, Instant detectedAt) {
    }

    private final Path syncRoot;
    private final MetadataStore store;
    private final PhysicalPathResolver paths;
    private final NodeId localNode;
    private final Supplier<Instant> clock;
    private final LocalStateWriter writer;

    public ConflictResolver(Path syncRoot, MetadataStore store, PhysicalPathResolver paths,
                            SyncRootId rootId, NodeId localNode, Supplier<Instant> clock) {
        this.syncRoot = Objects.requireNonNull(syncRoot, "syncRoot");
        this.store = Objects.requireNonNull(store, "store");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.localNode = Objects.requireNonNull(localNode, "localNode");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.writer = new LocalStateWriter(store, rootId, clock);
    }

    public List<ConflictView> open(int limit) {
        List<ConflictView> views = new ArrayList<>();
        for (Conflict conflict : store.conflicts().findOpen(limit)) {
            Item item = store.items().findById(conflict.itemId()).orElse(null);
            if (item == null) {
                continue;
            }
            views.add(new ConflictView(conflict.id(), item.logicalPath(),
                    version(conflict.localVersion()), version(conflict.remoteVersion()),
                    conflict.copyPath(), conflict.detectedAt()));
        }
        return List.copyOf(views);
    }

    public void resolve(ConflictId id, Resolution resolution) throws IOException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(resolution, "resolution");
        Conflict conflict = store.conflicts().find(id)
                .orElseThrow(() -> new IOException("conflict not found: " + id.value()));
        if (conflict.resolvedAt() != null) {
            return;
        }
        Item item = store.items().findById(conflict.itemId())
                .orElseThrow(() -> new IOException("item not found: " + conflict.itemId().value()));
        switch (resolution) {
            case KEEP_LOCAL -> keepLocal(conflict, item);
            case KEEP_REMOTE -> keepRemote(conflict, item);
            case KEEP_BOTH -> {
                // both files are already on disk
            }
        }
        store.conflicts().resolve(id, clock.get());
    }

    private void keepLocal(Conflict conflict, Item item) throws IOException {
        if (conflict.localVersion() == null) {
            throw new IOException("local version is gone: " + conflict.id().value());
        }
        FileVersion previousLocal = version(conflict.localVersion());
        VersionVector base = previousLocal == null ? item.vector() : previousLocal.vector();
        FileVersion remote = version(conflict.remoteVersion());
        VersionVector remoteVector = remote == null ? VersionVector.empty() : remote.vector();

        Path target = paths.resolve(syncRoot, item.logicalPath());
        BlobHashSize content;
        if (conflict.copyPath() != null) {
            Path copy = paths.resolve(syncRoot, conflict.copyPath());
            if (!Files.isRegularFile(copy)) {
                throw new IOException("conflict copy is gone: " + copy);
            }
            content = read(copy);
            writeFile(copy, target, content.mtime());
        } else {
            if (!Files.isRegularFile(target)) {
                throw new IOException("local file is gone: " + target);
            }
            content = read(target);
        }

        VersionVector next = base.merge(remoteVector).increment(localNode);
        writer.recordFile(item.logicalPath(), content.hash(), content.size(), content.mtime(),
                next, localNode);
    }

    private void keepRemote(Conflict conflict, Item item) throws IOException {
        if (conflict.remoteVersion() != null) {
            return;
        }
        Path target = paths.resolve(syncRoot, item.logicalPath());
        Files.deleteIfExists(target);
        writer.recordTombstone(item.logicalPath(), item.kind(),
                item.vector().increment(localNode), localNode);
    }

    private FileVersion version(VersionId id) {
        return id == null ? null : store.versions().find(id).orElse(null);
    }

    private static BlobHashSize read(Path file) throws IOException {
        return new BlobHashSize(FileHashing.sha256(file), Files.size(file),
                Files.getLastModifiedTime(file).toInstant());
    }

    private void writeFile(Path source, Path target, Instant mtime) throws IOException {
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
            try (InputStream in = Files.newInputStream(source);
                 OutputStream out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        Files.setLastModifiedTime(target, FileTime.from(mtime));
    }

    private record BlobHashSize(BlobHash hash, long size, Instant mtime) {
    }
}
