package chat.auth;

public class AuthQuery {

    /**
     * Добавить пользователя
     */
    public static final String ADD_USER = "INSERT INTO users(username, password_hash) VALUES (?,?) RETURNING *";

    /**
     * Получить пользователя по логину
     */
    public static final String GET_USER_BY_LOGIN = "SELECT id, password_hash FROM users WHERE username = ?";

    /**
     * Получить пользователя по токену
     */
    public static final String GET_USER_BY_TOKEN = "SELECT u.id, u.username FROM tokens t JOIN users u ON u.id = t.user_id WHERE t.token = ?";

    /**
     * Добавить токен
     */
    public static final String ADD_TOKEN = "INSERT INTO tokens(token, user_id) VALUES (?,?)";
}
