package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.GroupId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustStoreTest {

    private static final GroupId GROUP = GroupId.parse("group001");

    @TempDir
    Path temp;

    @Test
    void persistsTrustPerGroupAndFingerprint() throws Exception {
        TrustStore store = new TrustStore(temp);
        String fingerprint = "a".repeat(64);

        assertFalse(store.isTrusted(GROUP, fingerprint));
        store.trust(GROUP, fingerprint);
        assertTrue(store.isTrusted(GROUP, fingerprint));
        assertFalse(store.isTrusted(GroupId.parse("group002"), fingerprint));

        TrustStore reloaded = new TrustStore(temp);
        assertTrue(reloaded.isTrusted(GROUP, fingerprint));

        reloaded.untrust(GROUP, fingerprint);
        assertFalse(new TrustStore(temp).isTrusted(GROUP, fingerprint));
    }

    @Test
    void dropsTrustForOtherGroupsWhenTheGroupChanges() throws Exception {
        TrustStore store = new TrustStore(temp);
        String fingerprint = "a".repeat(64);
        GroupId other = GroupId.parse("group002");
        store.trust(GROUP, fingerprint);
        store.trust(other, fingerprint);

        store.retainOnly(other);

        assertFalse(store.isTrusted(GROUP, fingerprint));
        assertTrue(store.isTrusted(other, fingerprint));
        assertFalse(new TrustStore(temp).isTrusted(GROUP, fingerprint));
        assertTrue(new TrustStore(temp).isTrusted(other, fingerprint));
    }

    @Test
    void clearRemovesEveryGroup() throws Exception {
        TrustStore store = new TrustStore(temp);
        String fingerprint = "a".repeat(64);
        GroupId other = GroupId.parse("group002");
        store.trust(GROUP, fingerprint);
        store.trust(other, fingerprint);

        store.clear();

        assertFalse(store.isTrusted(GROUP, fingerprint));
        assertFalse(store.isTrusted(other, fingerprint));
        assertFalse(new TrustStore(temp).isTrusted(GROUP, fingerprint));
    }
}
