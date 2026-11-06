package chat.api;

/**
 * Служебные типы для обработки ошибок REST API.
 */
public final class Errors {

    private Errors() {
    }

    /**
     * Ошибка уровня API, которую безопасно показывать клиенту.
     * Перехватывается в {@link Router} и превращается в HTTP-ответ
     * вида {@code {"error": "..."}} с кодом {@link #status}.
     */
    public static final class ApiError extends RuntimeException {

        /** HTTP-статус ответа (400, 401, 404, 409 и т. д.). */
        public final int status;

        /**
         * Создаёт ошибку API.
         *
         * @param status  HTTP-статус
         * @param message человекочитаемое сообщение для клиента
         */
        public ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
