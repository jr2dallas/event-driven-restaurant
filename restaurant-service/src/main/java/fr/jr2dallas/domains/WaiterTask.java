package fr.jr2dallas.domains;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A job waiting for a waiter: one table, one visit.
 * ORDER = take the orders of every seated client at the table,
 * DELIVERY = carry a tray of ready dishes from the pass to the table.
 *
 * Jobs are rebuilt from the floor state on every scheduler tick, so they can never go stale.
 */
public record WaiterTask(
        Type type,
        String tableId,
        List<String> sessionIds,
        Instant waitingSince,
        Point start,   // where the waiter must go first (table for ORDER, pass for DELIVERY)
        Point target   // where the round ends (service position of the primary client)
) {
    public enum Type { ORDER, DELIVERY }

    /** Higher = more urgent. Deliveries weigh more: serving frees a seat sooner. */
    public double urgency(Instant now, double deliveryWeight) {
        double waited = Math.max(0, Duration.between(waitingSince, now).toMillis()) / 1000.0;
        return type == Type.DELIVERY ? (waited + 1) * deliveryWeight : waited + 1;
    }
}
