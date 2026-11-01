package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.model.SyncGroup;
import github.gtouming.itemsync.store.metadata.MetadataStore;
import github.gtouming.itemsync.store.metadata.MetadataStoreContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcMetadataStoreContractTest extends MetadataStoreContract {

    @TempDir
    Path temp;

    @Override
    protected MetadataStore openStore() {
        try {
            return JdbcMetadataStore.open("jdbc:sqlite:" + temp.resolve("metadata.db").toAbsolutePath());
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open test database", e);
        }
    }

    @Test
    void reopensDatabaseWithoutLosingData() {
        GroupId groupId = GroupId.parse("group009");

        try (MetadataStore first = openStore()) {
            first.groups().insert(new SyncGroup(groupId, "Home", 7, T0));
        }

        try (MetadataStore second = openStore()) {
            assertTrue(second.groups().find(groupId).isPresent());
            assertTrue(second.groups().find(groupId).orElseThrow().logicalClock() == 7);
        }
    }
}
