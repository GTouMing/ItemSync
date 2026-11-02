package github.gtouming.itemsync.android;

import github.gtouming.itemsync.domain.identity.GroupId;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36)
public class AndroidTrustStoreTest {

    private static final GroupId GROUP_A = GroupId.parse("group001");
    private static final GroupId GROUP_B = GroupId.parse("group002");

    private AndroidTrustStore store;

    @Before
    public void setUp() throws Exception {
        store = new AndroidTrustStore(RuntimeEnvironment.getApplication());
        store.clear();
    }

    @After
    public void tearDown() throws Exception {
        store.clear();
    }

    @Test
    public void persistsTrustPerGroupAndFingerprint() throws Exception {
        store.trust(GROUP_A, "ABCDEF");

        assertTrue(store.isTrusted(GROUP_A, "abcdef"));
        assertFalse(store.isTrusted(GROUP_B, "abcdef"));
    }

    @Test
    public void untrustRemovesOnlyTheSelectedFingerprint() throws Exception {
        store.trust(GROUP_A, "fingerprint-a");
        store.trust(GROUP_A, "fingerprint-b");

        store.untrust(GROUP_A, "fingerprint-a");

        assertFalse(store.isTrusted(GROUP_A, "fingerprint-a"));
        assertTrue(store.isTrusted(GROUP_A, "fingerprint-b"));
    }

    @Test
    public void retainOnlyDropsOtherGroups() throws Exception {
        store.trust(GROUP_A, "fingerprint-a");
        store.trust(GROUP_B, "fingerprint-b");

        store.retainOnly(GROUP_B);

        assertFalse(store.isTrusted(GROUP_A, "fingerprint-a"));
        assertTrue(store.isTrusted(GROUP_B, "fingerprint-b"));
    }

    @Test
    public void clearRemovesEveryGroup() throws Exception {
        store.trust(GROUP_A, "fingerprint-a");
        store.trust(GROUP_B, "fingerprint-b");

        store.clear();

        assertFalse(store.isTrusted(GROUP_A, "fingerprint-a"));
        assertFalse(store.isTrusted(GROUP_B, "fingerprint-b"));
    }
}
