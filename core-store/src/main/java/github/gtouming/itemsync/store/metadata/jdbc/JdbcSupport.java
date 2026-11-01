package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.store.metadata.MetadataException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;

final class JdbcSupport {

    private JdbcSupport() {
    }

    static MetadataException fail(String operation, SQLException cause) {
        return new MetadataException(operation + " failed", cause);
    }

    static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value.toEpochMilli());
        }
    }

    static Instant getInstant(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    static <E extends Enum<E>> E getEnum(ResultSet rs, String column, Class<E> type) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    static void setEnum(PreparedStatement ps, int index, Enum<?> value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value.name());
        }
    }
}
