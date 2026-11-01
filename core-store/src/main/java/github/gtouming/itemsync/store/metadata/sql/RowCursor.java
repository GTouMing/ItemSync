package github.gtouming.itemsync.store.metadata.sql;

public interface RowCursor {

    boolean next();

    String getString(String column);

    long getLong(String column);

    int getInt(String column);

    boolean wasNull();
}
