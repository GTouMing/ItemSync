package github.gtouming.itemsync.sync.state;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.blob.BlobStore;
import github.gtouming.itemsync.store.metadata.MetadataStore;
import github.gtouming.itemsync.sync.apply.PhysicalPathResolver;
import github.gtouming.itemsync.sync.ignore.IgnoreFileLoader;
import github.gtouming.itemsync.sync.ignore.IgnoreRules;
import github.gtouming.itemsync.sync.scan.FileScanner;
import github.gtouming.itemsync.sync.scan.ScannedEntry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class LocalReconciler {

    private static final int PAGE_SIZE = 500;

    private final Path syncRoot;
    private final MetadataStore store;
    private final BlobStore blobs;
    private final SyncRootId rootId;
    private final NodeId localNode;
    private final PhysicalPathResolver paths;
    private final FileScanner scanner = new FileScanner();
    private final LocalStateWriter writer;

    public LocalReconciler(Path syncRoot, MetadataStore store, BlobStore blobs,
                           SyncRootId rootId, NodeId localNode,
                           PhysicalPathResolver paths, Supplier<Instant> clock) {
        this.syncRoot = Objects.requireNonNull(syncRoot, "syncRoot");
        this.store = Objects.requireNonNull(store, "store");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.rootId = Objects.requireNonNull(rootId, "rootId");
        this.localNode = Objects.requireNonNull(localNode, "localNode");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.writer = new LocalStateWriter(store, rootId, clock);
    }

    public SyncRootId rootId() {
        return rootId;
    }

    public List<ManifestEntry> reconcile() throws IOException {
        IgnoreRules rules = IgnoreFileLoader.load(syncRoot);
        List<ScannedEntry> scanned = scanner.scan(syncRoot, rules);
        Map<LogicalPath, ScannedEntry> scannedByPath = new LinkedHashMap<>();
        for (ScannedEntry entry : scanned) {
            scannedByPath.put(entry.path(), entry);
        }

        Set<LogicalPath> seen = new HashSet<>();
        for (Item item : allItems()) {
            ScannedEntry entry = scannedByPath.get(item.logicalPath());
            if (entry == null) {
                if (item.deletedAt() == null) {
                    writer.recordTombstone(item.logicalPath(), item.kind(),
                            item.vector().increment(localNode), localNode);
                }
                continue;
            }
            seen.add(item.logicalPath());
            if (entry.kind() == ItemKind.DIRECTORY) {
                if (item.kind() != ItemKind.DIRECTORY || item.deletedAt() != null) {
                    writer.recordDirectory(entry.path(), item.vector().increment(localNode));
                }
                continue;
            }
            if (item.kind() != ItemKind.FILE || item.currentVersion() == null) {
                recordScannedFile(entry, item.vector().increment(localNode));
                continue;
            }
            FileVersion current = store.versions().find(item.currentVersion()).orElse(null);
            if (current == null || !current.blobHash().equals(entry.hash())) {
                recordScannedFile(entry, item.vector().increment(localNode));
            }
        }

        for (ScannedEntry entry : scanned) {
            if (seen.contains(entry.path())) {
                continue;
            }
            VersionVector vector = VersionVector.of(Compat.map(localNode, 1L));
            if (entry.kind() == ItemKind.DIRECTORY) {
                writer.recordDirectory(entry.path(), vector);
            } else {
                recordScannedFile(entry, vector);
            }
        }

        return manifest();
    }

    private void recordScannedFile(ScannedEntry entry, VersionVector vector) throws IOException {
        Path file = paths.resolve(syncRoot, entry.path());
        BlobHash stored;
        try (InputStream in = Files.newInputStream(file)) {
            stored = blobs.put(in);
        }
        if (!stored.equals(entry.hash())) {
            throw new IOException("file changed while reconciling: " + entry.path());
        }
        writer.recordFile(entry.path(), entry.hash(), entry.size(), entry.mtime(), vector, localNode);
    }

    private List<Item> allItems() {
        List<Item> items = new ArrayList<>();
        String cursor = null;
        while (true) {
            MetadataStore.ItemRepository.ItemPage page = store.items().page(rootId, cursor, PAGE_SIZE);
            items.addAll(page.items());
            cursor = page.nextCursor();
            if (cursor == null) {
                return items;
            }
        }
    }

    private List<ManifestEntry> manifest() {
        List<ManifestEntry> entries = new ArrayList<>();
        for (Item item : allItems()) {
            if (item.deletedAt() != null) {
                entries.add(new ManifestEntry(item.logicalPath(), item.kind(), null, 0,
                        item.deletedAt(), item.vector(), true));
                continue;
            }
            if (item.kind() == ItemKind.DIRECTORY) {
                entries.add(new ManifestEntry(item.logicalPath(), ItemKind.DIRECTORY, null, 0,
                        Instant.EPOCH, item.vector(), false));
                continue;
            }
            if (item.currentVersion() == null) {
                continue;
            }
            FileVersion current = store.versions().find(item.currentVersion()).orElse(null);
            if (current == null) {
                continue;
            }
            entries.add(new ManifestEntry(item.logicalPath(), ItemKind.FILE, current.blobHash(),
                    current.size(), current.mtime(), item.vector(), false));
        }
        return Compat.copyList(entries);
    }
}
