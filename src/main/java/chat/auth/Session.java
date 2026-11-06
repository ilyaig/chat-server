package chat.auth;

/** Данные аутентифицированного пользователя. */
public record Session(long userId, String username) {
}
