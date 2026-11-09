package chat.dto;

public class User {

    private final String username;
    private final String registeredAt;

    public User(String username, String registeredAt) {
        this.username = username;
        this.registeredAt = registeredAt;
    }

    public String getUsername() {
        return username;
    }

    public String getRegisteredAt() {
        return registeredAt;
    }
}
