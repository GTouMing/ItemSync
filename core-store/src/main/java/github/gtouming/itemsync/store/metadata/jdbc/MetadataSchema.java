package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.store.metadata.MetadataException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

final class MetadataSchema {

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "db/migrations/001_init.sql"));

    private MetadataSchema() {
    }

    static void migrate(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        } catch (SQLException e) {
            throw new MetadataException("create schema_version failed", e);
        }

        int current = currentVersion(connection);
        for (Migration migration : MIGRATIONS) {
            if (migration.version() <= current) {
                continue;
            }
            for (String sql : statements(read(migration.resource()))) {
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

    private static String read(String resource) {
        try (InputStream in = MetadataSchema.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing migration resource: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MetadataException("read migration resource failed: " + resource, e);
        }
    }

    static List<String> statements(String sql) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            int comment = line.indexOf("--");
            withoutComments.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : withoutComments.toString().split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return List.copyOf(statements);
    }

    private record Migration(int version, String resource) {
    }
}
