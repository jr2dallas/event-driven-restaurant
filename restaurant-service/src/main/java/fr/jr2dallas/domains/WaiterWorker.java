package fr.jr2dallas.domains;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Queue;

public class WaiterWorker {

    private final String id;
    private WaiterState state;
    private String targetSessionId;
    private boolean pendingRemoval = false;

    public WaiterWorker(String id) {
        this.id = id;
        this.state = WaiterState.IDLE;
        this.targetSessionId = null;
    }

    // ── Getters ───────────────────────────────────────────────
    public String getId() { return id; }
    public WaiterState getState() { return state; }
    public String getTargetSessionId() { return targetSessionId; }
    public boolean isIdle() { return state == WaiterState.IDLE; }
    public boolean isPendingRemoval() { return pendingRemoval; }
    public void markForRemoval() { this.pendingRemoval = true; }

    // ── Transitions triggered by the scheduler ────────────────

    /** Assigns an order task — the frontend will animate the walk toward the client */
    public void assignOrderTask(String sessionId) {
        this.targetSessionId = sessionId;
        this.state = WaiterState.WALKING_TO_CLIENT;
    }

    /** Assigns a delivery task — the frontend will animate the walk from kitchen to client */
    public void assignDeliveryTask(String sessionId) {
        this.targetSessionId = sessionId;
        this.state = WaiterState.DELIVERING;
    }

    // ── Transitions triggered by the frontend (via WaiterScheduler) ──

    /**
     * Frontend notifies that the waiter has arrived at the client's table.
     * WALKING_TO_CLIENT → TAKING_ORDER
     */
    public void arrivedAtClient() {
        if (state == WaiterState.WALKING_TO_CLIENT) {
            state = WaiterState.TAKING_ORDER;
        }
    }

    /**
     * Frontend notifies that the order has been taken.
     * TAKING_ORDER → WALKING_TO_KITCHEN
     */
    public void orderTaken() {
        if (state == WaiterState.TAKING_ORDER) {
            state = WaiterState.WALKING_TO_KITCHEN;
        }
    }

    /**
     * Kafka notifies that the kitchen has finished the dish.
     * WALKING_TO_KITCHEN → DELIVERING (with updated targetSessionId)
     */
    public void dishReady(String sessionId) {
        this.targetSessionId = sessionId;
        this.state = WaiterState.DELIVERING;
    }

    /**
     * Frontend notifies that the dish has been delivered.
     * DELIVERING → IDLE
     */
    public void dishDelivered() {
        this.targetSessionId = null;
        this.state = WaiterState.IDLE;
    }

    public void reset() {
        this.state = WaiterState.IDLE;
        this.targetSessionId = null;
    }

    // ── Task selection (scheduler tick) ──────────────────────

    public Optional<WaiterTask> pickNextTask(
            Queue<WaiterTask> orderTasks,
            Queue<WaiterTask> deliveryTasks,
            Duration staleDishThreshold
    ) {
        if (!isIdle() || pendingRemoval) return Optional.empty();

        Instant now = Instant.now();

        // Priority: urgent deliveries (dish getting cold)
        Optional<WaiterTask> urgent = deliveryTasks.stream()
                .filter(t -> t.getCreatedAt().isBefore(now.minus(staleDishThreshold)))
                .findFirst();
        if (urgent.isPresent()) {
            deliveryTasks.remove(urgent.get());
            assignDeliveryTask(urgent.get().getClientId());
            return urgent;
        }

        // Next: order taking
        if (!orderTasks.isEmpty()) {
            WaiterTask task = orderTasks.poll();
            assignOrderTask(task.getClientId());
            return Optional.of(task);
        }

        // Finally: normal deliveries
        if (!deliveryTasks.isEmpty()) {
            WaiterTask task = deliveryTasks.poll();
            assignDeliveryTask(task.getClientId());
            return Optional.of(task);
        }

        return Optional.empty();
    }
}
