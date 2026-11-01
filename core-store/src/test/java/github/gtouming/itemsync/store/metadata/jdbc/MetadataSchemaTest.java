package github.gtouming.itemsync.store.metadata.jdbc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetadataSchemaTest {

    @Test
    void splitsStatementsAndStripsLineComments() {
        List<String> statements = MetadataSchema.statements(
                "-- leading comment\nCREATE TABLE a (x INTEGER);\n\nCREATE TABLE b (y TEXT); -- trailing\n");

        assertEquals(List.of("CREATE TABLE a (x INTEGER)", "CREATE TABLE b (y TEXT)"), statements);
    }

    @Test
    void ignoresEmptyStatements() {
        assertEquals(List.of(), MetadataSchema.statements(";;\n-- only comments\n;"));
    }
}
