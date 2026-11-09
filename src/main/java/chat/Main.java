package chat;

import chat.api.Router;
import chat.auth.Auth;
import chat.db.Db;
import chat.events.SseBroker;
import chat.messaging.ChatHandlers;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Точка входа чат-сервера.
 *
 * <p>Порядок запуска: чтение конфигурации из переменных окружения →
 * ожидание готовности PostgreSQL (до 30 попыток с интервалом 2 секунды —
 * важно при совместном старте в docker-compose) → инициализация пула
 * соединений и миграция схемы → сборка компонентов → регистрация
 * эндпоинтов через {@link Router} → старт сервера.
 *
 * <p>Каждый HTTP-запрос обрабатывается в собственном виртуальном потоке,
 * поэтому блокирующие операции (БД, SSE-поток) не мешают другим запросам.
 */
public final class Main {

    private Main() {
    }

    /**
     * Запускает сервер.
     *
     * @param args аргументы командной строки (не используются)
     * @throws Exception если не удалось подключиться к БД или запустить сервер
     */
    public static void main(String[] args) throws Exception {
        String url = env("DB_URL", "jdbc:postgresql://localhost:5432/chat");
        String user = env("DB_USER", "postgres");
        String password = env("DB_PASSWORD", "111");
        int port = Integer.parseInt(env("PORT", "8080"));

        awaitDatabase(url, user, password);

        Db db = new Db(url, user, password, 16);
        db.migrate();
        SseBroker events = new SseBroker();
        Auth auth = new Auth(db, events);
        ChatHandlers handlers = new ChatHandlers(db, events);
        Router router = new Router(auth);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        router.register(server, handlers, events);
        server.start();
        System.out.println("Сервер запущен на порту " + port);

        // Корректное завершение: останавливаем сервер, брокер и пул соединений
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            executor.shutdown();
            try {
                events.close();
            } catch (Exception ignored) {
            }
            db.close();
            System.out.println("Сервер остановлен");
        }));
    }

    /**
     * Ждёт готовности БД: до 30 попыток подключения с интервалом 2 секунды.
     *
     * @param url      JDBC-строка
     * @param user     пользователь БД
     * @param password пароль БД
     * @throws IllegalStateException если подключиться так и не удалось
     */
    private static void awaitDatabase(String url, String user, String password) {
        for (int attempt = 1; attempt <= 30; attempt++) {
            try (Connection probe = DriverManager.getConnection(url, user, password)) {
                return;
            } catch (SQLException e) {
                System.out.println("БД ещё не готова, попытка " + attempt + "/30: " + e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Ожидание БД прервано", ie);
                }
            }
        }
        throw new IllegalStateException("Не удалось подключиться к PostgreSQL: " + url);
    }

    /**
     * Читает переменную окружения со значением по умолчанию.
     *
     * @param name имя переменной
     * @param def  значение по умолчанию, если переменная не задана или пустая
     * @return значение переменной либо {@code def}
     */
    private static String env(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : v;
    }
}
