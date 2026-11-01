package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.store.metadata.MetadataException;
import github.gtouming.itemsync.store.metadata.sql.RowConsumer;
import github.gtouming.itemsync.store.metadata.sql.RowMapper;
import github.gtouming.itemsync.store.metadata.sql.SqlSession;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class JdbcSqlSession implements SqlSession {

    private final Connection connection;
    private boolean inTransaction;

    JdbcSqlSession(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public void execute(String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw fail("execute", e);
        }
    }

    @Override
    public int update(String sql, Object... arguments) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, arguments);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw fail("update", e);
        }
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
        List<T> results = new ArrayList<>();
        queryEach(sql, cursor -> results.add(mapper.map(cursor)), arguments);
        return List.copyOf(results);
    }

    @Override
    public void queryEach(String sql, RowConsumer consumer, Object... arguments) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, arguments);
            try (ResultSet rs = ps.executeQuery()) {
                JdbcRowCursor cursor = new JdbcRowCursor(rs);
                while (cursor.next()) {
                    consumer.accept(cursor);
                }
            }
        } catch (SQLException e) {
            throw fail("query", e);
        }
    }

    @Override
    public void begin() {
        try {
            connection.setAutoCommit(false);
            inTransaction = true;
        } catch (SQLException e) {
            throw fail("begin transaction", e);
        }
    }

    @Override
    public void commit() {
        try {
            connection.commit();
            inTransaction = false;
            restoreAutoCommit();
        } catch (SQLException e) {
            throw fail("commit transaction", e);
        }
    }

    @Override
    public void rollback() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            throw fail("rollback transaction", e);
        } finally {
            inTransaction = false;
            restoreAutoCommit();
        }
    }

    @Override
    public boolean inTransaction() {
        return inTransaction;
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // the original failure is more useful than a failed state restore
        }
    }

    private static void bind(PreparedStatement ps, Object... arguments) throws SQLException {
        for (int i = 0; i < arguments.length; i++) {
            Object value = arguments[i];
            int index = i + 1;
            if (value == null) {
                ps.setObject(index, null);
            } else if (value instanceof String text) {
                ps.setString(index, text);
            } else if (value instanceof Long number) {
                ps.setLong(index, number);
            } else if (value instanceof Integer number) {
                ps.setLong(index, number);
            } else if (value instanceof Boolean flag) {
                ps.setLong(index, flag ? 1 : 0);
            } else if (value instanceof Instant instant) {
                ps.setLong(index, instant.toEpochMilli());
            } else {
                throw new IllegalArgumentException("unsupported sql argument type: " + value.getClass().getName());
            }
        }
    }

    private static MetadataException fail(String operation, SQLException cause) {
        return new MetadataException(operation + " failed", cause);
    }
}
