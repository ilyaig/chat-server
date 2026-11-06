package chat.api;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Единый для всего приложения {@link ObjectMapper}. */
public final class Json {

    /** Общий экземпляр Jackson: потокобезопасен после настройки. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }
}
