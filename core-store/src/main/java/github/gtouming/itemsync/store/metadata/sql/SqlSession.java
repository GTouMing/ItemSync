package github.gtouming.itemsync.store.metadata.sql;

import java.util.List;
import java.util.Optional;

public interface SqlSession {

    void execute(String sql);

    int update(String sql, Object... arguments);

    <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments);

    void queryEach(String sql, RowConsumer consumer, Object... arguments);

    default <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... arguments) {
        List<T> rows = query(sql, mapper, arguments);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    void begin();

    void commit();

    void rollback();

    boolean inTransaction();

    default void runAtomically(Runnable work) {
        if (inTransaction()) {
            work.run();
            return;
        }
        begin();
        try {
            work.run();
            commit();
        } catch (RuntimeException e) {
            rollback();
            throw e;
        }
    }
}
