package chat.helper;

import java.io.IOException;
import java.io.InputStream;

public class FileHelper {

    /**
     * Прочитать содержимое ресурса в строку
     * @param resource имя файла ресурса
     */
    public static String readResource(String resource) throws IOException {
        try(InputStream io = FileHelper.class.getClassLoader().getResourceAsStream(resource)) {
            if (io != null) {
                return new String(io.readAllBytes());
            }
        }
        throw new RuntimeException("Не найден исходный скрипт миграции");
    }
}
