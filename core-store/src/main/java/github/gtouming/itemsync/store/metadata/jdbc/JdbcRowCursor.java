package github.gtouming.itemsync.store.metadata.jdbc;

import github.gtouming.itemsync.store.metadata.MetadataException;
import github.gtouming.itemsync.store.metadata.sql.RowCursor;

import java.sql.ResultSet;
import java.sql.SQLException;

final class JdbcRowCursor implements RowCursor {

    private final ResultSet resultSet;
    private boolean wasNull;

    JdbcRowCursor(ResultSet resultSet) {
        this.resultSet = resultSet;
    }

    @Override
    public boolean next() {
        try {
            return resultSet.next();
        } catch (SQLException e) {
            throw new MetadataException("advance cursor failed", e);
        }
    }

    @Override
    public String getString(String column) {
        try {
            String value = resultSet.getString(column);
            wasNull = resultSet.wasNull();
            return value;
        } catch (SQLException e) {
            throw new MetadataException("read column " + column + " failed", e);
        }
    }

    @Override
    public long getLong(String column) {
        try {
            long value = resultSet.getLong(column);
            wasNull = resultSet.wasNull();
            return value;
        } catch (SQLException e) {
            throw new MetadataException("read column " + column + " failed", e);
        }
    }

    @Override
    public int getInt(String column) {
        try {
            int value = resultSet.getInt(column);
            wasNull = resultSet.wasNull();
            return value;
        } catch (SQLException e) {
            throw new MetadataException("read column " + column + " failed", e);
        }
    }

    @Override
    public boolean wasNull() {
        return wasNull;
    }
}
