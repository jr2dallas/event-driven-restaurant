package fr.jr2dallas.services;

import fr.jr2dallas.config.RestaurantProperties;
import fr.jr2dallas.domains.ClientSession;
import fr.jr2dallas.domains.SessionState;
import fr.jr2dallas.domains.WaiterState;
import fr.jr2dallas.domains.WaiterTask;
import fr.jr2dallas.domains.WaiterWorker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class WaiterScheduler {

    private static final Logger log = LoggerFactory.getLogger(WaiterScheduler.class);

    private final RestaurantService    restaurantService;
    private final RestaurantProperties props;

    private final List<WaiterWorker> waiters = new CopyOnWriteArrayList<>(List.of(
            new WaiterWorker("w1"),
            new WaiterWorker("w2")
    ));
    private final AtomicInteger waiterIdCounter = new AtomicInteger(3);

    public WaiterScheduler(RestaurantService restaurantService,
                           RestaurantProperties props,
                           MeterRegistry meterRegistry) {
        this.restaurantService = restaurantService;
        this.props = props;
        Gauge.builder("restaurant.waiters.active", waiters, List::size)
                .description("Number of active waiter workers")
                .register(meterRegistry);
    }

    public List<WaiterWorker> getWaiters() { return waiters; }

    public int getWaiterCount() { return waiters.size(); }

    public synchronized void setWaiterCount(int count) {
        int current = waiters.size();
        if (count > current) {
            for (int i = current + 1; i <= count; i++) {
                waiters.add(new WaiterWorker("w" + i));
            }
        } else if (count < current) {
            // Remove from the end — explicit while loop avoids index-shift confusion
            while (waiters.size() > count) {
                waiters.remove(waiters.size() - 1);
            }
        }
    }

    public synchronized void hireWaiter() {
        waiters.add(new WaiterWorker("w" + waiterIdCounter.getAndIncrement()));
    }

    public synchronized void fireWaiter() {
        // Remove an idle waiter immediately if one exists
        boolean removed = waiters.stream()
                .filter(w -> w.isIdle() && !w.isPendingRemoval())
                .findFirst()
                .map(waiters::remove)
                .orElse(false);
        if (removed) return;
        // Otherwise mark the first non-pending busy waiter for graceful removal
        waiters.stream()
                .filter(w -> !w.isPendingRemoval())
                .findFirst()
                .ifPresent(WaiterWorker::markForRemoval);
    }

    // ── Main tick ─────────────────────────────────────────────
    // fixedDelayString reads directly from the environment — @ConfigurationProperties beans
    // are not available at annotation processing time
    @Scheduled(fixedDelayString = "${restaurant.scheduler.tick-delay-ms:200}")
    public void tick() {
        // Seat the next queued client if a chair is available
        restaurantService.trySeatNextClient();

        // Assign tasks to idle waiters — validate client state immediately to avoid stale assignments
        for (WaiterWorker waiter : waiters) {
            waiter.pickNextTask(
                    restaurantService.getOrderTasks(),
                    restaurantService.getDeliveryTasks(),
                    Duration.ofSeconds(props.scheduler().staleDishThresholdSeconds())
            ).ifPresent(task -> {
                ClientSession session = restaurantService.getSession(task.getClientId());
                boolean valid = switch (task.getType()) {
                    case TAKING_ORDER -> session != null && session.getState() == SessionState.SEATED;
                    case DELIVERING   -> session != null && session.getState() == SessionState.WAITING_ORDER;
                    default           -> false;
                };
                if (!valid) waiter.reset();
            });
        }

        // Reset waiters whose target is in an incompatible state (client left, already served, etc.)
        for (WaiterWorker waiter : waiters) {
            if (waiter.isIdle() || waiter.getTargetSessionId() == null) continue;
            ClientSession session = restaurantService.getSession(waiter.getTargetSessionId());
            boolean stale = switch (waiter.getState()) {
                case WALKING_TO_CLIENT -> session == null ||
                        !List.of(SessionState.SEATED, SessionState.WAITING_ORDER).contains(session.getState());
                case DELIVERING        -> session == null || session.getState() != SessionState.WAITING_ORDER;
                default                -> false;
            };
            if (stale) waiter.reset();
        }

        // Transition EATING → LEAVING after timeout
        restaurantService.autoLeaveServedClients(Duration.ofSeconds(props.scheduler().eatingTimeoutSeconds()));

        // Safety net: remove LEAVING sessions the frontend never acknowledged
        restaurantService.autoCleanupLeavingSessions(Duration.ofSeconds(props.scheduler().leavingCleanupTimeoutSeconds()));

        // Safety net: WAITING_ORDER with no assigned waiter for too long → force a delivery task
        rescueStuckWaitingOrders();

        // Remove waiters marked for removal that are now idle
        waiters.removeIf(w -> w.isPendingRemoval() && w.isIdle());
    }

    /**
     * Detects clients stuck in WAITING_ORDER with no active waiter and no pending delivery task.
     * After the rescue threshold, forces a delivery task to unblock the cycle and free the seat.
     */
    private void rescueStuckWaitingOrders() {
        long thresholdSeconds = props.scheduler().rescueThresholdSeconds();
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(thresholdSeconds));

        // Sessions already handled by a waiter in transit or at the kitchen
        Set<String> activeSessions = waiters.stream()
                .filter(w -> !w.isIdle() && w.getTargetSessionId() != null)
                .map(WaiterWorker::getTargetSessionId)
                .collect(Collectors.toSet());

        // Sessions already queued for delivery
        Set<String> pendingDelivery = restaurantService.getDeliveryTasks().stream()
                .map(WaiterTask::getClientId)
                .collect(Collectors.toSet());

        for (ClientSession session : restaurantService.getSessions()) {
            if (session.getState() != SessionState.WAITING_ORDER) continue;
            if (session.getLastUpdate().isAfter(cutoff)) continue;
            if (activeSessions.contains(session.getId())) continue;
            if (pendingDelivery.contains(session.getId())) continue;

            log.warn("[Rescue] Session {} stuck in WAITING_ORDER for > {}s — forcing delivery task",
                    session.getId(), thresholdSeconds);
            restaurantService.addDeliveryTask(session.getId());
        }
    }

    // ── Callbacks from PUT /state routes ──────────────────────

    /** Frontend: PUT /state TAKING_ORDER — waiter arrived at the client's table to take the order */
    public void onWaiterArrivedForOrder(String waiterId) {
        findById(waiterId).ifPresent(waiter -> {
            if (waiter.getState() == WaiterState.WALKING_TO_CLIENT) {
                waiter.arrivedAtClient();
            }
        });
    }

    /** Frontend: PUT /state IDLE — covers both dish delivery (DELIVERING) and kitchen return (WALKING_TO_KITCHEN) */
    public void transitionToIdle(String waiterId) {
        findById(waiterId).ifPresentOrElse(waiter -> {
            if (waiter.getState() == WaiterState.DELIVERING) {
                String sessionId = waiter.getTargetSessionId();
                restaurantService.serveDish(sessionId);
                waiter.dishDelivered();
            } else if (waiter.getState() == WaiterState.WALKING_TO_KITCHEN) {
                String sessionId = waiter.getTargetSessionId();
                if (sessionId != null) restaurantService.sendOrderToKitchen(sessionId);
                waiter.reset();
            }
        }, () -> log.warn("[Waiter] transitionToIdle — waiter={} not found", waiterId));
    }

    /** Frontend: order taken, forward it to the kitchen */
    public void onOrderTaken(String waiterId) {
        findById(waiterId).ifPresent(waiter -> {
            String sessionId = waiter.getTargetSessionId();
            if (sessionId == null) return;

            ClientSession session = restaurantService.getSession(sessionId);
            // Only send to kitchen if the client is still waiting to order — guard against
            // duplicate notifyOrderTaken calls that would push extra Kafka messages
            if (session == null || session.getState() != SessionState.SEATED) {
                log.warn("[Order] onOrderTaken waiter={} REJECTED — session={} state={}", waiterId,
                        session == null ? "null" : sessionId,
                        session == null ? "null" : session.getState());
                waiter.reset();
                return;
            }

            waiter.orderTaken();
            restaurantService.markOrderTaken(sessionId);
            log.info("[Order] onOrderTaken waiter={} session={} → WALKING_TO_KITCHEN, session→WAITING_ORDER", waiterId, sessionId);
            // sendOrderToKitchen is deferred to transitionToIdle so the kitchen
            // only starts cooking once the waiter physically delivers the order slip.
        });
    }

    /** Kafka: dish ready — assign to the waiter that was WALKING_TO_KITCHEN */
    public void onDishReady(String sessionId) {
        waiters.stream()
                .filter(w -> w.getState() == WaiterState.WALKING_TO_KITCHEN
                        && sessionId.equals(w.getTargetSessionId()))
                .findFirst()
                .ifPresentOrElse(
                        w -> w.dishReady(sessionId),
                        // Waiter no longer available — fall back to delivery task queue
                        () -> restaurantService.addDeliveryTask(sessionId)
                );
    }

    public boolean waiterExists(String id) {
        return waiters.stream().anyMatch(w -> w.getId().equals(id));
    }

    private Optional<WaiterWorker> findById(String id) {
        return waiters.stream().filter(w -> w.getId().equals(id)).findFirst();
    }
}
