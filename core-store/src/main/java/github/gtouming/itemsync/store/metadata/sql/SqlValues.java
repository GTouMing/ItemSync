package github.gtouming.itemsync.store.metadata.sql;

import java.time.Instant;

final class SqlValues {

    private SqlValues() {
    }

    static Instant instant(RowCursor cursor, String column) {
        long value = cursor.getLong(column);
        return cursor.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    static <E extends Enum<E>> E enumValue(RowCursor cursor, String column, Class<E> type) {
        String value = cursor.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }
}
