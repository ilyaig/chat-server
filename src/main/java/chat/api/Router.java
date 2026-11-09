package chat.api;

import chat.auth.Auth;
import chat.auth.Session;
import chat.events.SseBroker;
import chat.messaging.ChatHandlers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Регистрация REST-эндпоинтов и SSE-потока, единая обработка ошибок.
 *
 * <p>Каждый обработчик обёрнут: проверка метода → авторизация (если нужна) →
 * бизнес-логика → сериализация ответа. {@link ApiError} превращается
 * в HTTP-ответ с соответствующим статусом, прочие исключения — в 500
 * с записью стека в лог. Соединение закрывается в любом случае.
 */
public final class Router {

    /** Обработчик, не требующий сессии. */
    interface Endpoint {
        void handle(HttpExchange ex) throws Exception;
    }

    /** Обработчик, получающий сессию авторизованного пользователя. */
    interface AuthEndpoint {
        void handle(Session session, HttpExchange ex) throws Exception;
    }

    private final Auth auth;

    /**
     * Создаёт роутер.
     *
     * @param auth сервис аутентификации
     */
    public Router(Auth auth) {
        this.auth = auth;
    }

    /**
     * Регистрирует все эндпоинты приложения.
     *
     * @param server   HTTP-сервер
     * @param handlers бизнес-обработчики чата
     * @param events   SSE-брокер
     */
    public void register(HttpServer server, ChatHandlers handlers, SseBroker events) {
        server.createContext("/api/register", open("POST",
                ex -> HttpUtil.sendJson(ex, 200, auth.register(body(ex)))));
        server.createContext("/api/login", open("POST",
                ex -> HttpUtil.sendJson(ex, 200, auth.login(body(ex)))));
        server.createContext("/api/me", secured("GET",
                (s, ex) -> HttpUtil.sendJson(ex, 200,
                        Map.of("userId", s.userId(), "username", s.username()))));
        server.createContext("/api/users", secured("GET",
                (s, ex) -> HttpUtil.sendJson(ex, 200, handlers.users())));
        server.createContext("/api/messages", secured("POST",
                (s, ex) -> HttpUtil.sendJson(ex, 200, handlers.send(s, body(ex)))));
        server.createContext("/api/messages/read", secured("POST",
                (s, ex) -> HttpUtil.sendJson(ex, 200, handlers.markRead(s, body(ex)))));
        server.createContext("/api/conversation", secured("GET",
                (s, ex) -> HttpUtil.sendJson(ex, 200, handlers.conversation(s, ex))));
        server.createContext("/api/events", exchange -> handleEvents(exchange, events));
    }

    /**
     * Читает и парсит JSON-тело запроса.
     *
     * @param ex HTTP-обмен
     * @return JSON-дерево запроса
     * @throws IOException если чтение потока не удалось
     */
    private static com.fasterxml.jackson.databind.JsonNode body(HttpExchange ex) throws IOException {
        return Json.MAPPER.readTree(ex.getRequestBody());
    }

    /**
     * Оборачивает обработчик единой логикой ошибок и закрытия соединения.
     *
     * @param action обработчик запроса
     * @return готовый HttpHandler
     */
    private HttpHandler open(String method, Endpoint action) {
        return exchange -> {
            try {
                if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
                    HttpUtil.sendJson(exchange, 405, Map.of("error", "Метод не поддерживается"));
                } else {
                    action.handle(exchange);
                }
            } catch (ApiError e) {
                HttpUtil.sendJson(exchange, e.status, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                HttpUtil.sendJson(exchange, 500, Map.of("error", "Внутренняя ошибка сервера"));
            } finally {
                exchange.close();
            }
        };
    }

    /**
     * Как {@link #open}, но перед выполнением резолвит сессию по заголовку
     * {@code Authorization: Bearer <token>}.
     *
     * @param method допустимый HTTP-метод
     * @param action обработчик, получающий сессию
     * @return готовый HttpHandler
     */
    private HttpHandler secured(String method, AuthEndpoint action) {
        return open(method, ex -> action.handle(auth.requireUser(ex), ex));
    }

    /**
     * SSE-эндпоинт {@code GET /api/events?token=...}: держит соединение
     * открытым и пушит события брокера, пока клиент не отключится.
     *
     * <p>Токен передаётся query-параметром, потому что браузерный
     * {@code EventSource} не умеет задавать заголовки. Обработчик выполняется
     * в виртуальном потоке, поэтому блокирующее чтение входного потока
     * (ожидание разрыва соединения) не занимает платформенный поток.
     *
     * @param exchange HTTP-обмен
     * @param events   SSE-брокер
     * @throws IOException если запись в поток не удалась
     */
    private void handleEvents(HttpExchange exchange, SseBroker events) throws IOException {
        try {
            String token = HttpUtil.queryValue(exchange.getRequestURI().getQuery(), "token");
            Session session = auth.requireToken(token);

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0); // чанкованный бесконечный ответ

            Writer writer = new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8);
            writer.write(": connected\n\n");
            writer.flush();

            Runnable unsubscribe = events.subscribe(session.userId(), event -> {
                writer.write(event);
                writer.flush();
            });

            // Блокируемся до закрытия соединения клиентом
            try {
                exchange.getRequestBody().readAllBytes();
            } catch (IOException ignored) {
                // клиент отключился — это нормальный сценарий
            }
            unsubscribe.run();
            exchange.close();
        } catch (ApiError e) {
            HttpUtil.sendJson(exchange, e.status, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            exchange.close();
        }
    }
}
