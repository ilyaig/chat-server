package chat.auth;

import chat.api.Errors;
import chat.db.Db;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Регистрация, вход и проверка bearer-токенов.
 *
 * <p>Пароли хранятся в виде PBKDF2-хеша с индивидуальной солью
 * (210 000 итераций, SHA-256, 256 бит ключа) в формате
 * {@code pbkdf2$<base64-соль>$<base64-хеш>}.
 *
 * <p>Сессия — случайный 256-битный токен в base64url; клиент передаёт его
 * в заголовке {@code Authorization: Bearer <token>} либо (для SSE,
 * где заголовки недоступны) в query-параметре {@code ?token=...}.
 */
public final class Auth {

    /** Число итераций PBKDF2 (рекомендация OWASP для PBKDF2-SHA256). */
    private static final int ITERATIONS = 210_000;

    private final Db db;
    private final SecureRandom random = new SecureRandom();

    /**
     * Создаёт сервис аутентификации.
     *
     * @param db пул соединений БД
     */
    public Auth(Db db) {
        this.db = db;
    }

    /**
     * Регистрирует нового пользователя.
     *
     * <p>Валидация: логин — 3–32 символа, пароль — не короче 6 символов.
     * Повторный логин возвращает 409.
     *
     * @param body JSON-тело: {@code {"username": "...", "password": "..."}}
     * @return {@code {"ok": true}}
     * @throws Exception при сбое обращения к БД
     * @throws Errors.ApiError 400 при некорректных данных, 409 при занятом логине
     */
    public Map<String, Object> register(JsonNode body) throws Exception {
        String username = text(body, "username");
        String password = text(body, "password");
        if (username.length() < 3 || username.length() > 32) {
            throw new Errors.ApiError(400, "Имя пользователя должно быть от 3 до 32 символов");
        }
        if (password.length() < 6) {
            throw new Errors.ApiError(400, "Пароль должен быть не короче 6 символов");
        }

        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String stored = "pbkdf2$" + Base64.getEncoder().encodeToString(salt) + "$" + hash(password, salt);

        try {
            db.inTransaction(c -> {
                try (var ps = c.prepareStatement("INSERT INTO users(username, password_hash) VALUES (?,?)")) {
                    ps.setString(1, username);
                    ps.setString(2, stored);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException sqle && "23505".equals(sqle.getSQLState())) {
                throw new Errors.ApiError(409, "Имя пользователя уже занято");
            }
            throw e;
        }
        return Map.of("ok", true);
    }

    /**
     * Аутентифицирует пользователя и выдаёт bearer-токен.
     *
     * @param body JSON-тело: {@code {"username": "...", "password": "..."}}
     * @return {@code {"token": "...", "username": "..."}}
     * @throws Exception при сбое обращения к БД
     * @throws Errors.ApiError 401 при неверном логине или пароле
     */
    public Map<String, Object> login(JsonNode body) throws Exception {
        String username = text(body, "username");
        String password = text(body, "password");

        record Row(long id, String hash) {
        }
        Row row = db.readOnly(c -> {
            try (var ps = c.prepareStatement("SELECT id, password_hash FROM users WHERE username = ?")) {
                ps.setString(1, username);
                var rs = ps.executeQuery();
                if (!rs.next()) {
                    throw new Errors.ApiError(401, "Неверное имя пользователя или пароль");
                }
                return new Row(rs.getLong("id"), rs.getString("password_hash"));
            }
        });

        if (!verify(password, row.hash())) {
            throw new Errors.ApiError(401, "Неверное имя пользователя или пароль");
        }

        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        db.inTransaction(c -> {
            try (var ps = c.prepareStatement("INSERT INTO tokens(token, user_id) VALUES (?,?)")) {
                ps.setString(1, token);
                ps.setLong(2, row.id());
                ps.executeUpdate();
            }
            return null;
        });

        return new HashMap<>(Map.of("token", token, "username", username));
    }

    /**
     * Извлекает сессию из заголовка {@code Authorization}.
     *
     * @param exchange HTTP-обмен
     * @return сессия пользователя
     * @throws Errors.ApiError 401, если заголовка нет или токен недействителен
     */
    public Session requireUser(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new Errors.ApiError(401, "Требуется авторизация");
        }
        return requireToken(header.substring("Bearer ".length()).trim());
    }

    /**
     * Резолвит сессию по готовому токену (для SSE, где заголовки недоступны).
     *
     * @param token bearer-токен или {@code null}
     * @return сессия пользователя
     * @throws Errors.ApiError 401, если токена нет или он недействителен
     */
    public Session requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new Errors.ApiError(401, "Требуется авторизация");
        }
        return findByToken(token);
    }

    /**
     * Загружает сессию из БД по токену.
     *
     * @param token bearer-токен
     * @return сессия пользователя
     */
    private Session findByToken(String token) {
        return db.readOnly(c -> {
            try (var ps = c.prepareStatement(
                    "SELECT u.id, u.username FROM tokens t JOIN users u ON u.id = t.user_id WHERE t.token = ?")) {
                ps.setString(1, token);
                var rs = ps.executeQuery();
                if (!rs.next()) {
                    throw new Errors.ApiError(401, "Недействительный токен");
                }
                return new Session(rs.getLong("id"), rs.getString("username"));
            }
        });
    }

    /**
     * Извлекает обязательное текстовое поле из JSON-объекта.
     *
     * @param body  JSON-тело
     * @param field имя поля
     * @return значение поля
     * @throws Errors.ApiError 400, если поле отсутствует или не строка
     */
    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual()) {
            throw new Errors.ApiError(400, "Поле '" + field + "' обязательно");
        }
        return node.asText();
    }

    /**
     * Вычисляет PBKDF2-хеш пароля и кодирует в Base64.
     *
     * @param password пароль в открытом виде
     * @param salt     соль (16 случайных байт)
     * @return Base64-хеш
     */
    private static String hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            byte[] dk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(dk);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Проверяет пароль против сохранённой записи {@code pbkdf2$соль$хеш}.
     *
     * @param password пароль в открытом виде
     * @param stored   сохранённая запись
     * @return {@code true}, если пароль верный
     */
    private static boolean verify(String password, String stored) {
        String[] parts = stored.split("\\$");
        byte[] salt = Base64.getDecoder().decode(parts[1]);
        return hash(password, salt).equals(parts[2]);
    }
}
