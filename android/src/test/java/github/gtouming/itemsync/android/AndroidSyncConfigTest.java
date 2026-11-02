package github.gtouming.itemsync.android;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36)
public class AndroidSyncConfigTest {

    private Context context;
    private SharedPreferences prefs;
    private AndroidSyncConfig config;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = context.getSharedPreferences("itemsync", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        config = AndroidSyncConfig.of(context);
    }

    @Test
    public void savesAndReloadsConfigurationWithoutManualPeers() {
        config.save("root-path", "group001", 47900, 60);

        assertEquals("root-path", config.rootPath());
        assertEquals("group001", config.groupId());
        assertEquals(47900, config.port());
        assertEquals(60, config.intervalSeconds());
    }

    @Test
    public void saveRemovesLegacyPeerKeys() {
        prefs.edit()
                .putString("peers", "127.0.0.1:47800:fp")
                .putString("expect", "fp")
                .commit();

        config.save("root-path", "group001", 47800, 300);

        assertFalse(prefs.contains("peers"));
        assertFalse(prefs.contains("expect"));
    }
}
