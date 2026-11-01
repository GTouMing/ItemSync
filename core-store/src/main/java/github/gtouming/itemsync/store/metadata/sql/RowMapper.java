package github.gtouming.itemsync.store.metadata.sql;

@FunctionalInterface
public interface RowMapper<T> {

    T map(RowCursor cursor);
}
