package chat.helper;

import java.sql.Timestamp;

public class TimeHelper {

    /**
     * Форматирует метку времени в строку ISO-8601.
     *
     * @param ts метка времени или {@code null}
     * @return ISO-строка либо {@code null}
     */
    public static String iso(Timestamp ts) {
        return ts == null ? null : ts.toString();
    }
}
