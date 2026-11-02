package github.gtouming.itemsync.store.metadata;

import github.gtouming.itemsync.domain.compat.Compat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MetadataMigrations {

    private static final List<Migration> MIGRATIONS = Compat.list(
            new Migration(1, "db/migrations/001_init.sql"),
            new Migration(2, "db/migrations/002_conflict_remote_vector.sql"),
            new Migration(3, "db/migrations/003_manifest_changes.sql"));

    private MetadataMigrations() {
    }

    public static List<Migration> all() {
        return MIGRATIONS;
    }

    public static int latestVersion() {
        return MIGRATIONS.get(MIGRATIONS.size() - 1).version();
    }

    public static List<Migration> after(int version) {
        List<Migration> pending = new ArrayList<>();
        for (Migration migration : MIGRATIONS) {
            if (migration.version() > version) {
                pending.add(migration);
            }
        }
        return Compat.copyList(pending);
    }

    public static List<String> splitStatements(String sql) {
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
        return Compat.copyList(statements);
    }

    public record Migration(int version, String resource) {

        public List<String> statements() {
            return splitStatements(read(resource));
        }
    }

    private static String read(String resource) {
        try (InputStream in = MetadataMigrations.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing migration resource: " + resource);
            }
            return new String(Compat.readAllBytes(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("read migration resource failed: " + resource, e);
        }
    }
}
