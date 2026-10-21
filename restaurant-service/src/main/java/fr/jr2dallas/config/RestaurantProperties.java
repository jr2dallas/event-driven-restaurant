package fr.jr2dallas.config;

import fr.jr2dallas.domains.Point;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "restaurant")
public record RestaurantProperties(
        String defaultDish,
        SchedulerProperties scheduler,
        DispatchProperties dispatch,
        TimeoutProperties timeouts,
        FloorProperties floor,
        AutoscalingProperties autoscaling
) {
    public record SchedulerProperties(
            long eatingTimeoutSeconds,
            long leavingCleanupTimeoutSeconds,
            // An order still IN_KITCHEN after this long, while the kitchens have no backlog,
            // is considered lost and re-sent (same orderId)
            long rescueThresholdSeconds
    ) {}

    public record DispatchProperties(
            int trayCapacity,              // plates a waiter carries in one delivery round
            double deliveryPriorityWeight, // >1 = serving beats taking new orders at equal wait
            long groupHoldSeconds,         // hold a table's first ready dish to serve the table together
            long passWaitSeconds,          // after dropping a slip, wait this long at the pass for the dishes
            double sectorPenaltyPx         // extra "distance" for a table outside the waiter's sector
    ) {}

    /** Safety nets when the front stops reporting (hidden tab, closed browser…). */
    public record TimeoutProperties(
            long walkSeconds,
            long takingOrderSeconds,
            long walkToSeatSeconds
    ) {}

    public record FloorProperties(
            Point pass,             // where waiters drop slips and collect dishes
            List<Point> waiterPosts // where idle waiters wait (one sector per post)
    ) {}

    public record AutoscalingProperties(
            boolean waiters,
            boolean kitchens,
            int minWaiters,
            int maxWaiters,
            int minKitchens,
            int maxKitchens
    ) {}
}
