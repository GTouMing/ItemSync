package github.gtouming.itemsync.android;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.model.SyncGroup;
import github.gtouming.itemsync.domain.model.SyncRoot;
import github.gtouming.itemsync.store.metadata.MetadataStoreConformance;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36)
public class AndroidMetadataStoreContractTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private AndroidSqliteMetadataStore store;
    private MetadataStoreConformance conformance;

    @Before
    public void openStore() {
        store = new AndroidSqliteMetadataStore(RuntimeEnvironment.getApplication(), null);
        conformance = new MetadataStoreConformance(store);
    }

    @After
    public void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    public void storesFindsAndDeletesPeer() {
        conformance.storesFindsAndDeletesPeer();
    }

    @Test
    public void peerUpsertReplacesExistingRow() {
        conformance.peerUpsertReplacesExistingRow();
    }

    @Test
    public void storesGroupAndUpdatesLogicalClock() {
        conformance.storesGroupAndUpdatesLogicalClock();
    }

    @Test
    public void managesGroupDevices() {
        conformance.managesGroupDevices();
    }

    @Test
    public void storesGroupRootAndListsGroups() {
        conformance.storesGroupRootAndListsGroups();
    }

    @Test
    public void storesItemWithVector() {
        conformance.storesItemWithVector();
    }

    @Test
    public void itemUpsertReplacesVector() {
        conformance.itemUpsertReplacesVector();
    }

    @Test
    public void pagesItemsAfterCursor() {
        conformance.pagesItemsAfterCursor();
    }

    @Test
    public void advancesItemChangeSequenceAndReturnsOnlyChangedItems() {
        conformance.advancesItemChangeSequenceAndReturnsOnlyChangedItems();
    }

    @Test
    public void keepsChangeSequencesIsolatedPerGroup() {
        conformance.keepsChangeSequencesIsolatedPerGroup();
    }

    @Test
    public void storesAndUpdatesManifestCursors() {
        conformance.storesAndUpdatesManifestCursors();
    }

    @Test
    public void deletesItem() {
        conformance.deletesItem();
    }

    @Test
    public void storesAndFindsVersion() {
        conformance.storesAndFindsVersion();
    }

    @Test
    public void listsRecentVersionsNewestFirst() {
        conformance.listsRecentVersionsNewestFirst();
    }

    @Test
    public void retentionKeepsNewestVersions() {
        conformance.retentionKeepsNewestVersions();
    }

    @Test
    public void storesPagesAndDeletesTombstones() {
        conformance.storesPagesAndDeletesTombstones();
    }

    @Test
    public void resolvesConflicts() {
        conformance.resolvesConflicts();
    }

    @Test
    public void listsDueTriggers() {
        conformance.listsDueTriggers();
    }

    @Test
    public void storesAndUpdatesSyncRun() {
        conformance.storesAndUpdatesSyncRun();
    }

    @Test
    public void transactionCommits() {
        conformance.transactionCommits();
    }

    @Test
    public void transactionRollsBackOnException() {
        conformance.transactionRollsBackOnException();
    }

    @Test
    public void nestedTransactionUsesOuterTransaction() {
        conformance.nestedTransactionUsesOuterTransaction();
    }

    @Test
    public void reopensDatabaseWithoutLosingData() {
        GroupId groupId = GroupId.parse("group009");

        AndroidSqliteMetadataStore first = new AndroidSqliteMetadataStore(
                RuntimeEnvironment.getApplication(), "reopen.db");
        try {
            first.groups().insert(new SyncGroup(groupId, "Home",
                    new SyncRoot("C:\\Docs", "Docs", null), 7, T0));
        } finally {
            first.close();
        }

        AndroidSqliteMetadataStore second = new AndroidSqliteMetadataStore(
                RuntimeEnvironment.getApplication(), "reopen.db");
        try {
            assertTrue(second.groups().find(groupId).isPresent());
            assertEquals(7, second.groups().find(groupId).orElseThrow().logicalClock());
        } finally {
            second.close();
        }
    }
}
