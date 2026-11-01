package github.gtouming.itemsync.store.metadata.sql;

@FunctionalInterface
public interface RowConsumer {

    void accept(RowCursor cursor);
}
