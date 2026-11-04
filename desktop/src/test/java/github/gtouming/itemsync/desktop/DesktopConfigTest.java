package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.platform.desktop.DesktopConfig;
import github.gtouming.itemsync.platform.desktop.DesktopGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopConfigTest {

    private static final int PORT = 47800;

    @TempDir
    Path temp;

    @Test
    void loadsConfiguration() throws Exception {
        Path root = temp.resolve("root");
        Files.createDirectories(root);
        Properties properties = new Properties();
        properties.setProperty("root", root.toString());
        properties.setProperty("group", "group001");
        properties.setProperty("root-id", "root-1");
        properties.setProperty("port", "47800");
        properties.setProperty("launch-command", "C:\\Apps\\ItemSync.exe");
        properties.setProperty("debounce-seconds", "5");
        writeProperties(temp.resolve(DesktopConfig.FILE_NAME), properties);

        DesktopConfig config = DesktopConfig.load(temp);

        assertEquals(root.toAbsolutePath(), config.root());
        assertEquals("group001", config.group().value());
        assertEquals(47800, config.port());
        assertEquals("C:\\Apps\\ItemSync.exe", config.launchCommand());
        assertEquals(5, config.debounce().toSeconds());
        assertEquals(1, config.groups().size());
    }

    @Test
    void migratesTheLegacySingleGroupIntoTheGroupList() throws Exception {
        Path root = Files.createDirectories(temp.resolve("root"));
        writeProperties(temp.resolve(DesktopConfig.FILE_NAME), legacyProperties(root, "group001"));

        DesktopConfig config = DesktopConfig.load(temp);

        assertEquals(1, config.groups().size());
        DesktopGroup migrated = config.groups().get(0);
        assertEquals(GroupId.parse("group001"), migrated.id());
        assertEquals(root.toAbsolutePath(), migrated.root());
        assertEquals("group001", migrated.name(),
                "a migrated group without a name should fall back to its id");

        Properties written = readProperties(temp.resolve(DesktopConfig.GROUPS_FILE_NAME));
        assertEquals("1", written.getProperty("group.count"));
        assertEquals("group001", written.getProperty("group.1.id"));
        assertEquals(root.toAbsolutePath().toString(), written.getProperty("group.1.root"));

        assertEquals(config.groups(), DesktopConfig.load(temp).groups(),
                "the migrated group list is not stable across reloads");
    }

    @Test
    void rejectsMissingRequiredKeys() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("group", "group001");
        writeProperties(temp.resolve(DesktopConfig.FILE_NAME), properties);

        assertThrows(IllegalArgumentException.class, () -> DesktopConfig.load(temp));
    }

    @Test
    void createsAndReloadsConfiguration() throws Exception {
        Path root = temp.resolve("root");
        DesktopConfig config = DesktopConfig.create(temp, root, GroupId.random(),
                47900, "C:\\Apps\\ItemSync.exe", Duration.ofSeconds(10));

        assertEquals(root.toAbsolutePath(), config.root());
        assertEquals(47900, config.port());
        assertEquals("C:\\Apps\\ItemSync.exe", config.launchCommand());
        assertEquals(10, config.debounce().toSeconds());
        assertEquals(1, config.groups().size());
    }

    @Test
    void createRejectsDataDirectoryInsideRoot() {
        assertThrows(IllegalArgumentException.class, () -> DesktopConfig.create(temp, temp,
                GroupId.random(), 47800, "", Duration.ofSeconds(30)));
    }

    @Test
    void changingGroupPreservesRootPortAndLaunchCommand() throws Exception {
        Path root = temp.resolve("root");
        DesktopConfig created = DesktopConfig.create(temp, root, GroupId.parse("group001"),
                47900, "C:\\Apps\\ItemSync.exe", Duration.ofSeconds(10));

        DesktopConfig changed = DesktopConfig.changeGroup(temp, GroupId.parse("group002"));

        assertEquals("group002", changed.group().value());
        assertEquals(created.root(), changed.root());
        assertEquals(47900, changed.port());
        assertEquals("C:\\Apps\\ItemSync.exe", changed.launchCommand());
        assertEquals(10, changed.debounce().toSeconds());
        assertEquals(1, changed.groups().size());
        assertEquals("group002", DesktopConfig.load(temp).group().value());
    }

    @Test
    void legacyPeerKeysAreIgnored() throws Exception {
        Path root = temp.resolve("root");
        Files.createDirectories(root);
        Properties properties = new Properties();
        properties.setProperty("root", root.toString());
        properties.setProperty("group", "group001");
        properties.setProperty("root-id", "root-1");
        properties.setProperty("port", "47800");
        properties.setProperty("expect", "peer-fingerprint");
        properties.setProperty("peers", "127.0.0.1:47801:fp-b,127.0.0.1:47802:fp-c");
        writeProperties(temp.resolve(DesktopConfig.FILE_NAME), properties);

        DesktopConfig config = DesktopConfig.load(temp);

        assertEquals("group001", config.group().value());
        assertEquals(47800, config.port());
        assertEquals(root.toAbsolutePath(), config.root());
    }

    @Test
    void createDoesNotWriteLegacyKeys() throws Exception {
        DesktopConfig.create(temp, temp.resolve("root"), GroupId.random(),
                47900, "", Duration.ofSeconds(10));

        Properties properties = readProperties(temp.resolve(DesktopConfig.FILE_NAME));

        assertFalse(properties.containsKey("expect"));
        assertFalse(properties.containsKey("peers"));
    }

    @Test
    void rejectsRootContainingDataDirectory() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("root", temp.toString());
        properties.setProperty("group", "group001");
        writeProperties(temp.resolve(DesktopConfig.FILE_NAME), properties);

        assertThrows(IllegalArgumentException.class, () -> DesktopConfig.load(temp));
    }

    @Test
    void persistsEveryGroupAndKeepsTheFirstGroupForSingleGroupCallers() throws Exception {
        Path rootA = Files.createDirectories(temp.resolve("root-a"));
        Path rootB = Files.createDirectories(temp.resolve("root-b"));
        DesktopGroup first = new DesktopGroup(GroupId.parse("group001"), "工作", rootA);
        DesktopGroup second = new DesktopGroup(GroupId.parse("group002"), "家庭", rootB);

        DesktopConfig created = DesktopConfig.create(temp, List.of(first, second), 47901, "",
                Duration.ofSeconds(30));

        assertEquals(List.of(first, second), created.groups());
        assertEquals(first.id(), created.group());
        assertEquals(first.root(), created.root());

        DesktopConfig reloaded = DesktopConfig.load(temp);
        assertEquals(List.of(first, second), reloaded.groups());
        assertEquals("工作", reloaded.groups().get(0).name(),
                "the local group name was not persisted");
        assertEquals(47901, reloaded.port());

        Properties legacy = readProperties(temp.resolve(DesktopConfig.FILE_NAME));
        assertEquals("group001", legacy.getProperty("group"),
                "the legacy keys must still describe the first group");
        assertEquals(rootA.toAbsolutePath().normalize().toString(),
                legacy.getProperty("root"));
    }

    @Test
    void theGroupFileWinsOverTheLegacyKeys() throws Exception {
        Path rootA = Files.createDirectories(temp.resolve("root-a"));
        Path rootB = Files.createDirectories(temp.resolve("root-b"));
        DesktopConfig.create(temp, List.of(
                new DesktopGroup(GroupId.parse("group001"), "a", rootA),
                new DesktopGroup(GroupId.parse("group002"), "b", rootB)),
                PORT, "", Duration.ofSeconds(30));

        Properties legacy = new Properties();
        legacy.setProperty("root", rootB.toString());
        legacy.setProperty("group", "group009");
        legacy.setProperty("port", "47800");
        writeProperties(temp.resolve(DesktopConfig.FILE_NAME), legacy);

        DesktopConfig reloaded = DesktopConfig.load(temp);

        assertEquals(List.of(GroupId.parse("group001"), GroupId.parse("group002")),
                reloaded.groups().stream().map(DesktopGroup::id).toList());
        assertEquals("group001", reloaded.group().value());
    }

    @Test
    void persistsTheEnabledFlagOfEachGroup() throws Exception {
        Path rootA = Files.createDirectories(temp.resolve("root-a"));
        Path rootB = Files.createDirectories(temp.resolve("root-b"));
        GroupId groupA = GroupId.parse("group001");
        GroupId groupB = GroupId.parse("group002");
        DesktopConfig config = DesktopConfig.create(temp, List.of(
                        new DesktopGroup(groupA, "a", rootA),
                        new DesktopGroup(groupB, "b", rootB)),
                PORT, "", Duration.ofSeconds(30));

        config = config.setGroupEnabled(groupB, false);

        DesktopConfig reloaded = DesktopConfig.load(temp);
        assertEquals(true, reloaded.findGroup(groupA).orElseThrow().enabled());
        assertEquals(false, reloaded.findGroup(groupB).orElseThrow().enabled());
    }

    @Test
    void addsRenamesAndRemovesGroups() throws Exception {
        Path rootA = Files.createDirectories(temp.resolve("root-a"));
        Path rootB = Files.createDirectories(temp.resolve("root-b"));
        DesktopConfig config = DesktopConfig.create(temp, rootA, GroupId.parse("group001"),
                PORT, "", Duration.ofSeconds(30));

        config = config.addGroup(new DesktopGroup(GroupId.parse("group002"), "第二个", rootB));
        assertEquals(2, config.groups().size());

        config = config.renameGroup(GroupId.parse("group002"), "改名后");
        assertEquals("改名后", config.groups().get(1).name());
        assertEquals(rootB.toAbsolutePath().normalize(), config.groups().get(1).root());

        config = config.removeGroup(GroupId.parse("group002"));
        assertEquals(1, config.groups().size());
        assertEquals(GroupId.parse("group001"), config.group());
        assertEquals(1, DesktopConfig.load(temp).groups().size());

        DesktopConfig remaining = config;
        assertThrows(IllegalArgumentException.class,
                () -> remaining.removeGroup(GroupId.parse("group002")));
        assertThrows(IllegalArgumentException.class,
                () -> remaining.renameGroup(GroupId.parse("group008"), "x"));
    }

    @Test
    void loadsAnInstallationWithoutAnyGroup() throws Exception {
        DesktopConfig created = DesktopConfig.create(temp, List.of(), PORT, "",
                Duration.ofSeconds(30));

        DesktopConfig reloaded = DesktopConfig.load(temp);

        assertTrue(reloaded.groups().isEmpty());
        assertFalse(reloaded.hasGroups());
        assertFalse(created.hasGroups());
        assertThrows(IllegalStateException.class, reloaded::group);
        assertThrows(IllegalStateException.class, reloaded::root);
        assertEquals(PORT, reloaded.port());
    }

    @Test
    void rejectsDuplicateGroupIds() throws Exception {
        Path rootA = temp.resolve("root-a");
        Path rootB = temp.resolve("root-b");
        GroupId duplicated = GroupId.parse("group001");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DesktopConfig.create(temp, List.of(
                        new DesktopGroup(duplicated, "a", rootA),
                        new DesktopGroup(duplicated, "b", rootB)),
                        PORT, "", Duration.ofSeconds(30)));
        assertTrue(failure.getMessage().contains("duplicate"),
                "unexpected message: " + failure.getMessage());

        DesktopConfig config = DesktopConfig.create(temp, rootA, duplicated, PORT, "",
                Duration.ofSeconds(30));
        IllegalArgumentException added = assertThrows(IllegalArgumentException.class,
                () -> config.addGroup(new DesktopGroup(duplicated, "b", rootB)));
        assertTrue(added.getMessage().contains("duplicate"),
                "unexpected message: " + added.getMessage());
    }

    @Test
    void rejectsTheSameRootInTwoGroups() {
        Path root = temp.resolve("shared-root");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DesktopConfig.create(temp, List.of(
                        new DesktopGroup(GroupId.parse("group001"), "a", root),
                        new DesktopGroup(GroupId.parse("group002"), "b", root)),
                        PORT, "", Duration.ofSeconds(30)));
        assertTrue(failure.getMessage().contains("overlap"),
                "unexpected message: " + failure.getMessage());
    }

    @Test
    void rejectsNestedRootsAcrossGroups() throws Exception {
        Path outer = Files.createDirectories(temp.resolve("outer"));
        Path inner = Files.createDirectories(outer.resolve("inner"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DesktopConfig.create(temp, List.of(
                        new DesktopGroup(GroupId.parse("group001"), "a", outer),
                        new DesktopGroup(GroupId.parse("group002"), "b", inner)),
                        PORT, "", Duration.ofSeconds(30)));
        assertTrue(failure.getMessage().contains("overlap"),
                "unexpected message: " + failure.getMessage());

        DesktopConfig config = DesktopConfig.create(temp, outer, GroupId.parse("group003"),
                PORT, "", Duration.ofSeconds(30));
        assertThrows(IllegalArgumentException.class,
                () -> config.addGroup(new DesktopGroup(GroupId.parse("group004"), "d", inner)));
    }

    @Test
    void siblingRootsWithACommonPrefixAreNotNested() throws Exception {
        Path root = Files.createDirectories(temp.resolve("root"));
        Path sibling = Files.createDirectories(temp.resolve("root-2"));

        DesktopConfig config = DesktopConfig.create(temp, List.of(
                new DesktopGroup(GroupId.parse("group001"), "a", root),
                new DesktopGroup(GroupId.parse("group002"), "b", sibling)),
                PORT, "", Duration.ofSeconds(30));

        assertEquals(2, config.groups().size());
        assertNotEquals(config.groups().get(0).root(), config.groups().get(1).root());
    }

    @Test
    void rejectsMoreGroupsThanTheAdrAllows() {
        List<DesktopGroup> groups = new ArrayList<>();
        GroupId[] ids = {
                GroupId.parse("group001"), GroupId.parse("group002"), GroupId.parse("group003"),
                GroupId.parse("group004"), GroupId.parse("group005"), GroupId.parse("group006")};
        for (int index = 0; index < ids.length; index++) {
            groups.add(new DesktopGroup(ids[index], "g" + index,
                    temp.resolve("root-" + index)));
        }

        assertThrows(IllegalArgumentException.class,
                () -> DesktopConfig.create(temp, groups, PORT, "", Duration.ofSeconds(30)));
    }

    @Test
    void rejectsAGroupFileWithAMissingEntry() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("group.count", "2");
        properties.setProperty("group.1.id", "group001");
        properties.setProperty("group.1.name", "a");
        properties.setProperty("group.1.root", temp.resolve("root-a").toString());
        writeProperties(temp.resolve(DesktopConfig.FILE_NAME), legacyProperties(
                temp.resolve("root-a"), "group001"));
        writeProperties(temp.resolve(DesktopConfig.GROUPS_FILE_NAME), properties);

        assertThrows(IllegalArgumentException.class, () -> DesktopConfig.load(temp));
    }

    private static Properties legacyProperties(Path root, String group) {
        Properties properties = new Properties();
        properties.setProperty("root", root.toString());
        properties.setProperty("group", group);
        properties.setProperty("port", "47800");
        return properties;
    }

    private static void writeProperties(Path file, Properties properties) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file)) {
            properties.store(writer, null);
        }
    }

    private static Properties readProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        return properties;
    }
}
