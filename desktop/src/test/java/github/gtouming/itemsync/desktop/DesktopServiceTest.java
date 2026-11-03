package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.platform.desktop.DesktopConfig;
import github.gtouming.itemsync.platform.desktop.DesktopGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
