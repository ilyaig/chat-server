package chat.messaging;

import chat.api.ApiError;
import chat.api.HttpUtil;
import chat.api.Json;
import chat.auth.Session;
import chat.db.Db;
import chat.dto.Communication;
import chat.dto.Message;
import chat.dto.Read;
import chat.dto.User;
import chat.events.SseBroker;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static chat.helper.TimeHelper.iso;

/**
 * Бизнес-обработчики чата: список пользователей, отправка сообщений,
 * переписка и статусы доставки/прочтения.
 *
 * <p>Статусы сообщения: {@code SENT} (0) → {@code DELIVERED} (1) → {@code READ} (2).
 * Переход в DELIVERED происходит, когда получатель забирает переписку
 * ({@code GET /api/conversation}), переход в READ — по явному запросу
 * получателя ({@code POST /api/messages/read}). Каждая смена статуса
 * публикуется в SSE-брокер — отправитель видит её мгновенно.
 */
public final class ChatHandlers {

    private final Db db;
    private final SseBroker events;

    /**
     * Создаёт обработчики.
     *
     * @param db     пул соединений БД
     * @param events SSE-брокер
     */
    public ChatHandlers(Db db, SseBroker events) {
        this.db = db;
        this.events = events;
    }

    /**
     * Список всех пользователей чата.
     *
     * @return массив {@code {username, registeredAt}}, отсортированный по логину
     */
    public List<User> users() {
        return db.readOnly(c -> {
            try (var ps = c.prepareStatement(ChatQuery.GET_USER)) {
                var rs = ps.executeQuery();
                List<User> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new User(
                            rs.getString("username"),
                            iso(rs.getTimestamp("created_at"))
                    ));
                }
                return result;
            }
        });
    }

    /**
     * Отправка сообщения пользователю. Сообщение сразу получает статус SENT;
     * событие {@code message} публикуется получателю и отправителю.
     *
     * @param from сессия отправителя
     * @param body JSON-тело: {@code {"to": "<логин>", "text": "..."}}
     * @return {@code {"id": <номер>, "status": "SENT"}}
     * @throws Exception        при сбое обращения к БД
     * @throws ApiError  400 при неверных полях, 404 если получатель не найден
     */
    public Message send(Session from, JsonNode body) throws Exception {
        JsonNode toNode = body.get("to");
        JsonNode textNode = body.get("text");
        if (toNode == null || !toNode.isTextual() || textNode == null || !textNode.isTextual()) {
            throw new ApiError(400, "Нужны поля 'to' и 'text'");
        }
        String to = toNode.asText();
        String text = textNode.asText();
        if (text.isBlank() || text.length() > 10_000) {
            throw new ApiError(400, "Текст сообщения пустой или слишком длинный (макс. 10000)");
        }

        long toId = userIdByName(to);
        Message message = db.inTransaction(c -> {
            try (var ps = c.prepareStatement(ChatQuery.ADD_MESSAGE)) {
                ps.setLong(1, from.userId());
                ps.setLong(2, toId);
                ps.setString(3, text);
                var rs = ps.executeQuery();
                rs.next();
                return new Message(
                        rs.getLong("id"),
                        rs.getString("sender"),
                        rs.getString("recipient"),
                        rs.getString("text"),
                        "SENT",
                        iso(rs.getTimestamp("created_at")));
            }
        });

        String payload = Json.MAPPER.writeValueAsString(message);
        events.publish(toId, "message", payload);
        events.publish(from.userId(), "message", payload);

        return message;
    }

    /**
     * Переписка текущего пользователя с собеседником. Перед выборкой входящие
     * сообщения со статусом SENT транзакционно помечаются DELIVERED, о чём
     * отправителю публикуется событие {@code delivered}.
     *
     * @param me       сессия текущего пользователя
     * @param exchange HTTP-обмен (читается query-параметр {@code with})
     * @return {@code {"with": "<логин>", "messages": [...]}}
     * @throws Exception        при сбое обращения к БД
     * @throws ApiError  400 если параметр {@code with} не задан,
     *                          404 если собеседник не найден
     */
    public Communication conversation(Session me, HttpExchange exchange) throws Exception {
        String with = HttpUtil.queryValue(exchange.getRequestURI().getQuery(), "with");
        if (with == null || with.isBlank()) {
            throw new ApiError(400, "Нужен параметр ?with=<username>");
        }
        long other = userIdByName(with);

        int delivered = db.inTransaction(c -> {
            try (var ps = c.prepareStatement(ChatQuery.MARK_MESSAGE_AS_DELIVERED)) {
                ps.setLong(1, me.userId());
                ps.setLong(2, other);
                return ps.executeUpdate();
            }
        });
        if (delivered > 0) {
            events.publish(other, "delivered", Json.MAPPER.writeValueAsString(Map.of("count", delivered)));
        }

        List<Message> messages = db.readOnly(c -> {
            try (var ps = c.prepareStatement(ChatQuery.GET_MESSAGES)) {
                ps.setLong(1, me.userId());
                ps.setLong(2, other);
                ps.setLong(3, other);
                ps.setLong(4, me.userId());
                var rs = ps.executeQuery();
                List<Message> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new Message(
                            rs.getLong("id"),
                            rs.getString("sender"),
                            rs.getString("recipient"),
                            rs.getString("text"),
                            statusName(rs.getInt("status")),
                            iso(rs.getTimestamp("created_at"))));
                }
                return list;
            }
        });

        return new Communication(with, messages);
    }

    /**
     * Отмечает входящие сообщения прочитанными и публикует отправителю
     * событие {@code read}.
     *
     * @param me   сессия получателя
     * @param body JSON-тело: {@code {"with": "<логин отправителя>"}}
     * @return {@code {"markedRead": <число обновлённых сообщений>}}
     * @throws Exception        при сбое обращения к БД
     * @throws ApiError  400 если поле {@code with} не задано,
     *                          404 если отправитель не найден
     */
    public Read markRead(Session me, JsonNode body) throws Exception {
        JsonNode withNode = body.get("with");
        if (withNode == null || !withNode.isTextual()) {
            throw new ApiError(400, "Нужно поле 'with'");
        }
        long other = userIdByName(withNode.asText());

        int updated = db.inTransaction(c -> {
            try (var ps = c.prepareStatement(ChatQuery.MARK_MESSAGE_AS_READ)) {
                ps.setLong(1, me.userId());
                ps.setLong(2, other);
                return ps.executeUpdate();
            }
        });
        if (updated > 0) {
            events.publish(other, "read", Json.MAPPER.writeValueAsString(Map.of("count", updated)));
        }
        return new Read(updated);
    }

    /**
     * Находит идентификатор пользователя по логину.
     *
     * @param username логин
     * @return идентификатор из БД
     */
    private long userIdByName(String username) {
        return db.readOnly(c -> {
            try (var ps = c.prepareStatement(ChatQuery.GET_USER_ID_BY_USER_NAME)) {
                ps.setString(1, username);
                var rs = ps.executeQuery();
                if (!rs.next()) {
                    throw new ApiError(404, "Пользователь не найден: " + username);
                }
                return rs.getLong(1);
            }
        });
    }

    /**
     * Преобразует числовой статус в строковый эквивалент API.
     *
     * @param status статус из БД (0, 1, 2)
     * @return {@code "SENT"} / {@code "DELIVERED"} / {@code "READ"}
     */
    private static String statusName(int status) {
        return switch (status) {
            case 0 -> "SENT";
            case 1 -> "DELIVERED";
            default -> "READ";
        };
    }
}
