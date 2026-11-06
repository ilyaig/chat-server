package chat.api;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Утилиты HTTP-слоя: отправка JSON-ответов и разбор query-параметров. */
public final class HttpUtil {

    private HttpUtil() {
    }

    /**
     * Отправляет клиенту JSON-ответ.
     *
     * @param ex     HTTP-обмен
     * @param status HTTP-статус (200, 400, 401, ...)
     * @param body   объект для сериализации; {@code null} превращается в {@code {}}
     * @throws IOException если запись тела не удалась
     */
    public static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(body == null ? Map.of() : body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Разбирает query-строку URI в карту параметров (с URL-декодированием).
     *
     * @param query query-строка, может быть {@code null}
     * @return карта параметров (пустая, если query отсутствует)
     */
    public static Map<String, String> queryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                params.put(URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    /**
     * Возвращает значение одного query-параметра.
     *
     * @param query query-строка
     * @param name  имя параметра
     * @return значение или {@code null}
     */
    public static String queryValue(String query, String name) {
        return queryParams(query).get(name);
    }
}
