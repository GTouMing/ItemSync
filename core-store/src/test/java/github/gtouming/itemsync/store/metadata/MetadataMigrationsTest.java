package github.gtouming.itemsync.store.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MetadataMigrationsTest {

    @Test
    void splitsStatementsAndStripsLineComments() {
        List<String> statements = MetadataMigrations.splitStatements(
                "-- leading comment\nCREATE TABLE a (x INTEGER);\n\nCREATE TABLE b (y TEXT); -- trailing\n");

        assertEquals(List.of("CREATE TABLE a (x INTEGER)", "CREATE TABLE b (y TEXT)"), statements);
    }

    @Test
    void ignoresEmptyStatements() {
        assertEquals(List.of(), MetadataMigrations.splitStatements(";;\n-- only comments\n;"));
    }

    @Test
    void exposesPendingMigrations() {
        assertEquals(2, MetadataMigrations.latestVersion());
        assertEquals(2, MetadataMigrations.after(0).size());
        assertEquals(0, MetadataMigrations.after(MetadataMigrations.latestVersion()).size());
        for (MetadataMigrations.Migration migration : MetadataMigrations.all()) {
            assertFalse(migration.statements().isEmpty());
        }
    }
}
