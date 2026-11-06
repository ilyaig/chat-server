package chat.events;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Брокер SSE-событий: рассылка уведомлений подписанным клиентам.
 *
 * <p>Каждый подписчик — виртуальный поток, читающий события из ограниченной
 * очереди и пишущий их в поток клиента. Клиенты с переполненной очередью
 * (слишком медленные) отключаются. Каждые {@link #HEARTBEAT_SECONDS} секунд
 * брокер отправляет heartbeat-комментарий, чтобы прокси не закрывали
 * простаивающие соединения.
 *
 * <p>Типы событий:
 * <ul>
 *   <li>{@code message} — новое сообщение;</li>
 *   <li>{@code delivered} — сообщения помечены DELIVERED;</li>
 *   <li>{@code read} — сообщения помечены READ.</li>
 * </ul>
 */
public final class SseBroker implements AutoCloseable {

    /** Ёмкость очереди событий одного подписчика. */
    private static final int QUEUE_CAPACITY = 256;

    /** Интервал heartbeat, секунды. */
    private static final long HEARTBEAT_SECONDS = 30;

    /** Интервал проверки флага закрытия в потоке-писателе, секунды. */
    private static final long POLL_TIMEOUT_SECONDS = 1;

    /**
     * Писатель события в исходящий поток клиента.
     */
    public interface Writer {
        /**
         * Пишет событие в поток клиента.
         *
         * @param event готовая SSE-запись (event + data + пустая строка)
         * @throws IOException если клиент отключился
         */
        void write(String event) throws IOException;
    }

    /** Подписка одного клиента: очередь событий и флаг закрытия. */
    private static final class Subscriber {
        final long userId;
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        final AtomicBoolean closed = new AtomicBoolean(false);

        Subscriber(long userId) {
            this.userId = userId;
        }
    }

    /** Подписчики по идентификатору пользователя. */
    private final Map<Long, List<Subscriber>> subscribers = new ConcurrentHashMap<>();

    /** Планировщик heartbeat. */
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    /** Создаёт брокер и запускает периодический heartbeat. */
    public SseBroker() {
        heartbeat.scheduleAtFixedRate(this::sendHeartbeats,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Подписывает клиента на события и запускает поток-писатель.
     *
     * @param userId идентификатор пользователя
     * @param writer писатель, отправляющий данные клиенту
     * @return ручка отписки; вызов закрывает подписку
     */
    public Runnable subscribe(long userId, Writer writer) {
        Subscriber sub = new Subscriber(userId);
        subscribers.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(sub);
        Thread pump = Thread.ofVirtual().start(() -> pump(sub, writer));
        return () -> {
            sub.closed.set(true);
            pump.interrupt();
        };
    }

    /**
     * Цикл писателя: забирает события из очереди и пишет их клиенту,
     * пока подписка открыта.
     *
     * @param sub    подписка
     * @param writer писатель клиента
     */
    private void pump(Subscriber sub, Writer writer) {
        try {
            while (!sub.closed.get()) {
                String event = sub.queue.poll(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (event == null) {
                    continue;
                }
                try {
                    writer.write(event);
                } catch (IOException e) {
                    break; // клиент отключился
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            remove(sub);
        }
    }

    /**
     * Публикует событие всем активным подпискам пользователя.
     *
     * @param userId идентификатор пользователя-получателя события
     * @param type   тип события (message / delivered / read)
     * @param json   JSON-тело события (однострочное)
     */
    public void publish(long userId, String type, String json) {
        List<Subscriber> list = subscribers.get(userId);
        if (list == null) {
            return;
        }
        String event = "event: " + type + "\ndata: " + json.replace("\n", " ") + "\n\n";
        for (Subscriber sub : list) {
            if (sub.closed.get()) {
                continue;
            }
            if (!sub.queue.offer(event)) {
                // очередь переполнена — клиент слишком медленный, отключаем
                sub.closed.set(true);
                remove(sub);
            }
        }
    }

    /** Отправляет heartbeat-комментарий всем активным подписчикам. */
    private void sendHeartbeats() {
        for (List<Subscriber> list : subscribers.values()) {
            for (Subscriber sub : list) {
                if (!sub.closed.get()) {
                    sub.queue.offer(": ping\n\n");
                }
            }
        }
    }

    /** Убирает подписку из реестра. */
    private void remove(Subscriber sub) {
        List<Subscriber> list = subscribers.get(sub.userId);
        if (list != null) {
            list.remove(sub);
        }
    }

    /** Останавливает heartbeat и все подписки. */
    @Override
    public void close() {
        heartbeat.shutdownNow();
        for (List<Subscriber> list : subscribers.values()) {
            for (Subscriber sub : list) {
                sub.closed.set(true);
            }
        }
        subscribers.clear();
    }
}
