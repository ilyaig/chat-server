package chat.messaging;

public class ChatQuery {

    /**
     * Описание сообщения чата
     */
    private static final String MESSAGE = """
            SELECT m.id, su.username AS sender, ru.username AS recipient,
                   m.text, m.status, m.created_at
            FROM %s m
            JOIN users su ON su.id = m.sender_id
            JOIN users ru ON ru.id = m.recipient_id
            """;

    /**
     * Получение списка пользователей
     */
    public static final String GET_USER = "SELECT username, created_at FROM users ORDER BY username";

    /**
     * Пометить сообщения, как доставленные
     */
    public static final String MARK_MESSAGE_AS_DELIVERED = "UPDATE messages SET status = 1, delivered_at = now() "
            + "WHERE recipient_id = ? AND sender_id = ? AND status = 0";

    /**
     * Пометить сообщения, как прочитанные
     */
    public static final String MARK_MESSAGE_AS_READ = "UPDATE messages SET status = 2, read_at = now() "
            + "WHERE recipient_id = ? AND sender_id = ? AND status < 2";

    /**
     * Добавить сообщение
     */
    public static final String ADD_MESSAGE = """
            with message as (INSERT INTO messages(sender_id, recipient_id, text) VALUES (?,?,?) RETURNING *)
            """ + String.format(MESSAGE, "message");

    /**
     * Получить сообщения
     */
    public static final String GET_MESSAGES = String.format(MESSAGE, "messages") + """
                    WHERE (m.sender_id = ? AND m.recipient_id = ?)
                       OR (m.sender_id = ? AND m.recipient_id = ?)
                    ORDER BY m.id""";

    /**
     * Получить идентификатор пользователя по имени
     */
    public static final String GET_USER_ID_BY_USER_NAME = "SELECT id FROM users WHERE username = ?";
}
