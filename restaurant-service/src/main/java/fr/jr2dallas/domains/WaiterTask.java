package fr.jr2dallas.domains;

import java.time.Instant;
import java.util.Objects;

public class WaiterTask {
    private final WaiterState type;
    private final String clientId;
    private final String seatId;
    private final Instant createdAt;

    public WaiterTask(WaiterState type, String clientId, String seatId, Instant createdAt) {
        this.type = type;
        this.clientId = clientId;
        this.seatId = seatId;
        this.createdAt = createdAt;
    }

    public WaiterState getType() {
        return type;
    }

    public String getClientId() {
        return clientId;
    }

    public String getSeatId() {
        return seatId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static WaiterTask takeOrder(String clientId, String seatId) {
        return new WaiterTask(WaiterState.TAKING_ORDER, clientId, seatId, Instant.now());
    }

    public static WaiterTask deliverDish(String clientId, String seatId) {
        return new WaiterTask(WaiterState.DELIVERING, clientId, seatId, Instant.now());
    }

    @Override
    public String toString() {
        return "WaiterTask{" +
                "type=" + type +
                ", clientId='" + clientId + '\'' +
                ", seatId='" + seatId + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WaiterTask that)) return false;
        return type == that.type &&
                Objects.equals(clientId, that.clientId) &&
                Objects.equals(seatId, that.seatId) &&
                Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, clientId, seatId, createdAt);
    }
}