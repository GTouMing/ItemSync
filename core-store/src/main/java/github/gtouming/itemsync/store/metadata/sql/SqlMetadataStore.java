package github.gtouming.itemsync.store.metadata.sql;

import github.gtouming.itemsync.store.metadata.MetadataStore;

import java.util.Objects;
import java.util.function.Function;

public abstract class SqlMetadataStore implements MetadataStore {

    private final SqlSession session;
    private final PeerRepository peers;
    private final SyncGroupRepository groups;
    private final SyncRootRepository roots;
    private final ItemRepository items;
    private final VersionRepository versions;
    private final TombstoneRepository tombstones;
    private final ConflictRepository conflicts;
    private final TriggerRepository triggers;
    private final SyncRunRepository runs;
    private final CursorRepository cursors;

    protected SqlMetadataStore(SqlSession session) {
        this.session = Objects.requireNonNull(session, "session");
        this.peers = new SqlPeerRepository(session);
        this.groups = new SqlSyncGroupRepository(session);
        this.roots = new SqlSyncRootRepository(session);
        this.items = new SqlItemRepository(session);
        this.versions = new SqlVersionRepository(session);
        this.tombstones = new SqlTombstoneRepository(session);
        this.conflicts = new SqlConflictRepository(session);
        this.triggers = new SqlTriggerRepository(session);
        this.runs = new SqlSyncRunRepository(session);
        this.cursors = new SqlCursorRepository(session);
    }

    @Override
    public <T> T inTransaction(Function<MetadataStore, T> work) {
        Objects.requireNonNull(work, "work");
        if (session.inTransaction()) {
            return work.apply(this);
        }
        session.begin();
        try {
            T result = work.apply(this);
            session.commit();
            return result;
        } catch (RuntimeException e) {
            session.rollback();
            throw e;
        }
    }

    @Override
    public PeerRepository peers() {
        return peers;
    }

    @Override
    public SyncGroupRepository groups() {
        return groups;
    }

    @Override
    public SyncRootRepository roots() {
        return roots;
    }

    @Override
    public ItemRepository items() {
        return items;
    }

    @Override
    public VersionRepository versions() {
        return versions;
    }

    @Override
    public TombstoneRepository tombstones() {
        return tombstones;
    }

    @Override
    public ConflictRepository conflicts() {
        return conflicts;
    }

    @Override
    public TriggerRepository triggers() {
        return triggers;
    }

    @Override
    public SyncRunRepository runs() {
        return runs;
    }

    @Override
    public CursorRepository cursors() {
        return cursors;
    }
}
