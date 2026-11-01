package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.store.metadata.MetadataException;
import github.gtouming.itemsync.store.metadata.MetadataStore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

public final class JdbcMetadataStore implements MetadataStore {

    private final Connection connection;
    private final PeerRepository peers;
    private final SyncGroupRepository groups;
    private final SyncRootRepository roots;
    private final ItemRepository items;
    private final VersionRepository versions;
    private final TombstoneRepository tombstones;
    private final ConflictRepository conflicts;
    private final TriggerRepository triggers;
    private final SyncRunRepository runs;

    private boolean inTransaction;

    public JdbcMetadataStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        MetadataSchema.migrate(connection);
        this.peers = new JdbcPeerRepository(connection);
        this.groups = new JdbcSyncGroupRepository(connection);
        this.roots = new JdbcSyncRootRepository(connection);
        this.items = new JdbcItemRepository(connection);
        this.versions = new JdbcVersionRepository(connection);
        this.tombstones = new JdbcTombstoneRepository(connection);
        this.conflicts = new JdbcConflictRepository(connection);
        this.triggers = new JdbcTriggerRepository(connection);
        this.runs = new JdbcSyncRunRepository(connection);
    }

    public static JdbcMetadataStore open(String jdbcUrl) throws SQLException {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        return new JdbcMetadataStore(DriverManager.getConnection(jdbcUrl));
    }

    @Override
    public <T> T inTransaction(Function<MetadataStore, T> work) {
        Objects.requireNonNull(work, "work");
        if (inTransaction) {
            return work.apply(this);
        }
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new MetadataException("begin transaction failed", e);
        }
        inTransaction = true;
        try {
            T result = work.apply(this);
            connection.commit();
            return result;
        } catch (RuntimeException e) {
            rollback(e);
            throw e;
        } catch (SQLException e) {
            rollback(e);
            throw new MetadataException("commit transaction failed", e);
        } finally {
            inTransaction = false;
            restoreAutoCommit();
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
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new MetadataException("close metadata store failed", e);
        }
    }

    private void rollback(Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new MetadataException("restore autocommit failed", e);
        }
    }
}
