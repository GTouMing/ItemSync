package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.store.metadata.MetadataException;
import github.gtouming.itemsync.store.metadata.MetadataMigrations;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class JdbcMetadataSchema {

    private JdbcMetadataSchema() {
    }

    static void migrate(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        } catch (SQLException e) {
            throw new MetadataException("create schema_version failed", e);
        }

        int current = currentVersion(connection);
        for (MetadataMigrations.Migration migration : MetadataMigrations.after(current)) {
            for (String sql : migration.statements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                } catch (SQLException e) {
                    throw new MetadataException("migration " + migration.version() + " failed", e);
                }
            }
            recordVersion(connection, migration.version());
        }
    }

    private static int currentVersion(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new MetadataException("read schema version failed", e);
        }
    }

    private static void recordVersion(Connection connection, int version) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM schema_version");
        } catch (SQLException e) {
            throw new MetadataException("clear schema version failed", e);
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO schema_version(version) VALUES (?)")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataException("write schema version failed", e);
        }
    }
}
