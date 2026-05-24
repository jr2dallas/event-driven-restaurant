package fr.jr2dallas.domains;

public class Seat {
    private final String id;
    private String clientId; // null si libre

    public Seat(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public boolean isFree() {
        return clientId == null;
    }

    public boolean isOccupied() {
        return !isFree();
    }

    public void occupy(String clientId) {
        if (!isFree()) {
            throw new IllegalStateException("Seat " + id + " already occupied by " + this.clientId);
        }
        this.clientId = clientId;
    }

    public void free() {
        this.clientId = null;
    }

    public void freeIfHeldBy(String clientId) {
        if (clientId != null && clientId.equals(this.clientId)) {
            this.clientId = null;
        }
    }

}