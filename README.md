# Chat-server

Серверная часть чат-приложения (по типу ICQ): регистрация, обмен сообщениями,
статусы доставки (DELIVERED) и прочтения (READ), мгновенная доставка событий
через Server-Sent Events (SSE).

## Стек

- Java 21, **без Spring** — встроенный `com.sun.net.httpserver` (JDK).
- Асинхронная обработка: каждый запрос — свой виртуальный поток
  (`Executors.newVirtualThreadPerTaskExecutor()`).
- SSE — постоянное соединение `GET /api/events`, сервер пушит события
  `message` / `delivered` / `read` без опроса со стороны клиента.
- PostgreSQL 16 — хранение пользователей, токенов и сообщений.
- Gradle — сборка.

## Архитектура

```
src/main/java/chat/
├── Main.java              точка входа: env-конфиг, ожидание БД, сборка компонентов
├── api/                   HTTP-инфраструктура (не знает о бизнес-логике)
│   ├── Json.java          единый ObjectMapper
│   ├── Errors.java        ApiError → HTTP-статус
│   ├── HttpUtil.java      sendJson, разбор query-параметров
│   └── Router.java        регистрация эндпоинтов, обёртка ошибок, SSE-эндпоинт
├── auth/
│   ├── Session.java       record сессии пользователя
│   └── Auth.java          регистрация/вход, PBKDF2, проверка токенов
├── db/
│   └── Db.java            пул соединений (BlockingQueue) + миграция схемы
├── events/
│   └── SseBroker.java     SSE-брокер: подписки, публикация, heartbeat
└── messaging/
    └── ChatHandlers.java  пользователи, сообщения, статусы, публикация событий
```

Зависимости между слоями: `messaging` → `db`, `events`, `api`, `auth`;
`api` ничего не знает о бизнес-логике, кроме контрактов обработчиков.

## Запуск через Docker (рекомендуется)

Требуются Docker и Docker Compose.

```bash
docker compose up --build
```

Сервер: http://localhost:8080. Останов — `docker compose down`
(данные PostgreSQL сохраняются в volume `pgdata`; полная очистка —
`docker compose down -v`).

## Локальный запуск без Docker

Нужны JDK 21 и запущенный PostgreSQL:

```bash
createdb -U postgres chatdb
export DB_URL=jdbc:postgresql://localhost:5432/chatdb
export DB_USER=postgres
export DB_PASSWORD=<пароль postgres>
gradle run
```

Таблицы создаются автоматически при старте (`Db.migrate`).

## Переменные окружения

| Переменная    | По умолчанию                              | Назначение      |
|---------------|-------------------------------------------|-----------------|
| `DB_URL`      | `jdbc:postgresql://localhost:5432/chatdb` | JDBC-строка     |
| `DB_USER`     | `chat`                                    | пользователь БД |
| `DB_PASSWORD` | `chatpass`                                | пароль БД       |
| `PORT`        | `8080`                                    | порт сервера    |

## REST API

Все запросы, кроме register/login, требуют заголовок `Authorization: Bearer <token>`.

| Метод | Путь                       | Описание                                        |
|-------|----------------------------|-------------------------------------------------|
| POST  | `/api/register`            | Регистрация `{username, password}`              |
| POST  | `/api/login`               | Вход `{username, password}` → `{token}`         |
| GET   | `/api/me`                  | Данные текущего пользователя                    |
| GET   | `/api/users`               | Список пользователей                            |
| POST  | `/api/messages`            | Отправка `{to, text}` → `{id, status: "SENT"}`  |
| GET   | `/api/conversation?with=X` | Переписка с X (входящие → DELIVERED)            |
| POST  | `/api/messages/read`       | `{with: X}` — входящие от X → READ              |

## SSE: GET /api/events?token=...

Постоянное соединение `text/event-stream`. Токен передаётся query-параметром,
потому что браузерный `EventSource` не умеет задавать заголовки.

События:

| Тип         | Кому                 | Тело                                     |
|-------------|----------------------|------------------------------------------|
| `message`   | получателю и отправителю | `{from, to, text, status}`          |
| `delivered` | отправителю         | `{count}` — получатель забрал сообщения |
| `read`      | отправителю         | `{count}` — получатель прочитал          |

Сервер отправляет heartbeat (комментарий `: ping`) каждые 30 секунд,
чтобы прокси не закрывали простаивающие соединения. Клиенты с переполненной
очередью событий отключаются.

При развёртывании за nginx отключите буферизацию для этого эндпоинта:
`proxy_buffering off; proxy_read_timeout 3600s;`.

## Пример (curl)

```bash
# Регистрация
curl -X POST localhost:8080/api/register -d '{"username":"alice","password":"secret1"}'
curl -X POST localhost:8080/api/register -d '{"username":"bob","password":"secret2"}'

# Логин alice
TOKEN=$(curl -s -X POST localhost:8080/api/login -d '{"username":"alice","password":"secret1"}' \
  | sed -E 's/.*"token":"([^"]+)".*/\1/')

# Отправка сообщения Bob
curl -X POST localhost:8080/api/messages -H "Authorization: Bearer $TOKEN" \
  -d '{"to":"bob","text":"Привет!"}'

# Прослушивание событий (в отдельном терминале, под токеном Bob)
curl -N "localhost:8080/api/events?token=$BOB_TOKEN"

# Bob забирает переписку — у Alice сработает событие delivered
curl "localhost:8080/api/conversation?with=alice" -H "Authorization: Bearer $BOB_TOKEN"

# Bob помечает прочитанными — у Alice сработает событие read
curl -X POST localhost:8080/api/messages/read -H "Authorization: Bearer $BOB_TOKEN" \
  -d '{"with":"alice"}'
```

## Схема БД

- `users` — пользователи (уникальный логин, PBKDF2-хеш пароля с солью).
- `tokens` — bearer-токены сессий.
- `messages` — сообщения: отправитель, получатель, текст, статус
  (0 = SENT, 1 = DELIVERED, 2 = READ), отметки времени доставки/прочтения.
- Индекс `idx_messages_pair` — быстрая выборка переписки пары пользователей.
