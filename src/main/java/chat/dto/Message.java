package chat.dto;

public record Message(
        long id,
        String from,
        String to,
        String text,
        String status,
        String createdAt
) {
}
