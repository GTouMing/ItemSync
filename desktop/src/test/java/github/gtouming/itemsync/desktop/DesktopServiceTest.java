package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.ConflictId;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.ItemId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.model.Conflict;
import github.gtouming.itemsync.domain.model.GroupDevice;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.domain.model.TriggerType;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.sync.trigger.AlarmSchedule;
import github.gtouming.itemsync.sync.trigger.TriggerConfig;
import github.gtouming.itemsync.platform.desktop.DesktopConfig;
import github.gtouming.itemsync.platform.desktop.DesktopGroup;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.state.VersionHistoryService;
import github.gtouming.itemsync.sync.trust.PeerTrustCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless backend test: one desktop process serves every configured group through a single
 * {@code SyncDevice} while the single-group methods of the service work on the first group.
 */
class DesktopServiceTest {

    private static final char[] PASSWORD = "desktop-service-test".toCharArray();

    @TempDir
    Path temp;

    @Test
    void servesEveryConfiguredGroupFromOneDevice() throws Exception {
        Path data = temp.resolve("data");
        Path rootA = Files.createDirectories(temp.resolve("root-a"));
        Path rootB = Files.createDirectories(temp.resolve("root-b"));
        GroupId groupA = GroupId.parse("group001");
        GroupId groupB = GroupId.parse("group002");
        DesktopConfig config = DesktopConfig.create(data, List.of(
                        new DesktopGroup(groupA, "工作", rootA),
                        new DesktopGroup(groupB, "家庭", rootB)),
                freePort(), "", Duration.ofSeconds(30));

        try (DesktopService service = DesktopService.open(config, PASSWORD)) {
            assertEquals(List.of(groupA, groupB),
                    service.groups().stream().map(DesktopGroup::id).toList());
            assertEquals(Set.of(groupA, groupB), service.groupRuntimes().keySet());
            assertEquals(rootA.toAbsolutePath().normalize(),
                    service.groupRuntimes().get(groupA).root());
            assertEquals(rootB.toAbsolutePath().normalize(),
                    service.groupRuntimes().get(groupB).root());

            assertTrue(service.onlinePeers().isEmpty());
            assertTrue(service.peerViews().isEmpty());
            assertTrue(service.syncNow().isEmpty(), "the first group has no peer to sync with");
            assertTrue(service.syncNow(groupB).isEmpty(), "group two has no peer to sync with");
            assertTrue(service.syncAllNow().isEmpty(), "an all-group sync without peers must pass");

            DesktopService.Status status = service.status();
            assertEquals(groupA, status.groupId(), "the window status must describe the first group");
            assertEquals(config.port(), status.port());
            assertEquals(rootA.toAbsolutePath().normalize(), status.root());
            assertNull(status.lastError());

            service.reconcileTrustedPeers();
            service.refreshRemoteTrust();
            assertTrue(service.openConflicts().isEmpty());
            assertTrue(service.historyItems().isEmpty());
            assertTrue(service.openConflicts(groupA).isEmpty());
            assertTrue(service.historyItems(groupB).isEmpty());
        }
    }

    @Test
    void doesNotServeDisabledGroupsButKeepsTheirTrust() throws Exception {
        Path data = temp.resolve("data");
        Path rootA = Files.createDirectories(temp.resolve("root-a"));
        Path rootB = Files.createDirectories(temp.resolve("root-b"));
        GroupId groupA = GroupId.parse("group001");
        GroupId groupB = GroupId.parse("group002");
        TrustStore trustStore = new TrustStore(data);
        trustStore.trust(groupB, "fp-disabled");
        DesktopConfig config = DesktopConfig.create(data, List.of(
                        new DesktopGroup(groupA, "启用", rootA, true),
                        new DesktopGroup(groupB, "停用", rootB, false)),
                freePort(), "", Duration.ofSeconds(30));

        try (DesktopService service = DesktopService.open(config, PASSWORD)) {
            assertEquals(Set.of(groupA), service.groupRuntimes().keySet());
            assertThrows(IllegalArgumentException.class, () -> service.peerViews(groupB));
        }
        assertEquals(List.of("fp-disabled"), new TrustStore(data).fingerprints(groupB),
                "a disabled group keeps its trust for a later re-enable");
    }

    @Test
    void reportsTheStatusOfTheSelectedGroup() throws Exception {
        Path data = temp.resolve("data");
        Path rootA = Files.createDirectories(temp.resolve("root-a"));
        Path rootB = Files.createDirectories(temp.resolve("root-b"));
        GroupId groupA = GroupId.parse("group001");
        GroupId groupB = GroupId.parse("group002");
        DesktopConfig config = DesktopConfig.create(data, List.of(
                        new DesktopGroup(groupA, "工作", rootA),
                        new DesktopGroup(groupB, "家庭", rootB)),
                freePort(), "", Duration.ofSeconds(30));

        try (DesktopService service = DesktopService.open(config, PASSWORD)) {
            DesktopService.Status first = service.status(groupA);
            DesktopService.Status second = service.status(groupB);

            assertEquals(groupA, first.groupId());
            assertEquals(rootA.toAbsolutePath().normalize(), first.root());
            assertEquals(groupB, second.groupId());
            assertEquals(rootB.toAbsolutePath().normalize(), second.root());
            assertEquals(config.port(), second.port());
            assertEquals(0, first.openConflicts());
            assertEquals(0, second.openConflicts());
            assertEquals(groupA, service.status().groupId(),
                    "the single-group status still describes the first group");

            assertThrows(IllegalArgumentException.class,
                    () -> service.status(GroupId.parse("group999")));
        }
    }

    @Test
    void keepsConflictsAndHistoryInsideTheirGroup() throws Exception {
        Path data = temp.resolve("data");
        Path rootA = Files.createDirectories(temp.resolve("root-a"));
        Path rootB = Files.createDirectories(temp.resolve("root-b"));
        GroupId groupA = GroupId.parse("group001");
        GroupId groupB = GroupId.parse("group002");
        DesktopConfig config = DesktopConfig.create(data, List.of(
                        new DesktopGroup(groupA, "工作", rootA),
                        new DesktopGroup(groupB, "家庭", rootB)),
                freePort(), "", Duration.ofSeconds(30));
        ItemId itemId = seedConflict(data, groupA);

        try (DesktopService service = DesktopService.open(config, PASSWORD)) {
            assertEquals(1, service.openConflicts(groupA).size(),
                    "the conflict of group one belongs to group one");
            assertTrue(service.openConflicts(groupB).isEmpty(),
                    "group two must not list group one's conflict");
            assertEquals(1, service.status(groupA).openConflicts());
            assertEquals(0, service.status(groupB).openConflicts());

            assertEquals(List.of(itemId),
                    service.historyItems(groupA).stream()
                            .map(VersionHistoryService.ItemView::id)
                            .toList());
            assertTrue(service.historyItems(groupB).isEmpty());
            assertTrue(service.versions(groupA, itemId).isEmpty());
        }
    }

    @Test
    void dropsTheTrustOfGroupsThatAreNoLongerConfigured() throws Exception {
        Path data = temp.resolve("data");
        Path rootA = Files.createDirectories(temp.resolve("root-a"));
        GroupId groupA = GroupId.parse("group001");
        GroupId removed = GroupId.parse("group002");
        TrustStore trustStore = new TrustStore(data);
        trustStore.trust(groupA, "fp-a");
        trustStore.trust(removed, "fp-b");
        DesktopConfig config = DesktopConfig.create(data, rootA, groupA, freePort(), "",
                Duration.ofSeconds(30));

        try (DesktopService service = DesktopService.open(config, PASSWORD)) {
            assertTrue(service.onlinePeers().isEmpty());
        }

        TrustStore reloaded = new TrustStore(data);
        assertEquals(List.of("fp-a"), reloaded.fingerprints(groupA));
        assertTrue(reloaded.fingerprints(removed).isEmpty(),
                "deleting a group must drop its trust on the next start");
    }

    @Test
    void keepsKnownDevicesVisibleWhileTheyAreOffline() throws Exception {
        Path data = temp.resolve("data");
        Path rootA = Files.createDirectories(temp.resolve("root-a"));
        Path rootB = Files.createDirectories(temp.resolve("root-b"));
        GroupId groupA = GroupId.parse("group001");
        GroupId groupB = GroupId.parse("group002");
        DeviceId deviceId = DeviceId.of("device-known");
        Instant seen = Instant.parse("2026-01-02T03:04:05Z");
        seedGroupDevice(data, new GroupDevice(groupA, deviceId, "Known peer", "fp-known",
                seen, seen));
        new TrustStore(data).trust(groupA, "fp-known");
        DesktopConfig config = DesktopConfig.create(data, List.of(
                        new DesktopGroup(groupA, "工作", rootA),
                        new DesktopGroup(groupB, "家庭", rootB)),
                freePort(), "", Duration.ofSeconds(30));

        try (DesktopService service = DesktopService.open(config, PASSWORD)) {
            List<PeerTrustCoordinator.PeerView> known = service.peerViews(groupA, false);
            assertEquals(1, known.size());
            assertTrue(PeerTrustCoordinator.PeerView.isOffline(known.get(0)));
            assertEquals(deviceId, known.get(0).presence().peer().deviceId());
            assertTrue(known.get(0).locallyTrusted());
            assertEquals(seen, known.get(0).presence().lastSeen());
            assertTrue(service.peerViews(groupB, false).isEmpty(),
                    "the device row belongs to one group only");
        }
    }

    @Test
    void storesSyncRulesPerGroup() throws Exception {
        Path data = temp.resolve("data");
        Path rootA = Files.createDirectories(temp.resolve("root-a"));
        Path rootB = Files.createDirectories(temp.resolve("root-b"));
        GroupId groupA = GroupId.parse("group001");
        GroupId groupB = GroupId.parse("group002");
        DesktopConfig config = DesktopConfig.create(data, List.of(
                        new DesktopGroup(groupA, "工作", rootA),
                        new DesktopGroup(groupB, "家庭", rootB)),
                freePort(), "", Duration.ofSeconds(30));

        try (DesktopService service = DesktopService.open(config, PASSWORD)) {
            Trigger alarm = service.addTrigger(groupA, new TriggerConfig.Alarm(
                    new AlarmSchedule(Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                            LocalTime.of(23, 0), ZoneId.of("Asia/Shanghai"))));
            assertEquals(TriggerType.ALARM, alarm.type());
            assertTrue(alarm.enabled());
            assertNotNull(alarm.nextFireAt());

            Trigger directory = service.addTrigger(groupA,
                    new TriggerConfig.DirectoryChange());
            assertEquals(TriggerType.CONDITION, directory.type());
            assertEquals(2, service.triggers(groupA).size());
            assertTrue(service.triggers(groupB).isEmpty());

            Trigger disabled = service.setTriggerEnabled(groupA, alarm.id(), false);
            assertFalse(disabled.enabled());
            assertFalse(service.triggers(groupA).stream()
                    .filter(trigger -> trigger.id().equals(alarm.id()))
                    .findFirst().orElseThrow().enabled());

            assertThrows(IOException.class,
                    () -> service.deleteTrigger(groupB, alarm.id()),
                    "a group must not delete another group's rule");
            service.deleteTrigger(groupA, directory.id());
            assertEquals(List.of(alarm.id()), service.triggers(groupA).stream()
                    .map(Trigger::id).toList());
        }
    }

    @Test
    void opensAnInstallationWithoutAnyGroup() throws Exception {
        Path data = temp.resolve("data");
        DesktopConfig config = DesktopConfig.create(data, List.of(), freePort(), "",
                Duration.ofSeconds(30));

        try (DesktopService service = DesktopService.open(config, PASSWORD)) {
            assertTrue(service.groups().isEmpty());
            assertTrue(service.groupRuntimes().isEmpty());
            assertTrue(service.syncNow().isEmpty());
            assertTrue(service.syncAllNow().isEmpty());
            assertTrue(service.onlinePeers().isEmpty());
            assertTrue(service.peerViews().isEmpty());
            assertTrue(service.openConflicts().isEmpty());
            assertTrue(service.historyItems().isEmpty());

            DesktopService.Status status = service.status();
            assertNull(status.groupId());
            assertNull(status.root());
            assertNull(status.lastError());
            assertEquals(config.port(), status.port());
        }
    }

    private static void seedGroupDevice(Path data, GroupDevice device) throws Exception {
        Path dbDirectory = Files.createDirectories(data.resolve("db"));
        try (JdbcMetadataStore store = JdbcMetadataStore.open(
                "jdbc:sqlite:" + dbDirectory.resolve("itemsync.sqlite").toAbsolutePath())) {
            store.devices().upsert(device);
        }
    }

    /**
     * Writes one item and one unresolved conflict of {@code group} into the metadata store the
     * service will open, so the group aware reads can be checked without a peer.
     */
    private static ItemId seedConflict(Path data, GroupId group) throws Exception {
        Path dbDirectory = Files.createDirectories(data.resolve("db"));
        try (JdbcMetadataStore store = JdbcMetadataStore.open(
                "jdbc:sqlite:" + dbDirectory.resolve("itemsync.sqlite").toAbsolutePath())) {
            ItemId itemId = ItemId.of(UUID.randomUUID().toString());
            store.items().upsert(new Item(itemId, group, LogicalPath.of("docs/a.txt"),
                    ItemKind.FILE, null, VersionVector.empty(), null));
            store.conflicts().insert(new Conflict(
                    ConflictId.of(UUID.randomUUID().toString()), itemId, null, null,
                    VersionVector.empty(), null, Instant.parse("2026-01-01T00:00:00Z"), null));
            return itemId;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
