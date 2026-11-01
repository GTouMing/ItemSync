package github.gtouming.itemsync.android;

import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import github.gtouming.itemsync.store.metadata.MetadataException;
import github.gtouming.itemsync.store.metadata.sql.RowConsumer;
import github.gtouming.itemsync.store.metadata.sql.RowMapper;
import github.gtouming.itemsync.store.metadata.sql.SqlSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class AndroidSqlSession implements SqlSession {

    private final SQLiteDatabase database;
    private boolean inTransaction;

    AndroidSqlSession(SQLiteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void execute(String sql) {
        try {
            database.execSQL(sql);
        } catch (SQLException e) {
            throw fail("execute", e);
        }
    }

    @Override
    public int update(String sql, Object... arguments) {
        try (SQLiteStatement statement = database.compileStatement(sql)) {
            bind(statement, arguments);
            return statement.executeUpdateDelete();
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
        Cursor cursor;
        try {
            cursor = database.rawQuery(sql, stringArguments(arguments));
        } catch (SQLException e) {
            throw fail("query", e);
        }
        try (Cursor closeable = cursor) {
            AndroidRowCursor row = new AndroidRowCursor(closeable);
            while (row.next()) {
                consumer.accept(row);
            }
        }
    }

    @Override
    public void begin() {
        database.beginTransaction();
        inTransaction = true;
    }

    @Override
    public void commit() {
        try {
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
            inTransaction = false;
        }
    }

    @Override
    public void rollback() {
        try {
            database.endTransaction();
        } finally {
            inTransaction = false;
        }
    }

    @Override
    public boolean inTransaction() {
        return inTransaction;
    }

    private static void bind(SQLiteStatement statement, Object... arguments) {
        for (int i = 0; i < arguments.length; i++) {
            Object value = arguments[i];
            int index = i + 1;
            if (value == null) {
                statement.bindNull(index);
            } else if (value instanceof String text) {
                statement.bindString(index, text);
            } else if (value instanceof Long number) {
                statement.bindLong(index, number);
            } else if (value instanceof Integer number) {
                statement.bindLong(index, number);
            } else if (value instanceof Boolean flag) {
                statement.bindLong(index, flag ? 1 : 0);
            } else if (value instanceof Instant instant) {
                statement.bindLong(index, instant.toEpochMilli());
            } else {
                throw new IllegalArgumentException("unsupported sql argument type: " + value.getClass().getName());
            }
        }
    }

    private static String[] stringArguments(Object... arguments) {
        String[] values = new String[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            Object value = arguments[i];
            if (value == null) {
                values[i] = null;
            } else if (value instanceof Instant instant) {
                values[i] = Long.toString(instant.toEpochMilli());
            } else if (value instanceof Boolean flag) {
                values[i] = flag ? "1" : "0";
            } else {
                values[i] = String.valueOf(value);
            }
        }
        return values;
    }

    private static MetadataException fail(String operation, SQLException cause) {
        return new MetadataException(operation + " failed", cause);
    }
}
