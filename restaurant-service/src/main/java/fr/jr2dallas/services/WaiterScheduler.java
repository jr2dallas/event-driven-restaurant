package fr.jr2dallas.services;

import fr.jr2dallas.config.RestaurantProperties;
import fr.jr2dallas.domains.*;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Dispatches waiters. On every tick the pending jobs are rebuilt from the floor state:
 * <ul>
 *   <li>ORDER: a table with seated clients nobody is serving — one visit takes every order there;</li>
 *   <li>DELIVERY: ready dishes of one table — carried together on one tray.</li>
 * </ul>
 * Jobs are handled most urgent first (deliveries weigh more: serving frees seats), each by
 * the closest free waiter, with a penalty for tables outside the waiter's sector.
 * A waiter who just dropped a slip at the kitchen waits a little at the pass, where the
 * next dishes come out.
 *
 * Every public method runs under the {@link FloorLock}.
 */
@Service
public class WaiterScheduler {

    private static final Logger log = LoggerFactory.getLogger(WaiterScheduler.class);

    private final RestaurantService    restaurantService;
    private final RestaurantProperties props;
    private final FloorLayout          layout;
    private final FloorLock            lock;
    private final FloorMetrics         metrics;

    private final List<WaiterWorker> waiters = new CopyOnWriteArrayList<>();
    private final AtomicInteger waiterIdCounter = new AtomicInteger(1);

    public WaiterScheduler(RestaurantService restaurantService,
                           RestaurantProperties props,
                           FloorLayout layout,
                           FloorLock lock,
                           FloorMetrics metrics,
                           MeterRegistry meterRegistry) {
        this.restaurantService = restaurantService;
        this.props   = props;
        this.layout  = layout;
        this.lock    = lock;
        this.metrics = metrics;

        hireWaiter();
        hireWaiter();

        Gauge.builder("restaurant.waiters.active", waiters, List::size)
                .description("Number of active waiter workers")
                .register(meterRegistry);
        Gauge.builder("restaurant.waiters.idle", waiters,
                        ws -> ws.stream().filter(WaiterWorker::isIdle).count())
                .description("Waiters with nothing to do")
                .register(meterRegistry);
    }

    // ── Staff ─────────────────────────────────────────────────

    public List<WaiterWorker> getWaiters() { return waiters; }
    public int getWaiterCount() { return waiters.size(); }

    public void hireWaiter() {
        lock.run(() -> {
            int n = waiterIdCounter.getAndIncrement();
            // Home posts are handed out round-robin: waiters spread over the room's sectors
            int post = (n - 1) % layout.posts().size();
            waiters.add(new WaiterWorker("w" + n, post, layout.post(post)));
        });
    }

    /** Removes an idle waiter now, or lets the first busy one finish its round before leaving. */
    public void fireWaiter() {
        lock.run(() -> {
            Optional<WaiterWorker> idle = waiters.stream()
                    .filter(w -> w.isIdle() && !w.isPendingRemoval())
                    .reduce((a, b) -> b); // last hired first
            if (idle.isPresent()) {
                waiters.remove(idle.get());
                return;
            }
            waiters.stream()
                    .filter(w -> !w.isPendingRemoval())
                    .reduce((a, b) -> b)
                    .ifPresent(WaiterWorker::markForRemoval);
        });
    }

    /** Same graceful rules as {@link #fireWaiter()}: a waiter carrying an order is never dropped. */
    public void setWaiterCount(int count) {
        lock.run(() -> {
            while (activeWaiters() < count) hireWaiter();
            while (activeWaiters() > count) fireWaiter();
        });
    }

    private long activeWaiters() {
        return waiters.stream().filter(w -> !w.isPendingRemoval()).count();
    }

    // ── Main tick ─────────────────────────────────────────────

    // fixedDelayString reads directly from the environment — @ConfigurationProperties beans
    // are not available at annotation processing time
    @Scheduled(fixedDelayString = "${restaurant.scheduler.tick-delay-ms:200}")
    public void tick() {
        lock.run(() -> {
            Instant now = Instant.now();

            restaurantService.trySeatNextClient();
            restaurantService.autoSeatStuckClients(Duration.ofSeconds(props.timeouts().walkToSeatSeconds()));

            applyWaiterTimeouts(now);
            cleanRounds(now);
            growOrderRounds();
            dispatch(now);

            restaurantService.autoLeaveServedClients(Duration.ofSeconds(props.scheduler().eatingTimeoutSeconds()));
            restaurantService.autoCleanupLeavingSessions(Duration.ofSeconds(props.scheduler().leavingCleanupTimeoutSeconds()));
            restaurantService.retryFailedSends();

            waiters.removeIf(w -> w.isPendingRemoval() && w.isIdle());
        });
    }

    // ── Dispatch ──────────────────────────────────────────────

    void dispatch(Instant now) {
        List<WaiterWorker> free = new ArrayList<>(waiters.stream()
                .filter(w -> w.isIdle() && !w.isPendingRemoval())
                .toList());
        if (free.isEmpty()) return;

        double weight = props.dispatch().deliveryPriorityWeight();
        List<WaiterTask> tasks = pendingTasks(now);
        tasks.sort(Comparator.comparingDouble((WaiterTask t) -> t.urgency(now, weight)).reversed());

        for (WaiterTask task : tasks) {
            if (free.isEmpty()) break;
            WaiterWorker best = null;
            double bestCost = Double.MAX_VALUE;
            for (WaiterWorker w : free) {
                // A waiter waiting at the pass is kept for dishes; it takes orders once its wait is over
                if (task.type() == WaiterTask.Type.ORDER && w.isWaitingAtPass(now)) continue;
                double cost = cost(w, task, now);
                if (cost < bestCost) { bestCost = cost; best = w; }
            }
            if (best == null) continue;
            assign(best, task, now);
            free.remove(best);
        }
    }

    /** Distance to the start of the job, plus a penalty outside the waiter's sector. */
    private double cost(WaiterWorker w, WaiterTask task, Instant now) {
        double cost = w.estimatedPosition(now).distanceTo(task.start());
        if (layout.sectorOf(task.tableId()) != w.getHomePostIndex())
            cost += props.dispatch().sectorPenaltyPx();
        if (task.type() == WaiterTask.Type.DELIVERY && task.tableId().equals(w.getPassReservedTable()))
            cost -= 1_000; // the waiter who took this order is already waiting for it
        return cost;
    }

    private void assign(WaiterWorker w, WaiterTask task, Instant now) {
        if (task.type() == WaiterTask.Type.ORDER) {
            w.assignOrderRound(task.tableId(), task.sessionIds(), task.target(), now);
        } else {
            w.assignDeliveryRound(task.tableId(), task.sessionIds(), task.target(), now);
            task.sessionIds().forEach(restaurantService::pickUp);
        }
        log.debug("[Dispatch] {} → {} table={} clients={}", w.getId(), task.type(), task.tableId(), task.sessionIds().size());
    }

    /** Jobs nobody has taken yet, rebuilt from the sessions on every tick. */
    List<WaiterTask> pendingTasks(Instant now) {
        Set<String> claimed = waiters.stream()
                .flatMap(w -> w.getSessionIds().stream())
                .collect(Collectors.toSet());
        Map<String, List<ClientSession>> toOrder = new LinkedHashMap<>();
        Map<String, List<ClientSession>> toServe = new LinkedHashMap<>();
        for (ClientSession s : restaurantService.getSessions()) {
            if (s.getTableId() == null || claimed.contains(s.getId())) continue;
            if (s.getState() == SessionState.SEATED)
                toOrder.computeIfAbsent(s.getTableId(), k -> new ArrayList<>()).add(s);
            else if (s.getState() == SessionState.WAITING_ORDER && s.getOrderPhase() == OrderPhase.READY_AT_PASS)
                toServe.computeIfAbsent(s.getTableId(), k -> new ArrayList<>()).add(s);
        }

        List<WaiterTask> tasks = new ArrayList<>();
        toOrder.forEach((table, clients) -> {
            clients.sort(Comparator.comparing(ClientSession::getSeatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            tasks.add(new WaiterTask(WaiterTask.Type.ORDER, table, ids(clients),
                    clients.get(0).getSeatedAt() != null ? clients.get(0).getSeatedAt() : now,
                    layout.servicePosition(clients.get(0).getSeatId()),
                    layout.servicePosition(clients.get(0).getSeatId())));
        });
        toServe.forEach((table, dishes) -> {
            dishes.sort(Comparator.comparing(ClientSession::getDishReadyAt, Comparator.nullsLast(Comparator.naturalOrder())));
            if (!readyToServe(dishes, now)) return;
            int tray = Math.max(1, props.dispatch().trayCapacity());
            for (int i = 0; i < dishes.size(); i += tray) {
                List<ClientSession> chunk = dishes.subList(i, Math.min(dishes.size(), i + tray));
                tasks.add(new WaiterTask(WaiterTask.Type.DELIVERY, table, ids(chunk),
                        chunk.get(0).getDishReadyAt() != null ? chunk.get(0).getDishReadyAt() : now,
                        layout.pass(),
                        layout.servicePosition(chunk.get(0).getSeatId())));
            }
        });
        return tasks;
    }

    /**
     * A table is served together: the tray leaves once every dish ordered in the same visit is
     * ready, or when the first ready dish has waited {@code groupHoldSeconds} (no cold food).
     */
    private boolean readyToServe(List<ClientSession> readyDishes, Instant now) {
        Instant oldest = readyDishes.get(0).getDishReadyAt();
        if (oldest == null || Duration.between(oldest, now).getSeconds() >= props.dispatch().groupHoldSeconds())
            return true;
        Set<String> rounds = readyDishes.stream()
                .map(ClientSession::getOrderRoundId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // Any dish of the same order round still cooking (or in the slip)? Then wait for it.
        return restaurantService.getSessions().stream()
                .filter(s -> s.getOrderRoundId() != null && rounds.contains(s.getOrderRoundId()))
                .noneMatch(s -> s.getOrderPhase() == OrderPhase.IN_KITCHEN || s.getOrderPhase() == OrderPhase.SLIP_IN_TRANSIT);
    }

    /** Clients who sat down at a table a waiter is already heading to (or serving) join the round. */
    private void growOrderRounds() {
        Set<String> claimed = waiters.stream()
                .flatMap(w -> w.getSessionIds().stream())
                .collect(Collectors.toSet());
        for (WaiterWorker w : waiters) {
            if (w.getState() != WaiterState.WALKING_TO_CLIENT && w.getState() != WaiterState.TAKING_ORDER) continue;
            for (ClientSession s : restaurantService.getSessions()) {
                if (s.getState() == SessionState.SEATED && w.getTableId().equals(s.getTableId()) && !claimed.contains(s.getId())) {
                    w.addToRound(s.getId());
                    claimed.add(s.getId());
                }
            }
        }
    }

    /** Remove clients that left a round's expected state; free the waiter if nobody is left. */
    private void cleanRounds(Instant now) {
        for (WaiterWorker w : waiters) {
            if (w.isIdle()) continue;
            List<String> gone = w.getSessionIds().stream()
                    .filter(id -> !belongsToRound(w, restaurantService.getSession(id)))
                    .toList();
            if (gone.isEmpty()) continue;
            if (w.removeFromRound(gone)) {
                log.info("[Dispatch] {} round emptied ({}), back to idle", w.getId(), w.getState());
                w.reset(now);
            }
        }
    }

    private boolean belongsToRound(WaiterWorker w, ClientSession s) {
        if (s == null) return false;
        return switch (w.getState()) {
            case WALKING_TO_CLIENT, TAKING_ORDER -> s.getState() == SessionState.SEATED;
            case WALKING_TO_KITCHEN -> s.getOrderPhase() == OrderPhase.SLIP_IN_TRANSIT;
            case DELIVERING -> s.getOrderPhase() == OrderPhase.READY_AT_PASS;
            case IDLE -> false;
        };
    }

    // ── Timeouts: keep the floor moving when the front stops reporting ──

    private void applyWaiterTimeouts(Instant now) {
        var t = props.timeouts();
        for (WaiterWorker w : waiters) {
            long secs = Duration.between(w.getStateSince(), now).getSeconds();
            switch (w.getState()) {
                case WALKING_TO_CLIENT -> {
                    if (secs < t.walkSeconds()) continue;
                    timeout(w, "walk_to_client");
                    onWaiterArrivedForOrder(w.getId());
                }
                case TAKING_ORDER -> {
                    if (secs < t.takingOrderSeconds()) continue;
                    timeout(w, "taking_order");
                    onOrderTaken(w.getId());
                }
                case WALKING_TO_KITCHEN -> {
                    if (secs < t.walkSeconds()) continue;
                    timeout(w, "walk_to_kitchen");
                    transitionToIdle(w.getId());
                }
                case DELIVERING -> {
                    // pass + table: two walks
                    if (secs < t.walkSeconds() * 2) continue;
                    timeout(w, "delivery");
                    transitionToIdle(w.getId());
                }
                default -> { }
            }
        }
    }

    private void timeout(WaiterWorker w, String step) {
        log.warn("[Timeout] waiter {} stuck in {} — completing the step (front not reporting?)", w.getId(), w.getState());
        metrics.timeout(step);
    }

    // ── Rescue: orders lost between the kitchen and us ────────

    /**
     * Called by the autoscaling/maintenance loop with the kitchen backlog: when the kitchens have
     * nothing queued, an order still IN_KITCHEN long after being sent was lost — re-send it.
     */
    public int rescueLostOrders(long kitchenLag) {
        if (kitchenLag > 0) return 0;
        return restaurantService.resendStuckOrders(Duration.ofSeconds(props.scheduler().rescueThresholdSeconds()));
    }

    // ── Callbacks from PUT /state routes (or timeouts) ────────

    /** Front: waiter reached the table. WALKING_TO_CLIENT → TAKING_ORDER */
    public void onWaiterArrivedForOrder(String waiterId) {
        lock.run(() -> findById(waiterId).ifPresent(w -> w.arrivedAtClient(Instant.now())));
    }

    /** Front: orders written. Every seated client of the round now waits for their dish. */
    public void onOrderTaken(String waiterId) {
        lock.run(() -> findById(waiterId).ifPresent(w -> {
            if (w.getState() != WaiterState.TAKING_ORDER) {
                log.warn("[Order] onOrderTaken waiter={} ignored — state={}", waiterId, w.getState());
                return;
            }
            String roundId = UUID.randomUUID().toString();
            List<String> notOrdered = w.getSessionIds().stream()
                    .filter(id -> !restaurantService.markOrderTaken(id, roundId))
                    .toList();
            if (w.removeFromRound(notOrdered)) {
                log.warn("[Order] onOrderTaken waiter={} — nobody left to serve, back to idle", waiterId);
                w.reset(Instant.now());
                return;
            }
            w.orderTaken(Instant.now());
            log.info("[Order] waiter={} took {} order(s) at {} → WALKING_TO_KITCHEN", waiterId, w.getSessionIds().size(), w.getTableId());
        }));
    }

    /** Front: PUT /state IDLE — order slips dropped at the kitchen, or tray delivered. */
    public void transitionToIdle(String waiterId) {
        lock.run(() -> findById(waiterId).ifPresentOrElse(w -> {
            Instant now = Instant.now();
            if (w.getState() == WaiterState.WALKING_TO_KITCHEN) {
                // The kitchen only starts cooking once the slip is physically at the pass
                w.getSessionIds().forEach(restaurantService::sendOrderToKitchen);
                w.droppedAtKitchen(layout.pass(), now.plusSeconds(props.dispatch().passWaitSeconds()), now);
                dispatch(now); // the waiter is at the pass: maybe a tray is ready right now
            } else if (w.getState() == WaiterState.DELIVERING) {
                w.getSessionIds().forEach(restaurantService::serveDish);
                w.delivered(now);
            }
        }, () -> log.warn("[Waiter] transitionToIdle — waiter={} not found", waiterId)));
    }

    /** Kafka: dish ready — it goes to the pass; the dispatcher sends a waiter. */
    public void onDishReady(String sessionId, UUID orderId) {
        lock.run(() -> {
            if (restaurantService.dishReady(sessionId, orderId)) dispatch(Instant.now());
        });
    }

    public void onDishReady(String sessionId) {
        onDishReady(sessionId, null);
    }

    public boolean waiterExists(String id) {
        return lock.get(() -> waiters.stream().anyMatch(w -> w.getId().equals(id)));
    }

    /** Pending work, for autoscaling: number of jobs and how long the oldest has waited. */
    public Backlog backlog() {
        return lock.get(() -> {
            Instant now = Instant.now();
            List<WaiterTask> tasks = pendingTasks(now);
            long oldest = tasks.stream()
                    .mapToLong(t -> Duration.between(t.waitingSince(), now).getSeconds())
                    .max().orElse(0);
            long freeWaiters = waiters.stream()
                    .filter(w -> w.isIdle() && !w.isPendingRemoval() && !w.isWaitingAtPass(now))
                    .count();
            return new Backlog(tasks.size(), oldest, freeWaiters, activeWaiters());
        });
    }

    public record Backlog(int tasks, long oldestWaitSeconds, long freeWaiters, long waiters) {}

    private Optional<WaiterWorker> findById(String id) {
        return waiters.stream().filter(w -> w.getId().equals(id)).findFirst();
    }

    private static List<String> ids(List<ClientSession> sessions) {
        return sessions.stream().map(ClientSession::getId).toList();
    }
}
