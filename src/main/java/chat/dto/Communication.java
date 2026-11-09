package chat.dto;

import java.util.List;

public record Communication(
        String with,
        List<Message> messages
) {
}
