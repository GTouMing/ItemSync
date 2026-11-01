package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.store.metadata.MetadataException;
import github.gtouming.itemsync.store.metadata.sql.SqlMetadataStore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

public final class JdbcMetadataStore extends SqlMetadataStore {

    private final Connection connection;

    public JdbcMetadataStore(Connection connection) {
        super(new JdbcSqlSession(Objects.requireNonNull(connection, "connection")));
        this.connection = connection;
        JdbcMetadataSchema.migrate(connection);
    }

    public static JdbcMetadataStore open(String jdbcUrl) throws SQLException {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        return new JdbcMetadataStore(DriverManager.getConnection(jdbcUrl));
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new MetadataException("close metadata store failed", e);
        }
    }
}
