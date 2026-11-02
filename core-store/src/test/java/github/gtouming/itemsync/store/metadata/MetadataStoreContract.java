package github.gtouming.itemsync.store.metadata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

public abstract class MetadataStoreContract {

    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private MetadataStore store;
    private MetadataStoreConformance conformance;

    protected abstract MetadataStore openStore();

    @BeforeEach
    void openStoreForTest() {
        store = openStore();
        conformance = new MetadataStoreConformance(store);
    }

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void storesFindsAndDeletesPeer() {
        conformance.storesFindsAndDeletesPeer();
    }

    @Test
    void peerUpsertReplacesExistingRow() {
        conformance.peerUpsertReplacesExistingRow();
    }

    @Test
    void storesGroupAndUpdatesLogicalClock() {
        conformance.storesGroupAndUpdatesLogicalClock();
    }

    @Test
    void managesGroupMembers() {
        conformance.managesGroupMembers();
    }

    @Test
    void storesRootsPerGroup() {
        conformance.storesRootsPerGroup();
    }

    @Test
    void storesItemWithVector() {
        conformance.storesItemWithVector();
    }

    @Test
    void itemUpsertReplacesVector() {
        conformance.itemUpsertReplacesVector();
    }

    @Test
    void pagesItemsAfterCursor() {
        conformance.pagesItemsAfterCursor();
    }

    @Test
    void advancesItemChangeSequenceAndReturnsOnlyChangedItems() {
        conformance.advancesItemChangeSequenceAndReturnsOnlyChangedItems();
    }

    @Test
    void storesAndUpdatesManifestCursors() {
        conformance.storesAndUpdatesManifestCursors();
    }

    @Test
    void deletesItem() {
        conformance.deletesItem();
    }

    @Test
    void storesAndFindsVersion() {
        conformance.storesAndFindsVersion();
    }

    @Test
    void listsRecentVersionsNewestFirst() {
        conformance.listsRecentVersionsNewestFirst();
    }

    @Test
    void retentionKeepsNewestVersions() {
        conformance.retentionKeepsNewestVersions();
    }

    @Test
    void storesPagesAndDeletesTombstones() {
        conformance.storesPagesAndDeletesTombstones();
    }

    @Test
    void resolvesConflicts() {
        conformance.resolvesConflicts();
    }

    @Test
    void listsDueTriggers() {
        conformance.listsDueTriggers();
    }

    @Test
    void storesAndUpdatesSyncRun() {
        conformance.storesAndUpdatesSyncRun();
    }

    @Test
    void transactionCommits() {
        conformance.transactionCommits();
    }

    @Test
    void transactionRollsBackOnException() {
        conformance.transactionRollsBackOnException();
    }

    @Test
    void nestedTransactionUsesOuterTransaction() {
        conformance.nestedTransactionUsesOuterTransaction();
    }
}
