package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.model.GroupDevice;
import github.gtouming.itemsync.domain.model.Item;
import github.gtouming.itemsync.domain.model.SyncGroup;
import github.gtouming.itemsync.domain.model.SyncRoot;
import github.gtouming.itemsync.domain.model.SyncRun;
import github.gtouming.itemsync.domain.model.SyncRunState;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.domain.model.TriggerType;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.store.metadata.MetadataMigrations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies migration 004 rewrites a version 3 database from root-keyed rows to group-keyed rows.
 */
class MigrationV4Test {

    private static final GroupId GROUP = GroupId.parse("group001");
    private static final GroupId ORPHAN_GROUP = GroupId.parse("group002");
    private static final NodeId NODE_A = NodeId.of("node-a");
    private static final NodeId NODE_B = NodeId.of("node-b");
    private static final DeviceId DEVICE_B = DeviceId.of("device-b");
    private static final Instant CREATED_AT = Instant.ofEpochMilli(1000);

    @TempDir
    Path temp;

    @Test
    void migratesRootKeyedRowsToGroupKeyedRows() throws Exception {
        String url = "jdbc:sqlite:" + temp.resolve("legacy.db").toAbsolutePath();
        createVersion3Database(url);

        try (JdbcMetadataStore store = JdbcMetadataStore.open(url)) {
            SyncGroup group = store.groups().find(GROUP).orElseThrow();
            assertEquals("Home", group.name());
            assertEquals(new SyncRoot("C:\\Docs", "Docs", "*.tmp"), group.root());
            assertEquals(42, group.logicalClock());
            assertEquals(CREATED_AT, group.createdAt());
            assertEquals(List.of(group), store.groups().findAll());
            assertTrue(store.groups().find(ORPHAN_GROUP).isEmpty());
        }

        try (JdbcMetadataStore store = JdbcMetadataStore.open(url)) {
            GroupDevice device = store.devices().findByGroup(GROUP).get(0);
            assertEquals(new GroupDevice(GROUP, DEVICE_B, "Beta", "fingerprint-b",
                    Instant.ofEpochMilli(2000), Instant.ofEpochMilli(1500)), device);

            Item item = store.items().find(GROUP, LogicalPath.of("docs/a.txt")).orElseThrow();
            assertEquals("item-1", item.id().value());
            assertEquals(VersionVector.of(Map.of(NODE_A, 3L)), item.vector());
            assertEquals(1L, store.items().currentSequence(GROUP));
            assertEquals(1, store.items().changedSince(GROUP, 0, 1).size());

            assertEquals(7L, store.cursors().pullSequence(GROUP, NODE_B));
            assertEquals(5L, store.cursors().pushSequence(GROUP, NODE_B));

            Trigger trigger = store.triggers().find(TriggerId.of("trig-1")).orElseThrow();
            assertEquals(GROUP, trigger.groupId());
            assertEquals(TriggerType.ALARM, trigger.type());
            assertEquals(List.of(trigger), store.triggers().findByGroup(GROUP));

            Optional<SyncRun> run = store.runs().find(SyncRunId.of("run-1"));
            assertTrue(run.isPresent());
            assertEquals(GROUP, run.get().groupId());
            assertEquals(SyncRunState.COMPLETED, run.get().state());
            assertEquals(List.of(run.get()), store.runs().recent(GROUP, 5));
        }

        try (Connection connection = DriverManager.getConnection(url)) {
            assertFalse(tableExists(connection, "sync_root"));
            assertFalse(tableExists(connection, "group_member"));
            for (String table : List.of("item", "item_vector", "item_change", "sync_cursor")) {
                Set<String> columns = columns(connection, table);
                assertTrue(columns.contains("group_id"), table + " keeps group_id");
                assertFalse(columns.contains("root_id"), table + " drops root_id");
            }
            for (String table : List.of("trigger", "sync_run")) {
                assertFalse(columns(connection, table).contains("root_id"), table + " drops root_id");
            }
            assertTrue(columns(connection, "sync_group").containsAll(
                    List.of("physical_path", "display_name", "ignore_snapshot")));
        }
    }

    private static void createVersion3Database(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            }
            for (MetadataMigrations.Migration migration : MetadataMigrations.all()) {
                if (migration.version() > 3) {
                    continue;
                }
                try (Statement statement = connection.createStatement()) {
                    for (String sql : migration.statements()) {
                        statement.execute(sql);
                    }
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM schema_version");
                statement.execute("INSERT INTO schema_version(version) VALUES (3)");
                statement.execute("INSERT INTO sync_group(id, name, logical_clock, created_at) "
                        + "VALUES ('group001', 'Home', 42, 1000)");
                statement.execute("INSERT INTO sync_group(id, name, logical_clock, created_at) "
                        + "VALUES ('group002', 'Orphan', 0, 1000)");
                statement.execute("INSERT INTO sync_root(id, group_id, physical_path, display_name, "
                        + "ignore_snapshot, created_at) "
                        + "VALUES ('root-1', 'group001', 'C:\\Docs', 'Docs', '*.tmp', 1000)");
                statement.execute("INSERT INTO peer(node_id, device_id, device_name, platform, "
                        + "cert_fingerprint, last_seen_at) "
                        + "VALUES ('node-b', 'device-b', 'Beta', 'WINDOWS', 'fingerprint-b', 2000)");
                statement.execute("INSERT INTO group_member(group_id, node_id, display_name, consent, "
                        + "blocked, joined_at) VALUES ('group001', 'node-b', 'Beta', 1, 0, 1500)");
                statement.execute("INSERT INTO item(id, root_id, logical_path, kind, "
                        + "current_version_id, deleted_at) "
                        + "VALUES ('item-1', 'root-1', 'docs/a.txt', 'FILE', 'ver-1', NULL)");
                statement.execute("INSERT INTO item_vector(root_id, logical_path, node_id, counter) "
                        + "VALUES ('root-1', 'docs/a.txt', 'node-a', 3)");
                statement.execute("INSERT INTO item_change(root_id, logical_path) "
                        + "VALUES ('root-1', 'docs/a.txt')");
                statement.execute("INSERT INTO sync_cursor(root_id, node_id, pull_sequence, push_sequence) "
                        + "VALUES ('root-1', 'node-b', 7, 5)");
                statement.execute("INSERT INTO trigger(id, group_id, root_id, type, config_json, enabled, "
                        + "next_fire_at) VALUES ('trig-1', 'group001', 'root-1', 'ALARM', '{}', 1, 5000)");
                statement.execute("INSERT INTO sync_run(id, group_id, root_id, trigger_id, "
                        + "source_node_id, state, started_at, ended_at, stats_json, error_code) "
                        + "VALUES ('run-1', 'group001', 'root-1', 'trig-1', 'node-a', 'COMPLETED', "
                        + "3000, 4000, '{\"items\":1}', NULL)");
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'")) {
            return rs.next();
        }
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }
}
