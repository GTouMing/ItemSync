package github.gtouming.itemsync.android;

import android.content.Context;
import android.content.SharedPreferences;

import github.gtouming.itemsync.domain.identity.GroupId;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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

        assertEquals(new File("root-path").getAbsolutePath(), config.rootPath());
        assertEquals("group001", config.groupId());
        assertEquals(47900, config.port());
        assertEquals(60, config.intervalSeconds());
    }

    @Test
    public void persistsEveryGroupAndKeepsTheFirstForLegacyCallers() {
        File rootA = new File(context.getCacheDir(), "root-a");
        File rootB = new File(context.getCacheDir(), "root-b");
        config.saveGroups(List.of(
                new AndroidGroup(GroupId.parse("group001"), "工作", rootA.getAbsolutePath()),
                new AndroidGroup(GroupId.parse("group002"), "家庭", rootB.getAbsolutePath())),
                47900, 60);

        assertEquals(2, config.groups().size());
        assertEquals("group001", config.groupId());
        assertEquals(rootA.getAbsolutePath(), config.rootPath());
        assertEquals(List.of("工作", "家庭"),
                config.groups().stream().map(AndroidGroup::name).toList());
        assertEquals(47900, config.port());
        assertEquals(60, config.intervalSeconds());

        config.removeGroup(GroupId.parse("group001"));
        assertEquals(List.of("group002"),
                config.groups().stream().map(group -> group.id().value()).toList());
        assertEquals("group002", config.groupId());
    }

    @Test
    public void rejectsOverlappingRoots() {
        File root = new File(context.getCacheDir(), "root");
        File nested = new File(root, "nested");
        assertThrows(IllegalArgumentException.class, () -> config.saveGroups(List.of(
                new AndroidGroup(GroupId.parse("group001"), "一", root.getAbsolutePath()),
                new AndroidGroup(GroupId.parse("group002"), "二", nested.getAbsolutePath())),
                47800, 300));
        assertTrue(config.groups().isEmpty());
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
