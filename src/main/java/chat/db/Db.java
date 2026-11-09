package chat.db;

import chat.helper.FileHelper;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Простой пул JDBC-соединений к PostgreSQL с миграцией схемы.
 *
 * <p>Пул реализован на блокирующей очереди: ожидание свободного соединения
 * паркует виртуальный поток запроса, не занимая платформенный поток.
 * Все операции выполняются через {@link #readOnly} или {@link #inTransaction} —
 * соединение автоматически возвращается в пул.
 *
 * <p>{@link #migrate()} идемпотентно создаёт таблицы {@code users},
 * {@code tokens}, {@code messages} и индекс по парам переписывающихся
 * пользователей.
 */
public final class Db implements AutoCloseable {

    private final BlockingQueue<Connection> pool;

    /**
     * Создаёт пул соединений заданного размера.
     *
     * @param url      JDBC-строка подключения
     * @param user     пользователь БД
     * @param password пароль БД
     * @param poolSize количество соединений (рекомендуется ~ ядра * 2)
     * @throws SQLException если ни одно соединение создать не удалось
     */
    public Db(String url, String user, String password, int poolSize) throws SQLException {
        pool = new LinkedBlockingQueue<>();
        for (int i = 0; i < poolSize; i++) {
            pool.add(DriverManager.getConnection(url, user, password));
        }
    }

    /**
     * Функциональный интерфейс операции над соединением.
     *
     * @param <T> тип результата
     */
    public interface Sql<T> {
        /**
         * Выполняет работу с соединением.
         *
         * @param c активное JDBC-соединение
         * @return результат операции
         * @throws Exception любая ошибка операции
         */
        T apply(Connection c) throws Exception;
    }

    /**
     * Выполняет операцию в транзакции: auto-commit выключается, при успехе —
     * commit, при ошибке — rollback. Соединение всегда возвращается в пул.
     *
     * @param work операция над БД
     * @param <T>  тип результата
     * @return результат операции
     * @throws RuntimeException исходная ошибка либо её обёртка
     */
    public <T> T inTransaction(Sql<T> work) {
        Connection c = take();
        try {
            c.setAutoCommit(false);
            T result = work.apply(c);
            c.commit();
            return result;
        } catch (Exception e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
            }
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            try {
                c.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
            pool.add(c);
        }
    }

    /**
     * Выполняет операцию без явной транзакции (auto-commit включён).
     *
     * @param work операция над БД
     * @param <T>  тип результата
     * @return результат операции
     * @throws RuntimeException исходная ошибка либо её обёртка
     */
    public <T> T readOnly(Sql<T> work) {
        Connection c = take();
        try {
            return work.apply(c);
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            pool.add(c);
        }
    }

    /**
     * Берёт свободное соединение из пула (блокируется, пока его нет).
     *
     * @return свободное JDBC-соединение
     * @throws IllegalStateException если поток прерван в ожидании
     */
    private Connection take() {
        try {
            return pool.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Идемпотентная миграция схемы БД.
     */
    public void migrate() throws IOException {
        String initMigrate = FileHelper.readResource("migrate/init.sql");
        readOnly(c -> {
            try (Statement st = c.createStatement()) {
                st.execute(initMigrate);
            }
            return null;
        });
    }

    /** Закрывает все соединения пула. */
    @Override
    public void close() {
        for (Connection c : pool) {
            try {
                c.close();
            } catch (SQLException ignored) {
            }
        }
    }
}
