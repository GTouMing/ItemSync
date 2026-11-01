package github.gtouming.itemsync.sync.apply;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.ConflictId;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.identity.VersionId;
import github.gtouming.itemsync.domain.model.Conflict;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.domain.model.Tombstone;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.blob.BlobStore;
import github.gtouming.itemsync.store.metadata.MetadataStore;
import github.gtouming.itemsync.sync.fs.FileHashing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class LocalApplier {

    private static final int KEEP_VERSIONS = 10;
    private static final DateTimeFormatter CONFLICT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path syncRoot;
    private final MetadataStore store;
    private final BlobStore blobs;
    private final PhysicalPathResolver paths;
    private final SyncRootId rootId;
    private final NodeId localNode;
    private final DeviceId localDevice;
    private final Supplier<Instant> clock;

    public LocalApplier(Path syncRoot, MetadataStore store, BlobStore blobs,
                        PhysicalPathResolver paths, SyncRootId rootId,
                        NodeId localNode, DeviceId localDevice, Supplier<Instant> clock) {
        this.syncRoot = Objects.requireNonNull(syncRoot, "syncRoot");
        this.store = Objects.requireNonNull(store, "store");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.rootId = Objects.requireNonNull(rootId, "rootId");
        this.localNode = Objects.requireNonNull(localNode, "localNode");
        this.localDevice = Objects.requireNonNull(localDevice, "localDevice");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public VersionId applyRemote(ManifestEntry remote, BlobFetcher fetcher, NodeId sourceNode)
            throws IOException {
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(fetcher, "fetcher");
        Objects.requireNonNull(sourceNode, "sourceNode");

        if (remote.deleted()) {
            applyRemoteDeletion(remote, sourceNode);
            return null;
        }

        Path target = paths.resolve(syncRoot, remote.path());
        if (remote.kind() == ItemKind.DIRECTORY) {
            Files.createDirectories(target);
            storeDirectory(remote);
            return null;
        }

        BlobHash expected = remote.hash();
        BlobHash stored;
        try (InputStream in = fetcher.open(expected)) {
            stored = blobs.put(in);
        }
        if (!stored.equals(expected)) {
            throw new IOException("blob hash mismatch: expected " + expected + ", got " + stored);
        }
        writeAtomically(target, expected, remote.mtime());
        return storeFile(remote.path(), expected, remote.size(), remote.mtime(), remote.vector(), sourceNode);
    }

    public void resolveConflict(ManifestEntry local, ManifestEntry remote,
                                BlobFetcher fetcher, NodeId sourceNode) throws IOException {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(fetcher, "fetcher");
        Objects.requireNonNull(sourceNode, "sourceNode");

        if (local.deleted() && remote.deleted()) {
            return;
        }
        if (remote.deleted()) {
            recordConflict(local.path(), currentVersionId(local.path()), null, null);
            return;
        }
        if (local.deleted()) {
            VersionId remoteVersion = applyRemote(remote, fetcher, sourceNode);
            recordConflict(remote.path(), null, remoteVersion, null);
            return;
        }

        VersionId localVersion = currentVersionId(local.path());
        Path source = paths.resolve(syncRoot, local.path());
        BlobHash localHash = FileHashing.sha256(source);
        try (InputStream in = Files.newInputStream(source)) {
            BlobHash stored = blobs.put(in);
            if (!stored.equals(localHash)) {
                throw new IOException("local file changed while creating conflict copy");
            }
        }

        LogicalPath copyPath = conflictPath(local.path(), clock.get());
        Path copyTarget = paths.resolve(syncRoot, copyPath);
        Files.createDirectories(copyTarget.getParent());
        Files.move(source, copyTarget, StandardCopyOption.ATOMIC_MOVE);
        Instant copyMtime = Files.getLastModifiedTime(copyTarget).toInstant();
        long copySize = Files.size(copyTarget);
        VersionVector copyVector = local.vector().increment(localNode);
        storeFile(copyPath, localHash, copySize, copyMtime, copyVector, localNode);

        VersionId remoteVersion = applyRemote(remote, fetcher, sourceNode);
        recordConflict(local.path(), localVersion, remoteVersion, copyPath);
    }

    private void applyRemoteDeletion(ManifestEntry remote, NodeId sourceNode) throws IOException {
        Path target = paths.resolve(syncRoot, remote.path());
        if (Files.isRegularFile(target)) {
            Files.delete(target);
        }
        storeTombstone(remote.path(), remote.kind(), remote.vector(), sourceNode);
    }

    private void storeDirectory(ManifestEntry remote) {
        store.inTransaction(transaction -> {
            ItemId itemId = transaction.items().find(rootId, remote.path())
                    .map(Item::id)
                    .orElseGet(() -> new ItemId(UUID.randomUUID().toString()));
            transaction.items().upsert(new Item(itemId, rootId, remote.path(), ItemKind.DIRECTORY,
                    null, remote.vector(), null));
            transaction.tombstones().delete(itemId);
            return null;
        });
    }

    private void storeTombstone(LogicalPath path, ItemKind kind, VersionVector vector, NodeId sourceNode) {
        Instant now = clock.get();
        store.inTransaction(transaction -> {
            Optional<Item> existing = transaction.items().find(rootId, path);
            ItemId itemId = existing.map(Item::id)
                    .orElseGet(() -> new ItemId(UUID.randomUUID().toString()));
            ItemKind effectiveKind = existing.map(Item::kind).orElse(kind);
            transaction.items().upsert(new Item(itemId, rootId, path, effectiveKind,
                    null, vector, now));
            transaction.tombstones().upsert(new Tombstone(itemId, vector, now, sourceNode));
            return null;
        });
    }

    private VersionId storeFile(LogicalPath path, BlobHash hash, long size, Instant mtime,
                                VersionVector vector, NodeId sourceNode) {
        Instant now = clock.get();
        return store.inTransaction(transaction -> {
            ItemId itemId = transaction.items().find(rootId, path)
                    .map(Item::id)
                    .orElseGet(() -> new ItemId(UUID.randomUUID().toString()));
            VersionId versionId = new VersionId(UUID.randomUUID().toString());
            transaction.items().upsert(new Item(itemId, rootId, path, ItemKind.FILE,
                    versionId, vector, null));
            transaction.versions().insert(new FileVersion(versionId, itemId, hash, size, mtime,
                    sourceNode, vector, null, now));
            transaction.versions().deleteOlderThan(itemId, KEEP_VERSIONS);
            transaction.tombstones().delete(itemId);
            return versionId;
        });
    }

    private void recordConflict(LogicalPath path, VersionId localVersion,
                                VersionId remoteVersion, LogicalPath copyPath) {
        Optional<ItemId> itemId = store.items().find(rootId, path).map(Item::id);
        if (itemId.isEmpty()) {
            return;
        }
        store.conflicts().insert(new Conflict(
                ConflictId.of(UUID.randomUUID().toString()),
                itemId.get(),
                localVersion,
                remoteVersion,
                copyPath,
                clock.get(),
                null));
    }

    private VersionId currentVersionId(LogicalPath path) {
        return store.items().find(rootId, path).map(Item::currentVersion).orElse(null);
    }

    private LogicalPath conflictPath(LogicalPath original, Instant now) {
        String name = original.name();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        String suffix = " (conflict " + localDevice.value() + " " + CONFLICT_STAMP.format(now) + ")";
        String parent = parentOf(original);

        int counter = 1;
        while (true) {
            String candidate = base + suffix + (counter == 1 ? "" : "-" + counter) + extension;
            LogicalPath path = parent == null
                    ? LogicalPath.of(candidate)
                    : LogicalPath.of(parent + "/" + candidate);
            if (!Files.exists(paths.resolve(syncRoot, path))) {
                return path;
            }
            counter++;
        }
    }

    private static String parentOf(LogicalPath path) {
        int slash = path.value().lastIndexOf('/');
        return slash < 0 ? null : path.value().substring(0, slash);
    }

    private void writeAtomically(Path target, BlobHash hash, Instant mtime) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("target has no parent directory: " + target);
        }
        Files.createDirectories(parent);
        if (Files.isDirectory(target)) {
            throw new IOException("cannot replace a directory with a file: " + target);
        }

        Path temp = Files.createTempFile(parent, ".itemsync-", ".tmp");
        try {
            try (InputStream in = blobs.open(hash); OutputStream out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        Files.setLastModifiedTime(target, FileTime.from(mtime));
    }
}
