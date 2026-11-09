package chat.auth;

import chat.dto.User;

public class AuthUser extends User {

    private final long id;
    private final String hash;

    public AuthUser(long id, String hash) {
        super(null, null);
        this.id = id;
        this.hash = hash;
    }

    public AuthUser(long id, String hash, String username, String registeredAt) {
        super(username, registeredAt);
        this.id = id;
        this.hash = hash;
    }

    public long getId() {
        return this.id;
    }

    public String getHash() {
        return this.hash;
    }

    public User getUser() {
        return new User(getUsername(), getRegisteredAt());
    }
}
