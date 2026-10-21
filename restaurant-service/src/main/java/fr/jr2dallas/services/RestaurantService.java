package fr.jr2dallas.services;

import fr.jr2dallas.config.RestaurantProperties;
import fr.jr2dallas.domains.*;
import fr.jr2dallas.domains.exceptions.ObjectNotFoundException;
import fr.jr2dallas.generated.model.OrderMessage;
import fr.jr2dallas.services.FloorMetrics.Stage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Clients and their orders. Every public method runs under the {@link FloorLock}.
 */
@Service
public class RestaurantService {

    private static final Logger log = LoggerFactory.getLogger(RestaurantService.class);

    private final Restaurant                    restaurant;
    private final FloorLayout                   layout;
    private final KafkaTemplate<String, Object> kafka;
    private final RestaurantProperties          props;
    private final FloorLock                     lock;
    private final FloorMetrics                  metrics;

    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();

    private final Counter ordersSentCounter;
    private final Counter ordersFailedCounter;
    private final Counter clientsSeatedCounter;

    public RestaurantService(KafkaTemplate<String, Object> kafka,
                             RestaurantProperties props,
                             FloorLayout layout,
                             FloorLock lock,
                             FloorMetrics metrics,
                             MeterRegistry meterRegistry) {
        this.kafka   = kafka;
        this.props   = props;
        this.layout  = layout;
        this.lock    = lock;
        this.metrics = metrics;
        this.restaurant = new Restaurant("main", layout.seatIds().stream().map(Seat::new).toList());
        log.info("[Layout] {} seats loaded", layout.seatIds().size());

        Gauge.builder("restaurant.sessions.active", sessions, Map::size)
                .description("Number of active client sessions")
                .register(meterRegistry);
        Gauge.builder("restaurant.clients.queued", this, RestaurantService::getQueueSize)
                .description("Number of clients waiting in the entrance queue")
                .register(meterRegistry);
        Gauge.builder("restaurant.orders.in_kitchen", this, s -> s.countPhase(OrderPhase.IN_KITCHEN))
                .description("Orders sent to the kitchen and not ready yet")
                .register(meterRegistry);
        Gauge.builder("restaurant.pass.dishes", this, s -> s.countPhase(OrderPhase.READY_AT_PASS))
                .description("Ready dishes waiting at the pass for a waiter")
                .register(meterRegistry);
        this.ordersSentCounter = Counter.builder("restaurant.orders.sent")
                .description("Total orders successfully sent to Kafka")
                .register(meterRegistry);
        this.ordersFailedCounter = Counter.builder("restaurant.orders.failed")
                .description("Total orders that failed to send to Kafka")
                .register(meterRegistry);
        this.clientsSeatedCounter = Counter.builder("restaurant.clients.seated")
                .description("Total clients seated at a table")
                .register(meterRegistry);
    }

    // ── Client lifecycle (front callbacks) ────────────────────

    public String enqueueNewClient() {
        return enqueueNewClient(null);
    }

    public String enqueueNewClient(String tag) {
        String clientId = UUID.randomUUID().toString();
        lock.run(() -> sessions.put(clientId, new ClientSession(clientId, tag)));
        return clientId;
    }

    public void clientAtEntrance(String clientId) throws ObjectNotFoundException {
        lock.run(() -> {
            ClientSession session = requireSession(clientId);
            if (session.getState() != SessionState.QUEUING) return;
            session.markAtEntrance();
            restaurant.enqueueClient(clientId);
        });
    }

    public void trySeatNextClient() {
        lock.run(() -> restaurant.seatNextClient(this::chooseSeat).ifPresent(a -> {
            ClientSession session = sessions.get(a.clientId());
            if (session == null || session.getState() != SessionState.AT_ENTRANCE) {
                restaurant.freeSeat(a.seatId());
                return;
            }
            session.walkingToSeat(a.seatId(), layout.tableOf(a.seatId()));
            metrics.record(Stage.QUEUE, session.getCreatedAt(), session.getSeatAssignedAt());
        }));
    }

    public void clientSeated(String clientId) throws ObjectNotFoundException {
        lock.run(() -> {
            ClientSession session = requireSession(clientId);
            if (session.getState() != SessionState.WALKING_TO_SEAT) return;
            session.seatedAt(session.getSeatId());
            clientsSeatedCounter.increment();
            metrics.record(Stage.WALK_TO_SEAT, session.getSeatAssignedAt(), session.getSeatedAt());
        });
    }

    public void clientLeft(String clientId) throws ObjectNotFoundException {
        lock.run(() -> {
            ClientSession session = requireSession(clientId);
            if (session.getState() != SessionState.LEAVING) return;
            if (session.getSeatId() != null)
                restaurant.freeSeatIfHeldBy(session.getSeatId(), clientId);
            session.markDespawned();
            sessions.remove(clientId);
        });
    }

    // ── Order flow (driven by WaiterScheduler) ────────────────

    /** SEATED → WAITING_ORDER (slip in the waiter's hand). */
    public boolean markOrderTaken(String clientId, String roundId) {
        return lock.get(() -> {
            ClientSession session = sessions.get(clientId);
            if (session == null || session.getState() != SessionState.SEATED) return false;
            session.markOrdered(roundId);
            metrics.record(Stage.WAIT_ORDER, session.getSeatedAt(), session.getOrderTakenAt());
            return true;
        });
    }

    /** The waiter reached the kitchen: the order is sent to Kafka (kitchen starts cooking). */
    public boolean sendOrderToKitchen(String clientId) {
        return lock.get(() -> {
            ClientSession session = sessions.get(clientId);
            if (session == null || session.getSeatId() == null
                    || session.getState() != SessionState.WAITING_ORDER
                    || session.getOrderPhase() != OrderPhase.SLIP_IN_TRANSIT) {
                log.warn("[Kafka] send skipped — client={} state={} phase={}", clientId,
                        session == null ? "null" : session.getState(),
                        session == null ? "null" : session.getOrderPhase());
                return false;
            }
            send(session, UUID.randomUUID());
            metrics.record(Stage.SLIP_TRANSIT, session.getOrderTakenAt(), session.getOrderSentAt());
            return true;
        });
    }

    /** Kafka orders.ready. Ignores duplicates and late answers to a re-sent order. */
    public boolean dishReady(String clientId, UUID orderId) {
        return lock.get(() -> {
            ClientSession session = sessions.get(clientId);
            if (session == null
                    || session.getOrderPhase() != OrderPhase.IN_KITCHEN
                    || (orderId != null && !orderId.equals(session.getOrderId()))) {
                metrics.duplicateDish();
                log.debug("[Kitchen] ready ignored — client={} orderId={}", clientId, orderId);
                return false;
            }
            session.dishReady();
            metrics.record(Stage.KITCHEN, session.getOrderSentAt(), session.getDishReadyAt());
            return true;
        });
    }

    /** A waiter took the dish from the pass. */
    public void pickUp(String clientId) {
        lock.run(() -> {
            ClientSession session = sessions.get(clientId);
            if (session == null || session.getOrderPhase() != OrderPhase.READY_AT_PASS) return;
            session.pickedUp();
            metrics.record(Stage.PASS_WAIT, session.getDishReadyAt(), session.getPickedUpAt());
        });
    }

    /** Dish on the table: WAITING_ORDER → EATING. Only dishes that really came out of the kitchen. */
    public boolean serveDish(String clientId) {
        return lock.get(() -> {
            ClientSession session = sessions.get(clientId);
            if (session == null
                    || session.getState() != SessionState.WAITING_ORDER
                    || session.getOrderPhase() != OrderPhase.READY_AT_PASS) return false;
            session.markEating();
            metrics.record(Stage.DELIVERY, session.getPickedUpAt(), session.getServedAt());
            return true;
        });
    }

    // ── Scheduled maintenance ─────────────────────────────────

    public void autoLeaveServedClients(Duration delay) {
        lock.run(() -> {
            Instant now    = Instant.now();
            Instant cutoff = now.minus(delay);
            for (ClientSession s : sessions.values()) {
                if (s.getState() != SessionState.EATING || !s.getLastUpdate().isBefore(cutoff) || s.getSeatId() == null)
                    continue;
                s.markLeaving();
                restaurant.freeSeat(s.getSeatId());
                metrics.record(Stage.EATING, s.getServedAt(), now);
                metrics.record(Stage.SEAT_TOTAL, s.getSeatAssignedAt(), now);
            }
        });
    }

    public void autoCleanupLeavingSessions(Duration timeout) {
        lock.run(() -> {
            Instant cutoff = Instant.now().minus(timeout);
            sessions.entrySet().removeIf(e -> {
                ClientSession s = e.getValue();
                if (s.getState() != SessionState.LEAVING || s.getLastUpdate().isAfter(cutoff))
                    return false;
                if (s.getSeatId() != null) restaurant.freeSeatIfHeldBy(s.getSeatId(), e.getKey());
                return true;
            });
        });
    }

    /** Orders whose Kafka send failed are sent again (same orderId). */
    public void retryFailedSends() {
        lock.run(() -> {
            for (ClientSession s : sessions.values()) {
                if (!s.isSendFailed() || s.getOrderPhase() != OrderPhase.IN_KITCHEN) continue;
                log.warn("[Kafka] retrying failed send — client={} orderId={}", s.getId(), s.getOrderId());
                metrics.resent();
                send(s, s.getOrderId());
            }
        });
    }

    /**
     * Orders stuck in the kitchen for too long. Only called when the kitchens have no backlog,
     * so a missing "ready" really means the order was lost: it is re-sent with the same orderId.
     */
    public int resendStuckOrders(Duration threshold) {
        return lock.get(() -> {
            Instant cutoff = Instant.now().minus(threshold);
            int count = 0;
            for (ClientSession s : sessions.values()) {
                if (s.getOrderPhase() != OrderPhase.IN_KITCHEN || s.isSendFailed()) continue;
                if (s.getOrderSentAt() == null || s.getOrderSentAt().isAfter(cutoff)) continue;
                log.warn("[Rescue] order stuck in kitchen > {}s — re-sending client={} orderId={}",
                        threshold.toSeconds(), s.getId(), s.getOrderId());
                metrics.resent();
                send(s, s.getOrderId());
                count++;
            }
            return count;
        });
    }

    /** Safety net: the front never reported the client sitting down. */
    public void autoSeatStuckClients(Duration timeout) {
        lock.run(() -> {
            Instant cutoff = Instant.now().minus(timeout);
            for (ClientSession s : sessions.values()) {
                if (s.getState() != SessionState.WALKING_TO_SEAT || s.getLastUpdate().isAfter(cutoff)) continue;
                log.warn("[Timeout] client {} never reported SEATED — seating it", s.getId());
                metrics.timeout("walk_to_seat");
                s.seatedAt(s.getSeatId());
                clientsSeatedCounter.increment();
                metrics.record(Stage.WALK_TO_SEAT, s.getSeatAssignedAt(), s.getSeatedAt());
            }
        });
    }

    // ── Queries (callers needing consistency hold the FloorLock) ──

    public ClientSession             getSession(String clientId) { return sessions.get(clientId); }
    public Collection<ClientSession> getSessions()               { return sessions.values(); }
    public int                       getQueueSize()              { return restaurant.getWaitingQueueClientIds().size(); }

    public RestaurantStateDto getState() {
        return lock.get(() -> new RestaurantStateDto(
                List.copyOf(sessions.values()),
                restaurant.getSeats(),
                restaurant.isFull(),
                (int) restaurant.availableSeats()
        ));
    }

    // ── Private helpers ───────────────────────────────────────

    private ClientSession requireSession(String clientId) throws ObjectNotFoundException {
        ClientSession session = sessions.get(clientId);
        if (session == null) throw new ObjectNotFoundException("No client found: " + clientId);
        return session;
    }

    private int countPhase(OrderPhase phase) {
        int n = 0;
        for (ClientSession s : sessions.values()) if (s.getOrderPhase() == phase) n++;
        return n;
    }

    /**
     * Seat choice: join a table where clients are about to order (one waiter visit for all),
     * else open an empty table (so the next arrivals can join), else any free seat.
     */
    private Optional<Seat> chooseSeat(List<Seat> free) {
        Map<String, Long> pendingOrders = sessions.values().stream()
                .filter(s -> s.getTableId() != null
                        && (s.getState() == SessionState.WALKING_TO_SEAT || s.getState() == SessionState.SEATED))
                .collect(Collectors.groupingBy(ClientSession::getTableId, Collectors.counting()));
        Set<String> occupiedTables = restaurant.getSeats().stream()
                .filter(Seat::isOccupied)
                .map(s -> layout.tableOf(s.getId()))
                .collect(Collectors.toSet());

        return free.stream().min(Comparator
                .comparingInt((Seat s) -> {
                    String table = layout.tableOf(s.getId());
                    if (pendingOrders.getOrDefault(table, 0L) > 0) return 0;
                    return occupiedTables.contains(table) ? 2 : 1;
                })
                .thenComparingLong(s -> -pendingOrders.getOrDefault(layout.tableOf(s.getId()), 0L)));
    }

    private void send(ClientSession session, UUID orderId) {
        session.orderSent(orderId);
        String clientId = session.getId();
        OrderMessage msg = new OrderMessage()
                .orderId(orderId)
                .clientId(UUID.fromString(clientId))
                .seatId(session.getSeatId())
                .items(List.of(props.defaultDish()))
                .timestamp(OffsetDateTime.now());
        log.info("[Kafka] sending order orderId={} client={} seat={}", orderId, clientId, session.getSeatId());

        // Keyed by client: Kafka spreads orders over every partition (no hard-coded partition count)
        try {
            kafka.send("orders.in", clientId, msg).whenComplete((result, ex) -> {
                if (ex == null) {
                    ordersSentCounter.increment();
                    return;
                }
                log.error("[Kafka] Failed to send order orderId={}: {}", orderId, ex.getMessage());
                ordersFailedCounter.increment();
                markSendFailed(clientId, orderId);
            });
        } catch (Exception e) {
            log.error("[Kafka] Failed to send order orderId={}: {}", orderId, e.getMessage());
            ordersFailedCounter.increment();
            session.sendFailed();
        }
    }

    private void markSendFailed(String clientId, UUID orderId) {
        lock.run(() -> {
            ClientSession s = sessions.get(clientId);
            if (s != null && s.getOrderPhase() == OrderPhase.IN_KITCHEN && orderId.equals(s.getOrderId()))
                s.sendFailed();
        });
    }
}
