package fr.jr2dallas.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.jr2dallas.config.RestaurantProperties;
import fr.jr2dallas.domains.*;
import fr.jr2dallas.domains.exceptions.ObjectNotFoundException;
import fr.jr2dallas.generated.model.OrderMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class RestaurantService {

    private static final Logger log = LoggerFactory.getLogger(RestaurantService.class);

    private final Restaurant                        restaurant;
    private final KafkaTemplate<String, Object>     kafka;
    private final RestaurantProperties              props;
    private final AtomicInteger                     partitionCounter = new AtomicInteger(0);

    private final Map<String, ClientSession> sessions      = new ConcurrentHashMap<>();
    private final Queue<WaiterTask>          orderTasks    = new ConcurrentLinkedQueue<>();
    private final Queue<WaiterTask>          deliveryTasks = new ConcurrentLinkedQueue<>();

    private final Counter ordersSentCounter;
    private final Counter ordersFailedCounter;
    private final Counter clientsSeatedCounter;

    public RestaurantService(ObjectMapper objectMapper,
                             KafkaTemplate<String, Object> kafka,
                             RestaurantProperties props,
                             MeterRegistry meterRegistry) {
        this.kafka = kafka;
        this.props = props;

        Gauge.builder("restaurant.sessions.active", sessions, Map::size)
                .description("Number of active client sessions")
                .register(meterRegistry);

        Gauge.builder("restaurant.clients.queued", this, RestaurantService::getQueueSize)
                .description("Number of clients waiting in the entrance queue")
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

        try {
            this.restaurant = loadLayout(objectMapper);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load restaurant-layout.json", e);
        }
    }

    // ── Client lifecycle ──────────────────────────────────────

    public String enqueueNewClient() {
        return enqueueNewClient(null);
    }

    public String enqueueNewClient(String tag) {
        String clientId = UUID.randomUUID().toString();
        sessions.put(clientId, new ClientSession(clientId, tag));
        return clientId;
    }

    public void clientAtEntrance(String clientId) throws ObjectNotFoundException {
        ClientSession session = requireSession(clientId);
        if (session.getState() != SessionState.QUEUING) return;
        session.markAtEntrance();
        restaurant.enqueueClient(clientId);
    }

    public void trySeatNextClient() {
        restaurant.seatNextClient().ifPresent(a -> {
            ClientSession session = sessions.get(a.clientId());
            if (session == null || session.getState() != SessionState.AT_ENTRANCE) {
                restaurant.freeSeat(a.seatId());
                return;
            }
            session.walkingToSeat(a.seatId());
        });
    }

    public void clientSeated(String clientId) throws ObjectNotFoundException {
        ClientSession session = requireSession(clientId);
        if (session.getState() != SessionState.WALKING_TO_SEAT) return;
        session.seatedAt(session.getSeatId());
        orderTasks.add(WaiterTask.takeOrder(clientId, session.getSeatId()));
        clientsSeatedCounter.increment();
    }

    public void clientLeft(String clientId) throws ObjectNotFoundException {
        ClientSession session = requireSession(clientId);
        if (session.getState() != SessionState.LEAVING) return;
        if (session.getSeatId() != null)
            restaurant.freeSeatIfHeldBy(session.getSeatId(), clientId);
        session.markDespawned();
        sessions.remove(clientId);
    }

    // ── Waiter callbacks ──────────────────────────────────────

    public void markOrderTaken(String clientId) {
        ifSession(clientId, ClientSession::markOrdered);
    }

    public void sendOrderToKitchen(String clientId) {
        doSendOrder(clientId);
    }

    public void markDishReady(String clientId) {
        ifSession(clientId, session -> {
            if (session.getSeatId() != null)
                deliveryTasks.add(WaiterTask.deliverDish(clientId, session.getSeatId()));
        });
    }

    public void addDeliveryTask(String clientId) {
        markDishReady(clientId);
    }

    public void serveDish(String clientId) {
        ifSession(clientId, ClientSession::markEating);
    }

    // ── Scheduled maintenance ─────────────────────────────────

    public void autoLeaveServedClients(Duration delay) {
        Instant cutoff = Instant.now().minus(delay);
        sessions.values().stream()
                .filter(s -> s.getState() == SessionState.EATING
                        && s.getLastUpdate().isBefore(cutoff)
                        && s.getSeatId() != null)
                .forEach(s -> {
                    s.markLeaving();
                    restaurant.freeSeat(s.getSeatId());
                });
    }

    public void autoCleanupLeavingSessions(Duration timeout) {
        Instant cutoff = Instant.now().minus(timeout);
        sessions.entrySet().removeIf(e -> {
            ClientSession s = e.getValue();
            if (s.getState() != SessionState.LEAVING || s.getLastUpdate().isAfter(cutoff))
                return false;
            if (s.getSeatId() != null) restaurant.freeSeatIfHeldBy(s.getSeatId(), e.getKey());
            return true;
        });
    }

    // ── Queries ───────────────────────────────────────────────

    public ClientSession             getSession(String clientId) { return sessions.get(clientId); }
    public Collection<ClientSession> getSessions()               { return sessions.values(); }
    public Queue<WaiterTask>         getOrderTasks()             { return orderTasks; }
    public Queue<WaiterTask>         getDeliveryTasks()          { return deliveryTasks; }
    public int                       getQueueSize()              { return restaurant == null ? 0 : restaurant.getWaitingQueueClientIds().size(); }

    public RestaurantStateDto getState() {
        return new RestaurantStateDto(
                sessions.values().stream().toList(),
                restaurant.getSeats(),
                restaurant.isFull(),
                (int) restaurant.availableSeats()
        );
    }

    // ── Private helpers ───────────────────────────────────────

    private ClientSession requireSession(String clientId) throws ObjectNotFoundException {
        ClientSession session = sessions.get(clientId);
        if (session == null) throw new ObjectNotFoundException("No client found: " + clientId);
        return session;
    }

    private void ifSession(String clientId, Consumer<ClientSession> action) {
        ClientSession session = sessions.get(clientId);
        if (session != null) action.accept(session);
    }

    private void doSendOrder(String clientId) {
        ClientSession session = sessions.get(clientId);
        if (session == null || session.getSeatId() == null
                || session.getState() != SessionState.WAITING_ORDER) {
            log.warn("[Kafka] send skipped — client={} state={} seat={}",
                    clientId,
                    session == null ? "null" : session.getState(),
                    session == null ? "null" : session.getSeatId());
            return;
        }
        UUID orderId = UUID.randomUUID();
        log.info("[Kafka] sending order orderId={} client={} seat={}", orderId, clientId, session.getSeatId());
        OrderMessage msg = new OrderMessage()
                .orderId(orderId)
                .clientId(UUID.fromString(clientId))
                .seatId(session.getSeatId())
                .items(List.of(props.defaultDish()))
                .timestamp(OffsetDateTime.now());
        kafka.send("orders.in", Math.abs(partitionCounter.getAndIncrement() % props.kafka().ordersPartitions()), clientId, msg)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[Kafka] Failed to send order orderId={}: {}", orderId, ex.getMessage(), ex);
                        ordersFailedCounter.increment();
                    } else if (result != null && result.getRecordMetadata() != null) {
                        log.debug("[Kafka] Order sent orderId={} partition={} offset={}",
                                orderId, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                        ordersSentCounter.increment();
                    }
                });
    }

    // ── Layout ────────────────────────────────────────────────

    private static Restaurant loadLayout(ObjectMapper mapper) throws IOException {
        JsonNode root   = mapper.readTree(new ClassPathResource("restaurant-layout.json").getInputStream());
        JsonNode chairs = root.get("chair");
        if (chairs == null || !chairs.isArray())
            throw new RuntimeException("'chair' key not found in restaurant-layout.json");

        List<Seat> seats = new ArrayList<>();
        for (JsonNode chair : chairs) seats.add(new Seat(chair.get("id").asText()));

        log.info("[Layout] {} seats loaded", seats.size());
        return new Restaurant("main", seats);
    }
}
