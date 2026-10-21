package fr.jr2dallas.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.jr2dallas.config.RestaurantProperties;
import fr.jr2dallas.domains.*;
import fr.jr2dallas.generated.model.OrderMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Scenario tests: real RestaurantService + WaiterScheduler on a tiny floor, Kafka mocked.
 *
 *   t1 (100,100) chairs c1 c2        t2 (600,100) chairs c3 c4        pass (900,100)
 *   post 0 (100,300)                 post 1 (600,300)
 */
class WaiterSchedulerTest {

    private static final String LAYOUT = """
            {
              "table": [ {"id":"t1","x":100,"y":100}, {"id":"t2","x":600,"y":100} ],
              "chair": [ {"id":"c1","x":80,"y":100},  {"id":"c2","x":120,"y":100},
                         {"id":"c3","x":580,"y":100}, {"id":"c4","x":620,"y":100} ]
            }
            """;

    private KafkaTemplate<String, Object> kafka;
    private RestaurantService restaurant;
    private WaiterScheduler   scheduler;

    @SuppressWarnings("unchecked")
    private void setUp(long groupHoldSeconds, long passWaitSeconds, long rescueThresholdSeconds) throws Exception {
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        RestaurantProperties props = new RestaurantProperties(
                "dish-1",
                new RestaurantProperties.SchedulerProperties(20, 15, rescueThresholdSeconds),
                new RestaurantProperties.DispatchProperties(4, 2.0, groupHoldSeconds, passWaitSeconds, 150),
                new RestaurantProperties.TimeoutProperties(45, 20, 60),
                new RestaurantProperties.FloorProperties(new Point(900, 100),
                        List.of(new Point(100, 300), new Point(600, 300))),
                new RestaurantProperties.AutoscalingProperties(false, false, 1, 10, 1, 3)
        );
        FloorLayout layout = new FloorLayout(new ObjectMapper().readTree(LAYOUT), props.floor().pass(), props.floor().waiterPosts());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FloorLock lock = new FloorLock();
        FloorMetrics metrics = new FloorMetrics(registry);

        restaurant = new RestaurantService(kafka, props, layout, lock, metrics, registry);
        scheduler  = new WaiterScheduler(restaurant, props, layout, lock, metrics, registry);
    }

    private void setUp() throws Exception {
        setUp(60, 0, 60);
    }

    // ── Helpers ───────────────────────────────────────────────

    private String seatClient() throws Exception {
        String id = restaurant.enqueueNewClient();
        restaurant.clientAtEntrance(id);
        restaurant.trySeatNextClient();
        restaurant.clientSeated(id);
        return id;
    }

    private WaiterWorker waiter(String id) {
        return scheduler.getWaiters().stream().filter(w -> w.getId().equals(id)).findFirst().orElseThrow();
    }

    private ClientSession session(String id) {
        return restaurant.getSession(id);
    }

    /** Walk w through the order round it was given and drop the slips at the kitchen. */
    private void takeOrdersAndDrop(WaiterWorker w) {
        scheduler.onWaiterArrivedForOrder(w.getId());
        scheduler.onOrderTaken(w.getId());
        scheduler.transitionToIdle(w.getId());
    }

    private void cooked(String clientId) {
        scheduler.onDishReady(clientId, session(clientId).getOrderId());
    }

    // ── Seating + order rounds ────────────────────────────────

    @Test
    void clientsAreGroupedAtTheSameTable_andOneVisitTakesEveryOrder() throws Exception {
        setUp();
        String a = seatClient();
        String b = seatClient();
        assertThat(session(a).getTableId()).isEqualTo("t1");
        assertThat(session(b).getTableId()).isEqualTo("t1");

        scheduler.tick();

        WaiterWorker w1 = waiter("w1"); // home post next to t1
        assertThat(w1.getState()).isEqualTo(WaiterState.WALKING_TO_CLIENT);
        assertThat(w1.getSessionIds()).containsExactlyInAnyOrder(a, b);
        assertThat(waiter("w2").isIdle()).isTrue();
    }

    @Test
    void theClosestWaiterOfTheSectorTakesTheJob() throws Exception {
        setUp();
        String a = seatClient();
        scheduler.tick();
        takeOrdersAndDrop(waiter("w1"));

        String c = seatClient(); // t1 is busy → opens t2
        assertThat(session(c).getTableId()).isEqualTo("t2");

        scheduler.tick();

        assertThat(waiter("w2").getSessionIds()).containsExactly(c);
        assertThat(session(a).getOrderPhase()).isEqualTo(OrderPhase.IN_KITCHEN);
    }

    @Test
    void clientSeatedWhileTheWaiterIsOnTheWay_joinsTheRound() throws Exception {
        setUp();
        String a = seatClient();
        scheduler.tick();
        String b = seatClient();
        scheduler.tick();

        assertThat(waiter("w1").getSessionIds()).containsExactly(a, b);
        assertThat(waiter("w2").isIdle()).isTrue();
    }

    // ── Kitchen + delivery ────────────────────────────────────

    @Test
    void fullCycle_tableIsServedTogetherOnOneTray() throws Exception {
        setUp(60, 10, 60);
        String a = seatClient();
        String b = seatClient();
        scheduler.tick();
        WaiterWorker w1 = waiter("w1");
        takeOrdersAndDrop(w1);

        verify(kafka, times(2)).send(eq("orders.in"), anyString(), any());
        assertThat(session(a).getOrderPhase()).isEqualTo(OrderPhase.IN_KITCHEN);
        assertThat(w1.isWaitingAtPass(Instant.now())).isTrue();

        cooked(a);
        scheduler.tick();
        assertThat(w1.isIdle()).as("waits for the second dish of the table").isTrue();

        cooked(b);
        assertThat(w1.getState()).isEqualTo(WaiterState.DELIVERING);
        assertThat(w1.getDishes()).isEqualTo(2);

        scheduler.transitionToIdle(w1.getId());
        assertThat(session(a).getState()).isEqualTo(SessionState.EATING);
        assertThat(session(b).getState()).isEqualTo(SessionState.EATING);
        assertThat(w1.isIdle()).isTrue();
    }

    @Test
    void deliveriesComeBeforeNewOrders() throws Exception {
        setUp(0, 0, 60);
        scheduler.setWaiterCount(1);
        String a = seatClient();
        scheduler.tick();
        takeOrdersAndDrop(waiter("w1"));

        String c = seatClient();
        cooked(a); // both jobs are now pending for the only waiter

        WaiterWorker w1 = waiter("w1");
        assertThat(w1.getState()).isEqualTo(WaiterState.DELIVERING);
        assertThat(w1.getSessionIds()).containsExactly(a);
        assertThat(session(c).getState()).isEqualTo(SessionState.SEATED);
    }

    @Test
    void waiterWaitingAtThePass_doesNotLeaveForAnOrder() throws Exception {
        setUp(60, 30, 60);
        scheduler.setWaiterCount(1);
        seatClient();
        scheduler.tick();
        takeOrdersAndDrop(waiter("w1"));

        seatClient();
        scheduler.tick();

        assertThat(waiter("w1").isIdle()).isTrue();
    }

    @Test
    void duplicateOrUnknownReadyMessagesAreIgnored() throws Exception {
        setUp();
        String a = seatClient();
        scheduler.tick();
        takeOrdersAndDrop(waiter("w1"));

        scheduler.onDishReady(a, UUID.randomUUID());   // answer to another order
        scheduler.onDishReady("nobody", UUID.randomUUID());

        assertThat(session(a).getOrderPhase()).isEqualTo(OrderPhase.IN_KITCHEN);
    }

    @Test
    void lostOrdersAreResent_neverServedWithoutCooking() throws Exception {
        setUp(60, 0, 0);
        String a = seatClient();
        scheduler.tick();
        takeOrdersAndDrop(waiter("w1"));
        UUID orderId = session(a).getOrderId();

        assertThat(scheduler.rescueLostOrders(3)).as("kitchens busy: not lost, just queued").isZero();
        assertThat(scheduler.rescueLostOrders(0)).isEqualTo(1);

        verify(kafka, times(2)).send(eq("orders.in"), eq(a),
                argThat(m -> m instanceof OrderMessage om && om.getOrderId().equals(orderId)));
        assertThat(session(a).getState()).isEqualTo(SessionState.WAITING_ORDER);
        assertThat(session(a).getOrderPhase()).isEqualTo(OrderPhase.IN_KITCHEN);
    }

    // ── Staff ─────────────────────────────────────────────────

    @Test
    void hireAndFireIdleWaiters() throws Exception {
        setUp();
        scheduler.hireWaiter();
        assertThat(scheduler.getWaiterCount()).isEqualTo(3);
        scheduler.fireWaiter();
        assertThat(scheduler.getWaiterCount()).isEqualTo(2);
        assertThat(scheduler.waiterExists("w1")).isTrue();
        assertThat(scheduler.waiterExists("unknown")).isFalse();
    }

    @Test
    void setWaiterCount_neverDropsAWaiterCarryingAnOrder() throws Exception {
        setUp();
        String a = seatClient();
        scheduler.tick(); // w1 busy

        scheduler.setWaiterCount(0);
        assertThat(scheduler.getWaiterCount()).isEqualTo(1);
        assertThat(waiter("w1").isPendingRemoval()).isTrue();

        takeOrdersAndDrop(waiter("w1"));
        verify(kafka).send(eq("orders.in"), eq(a), any());

        scheduler.tick();
        assertThat(scheduler.getWaiterCount()).isZero();
    }

    @Test
    void setWaiterCount_hiresWithUniqueIds() throws Exception {
        setUp();
        scheduler.hireWaiter();          // w3
        scheduler.setWaiterCount(5);     // w4, w5
        assertThat(scheduler.getWaiters()).extracting(WaiterWorker::getId)
                .containsExactly("w1", "w2", "w3", "w4", "w5");
    }

    // ── Timeouts ──────────────────────────────────────────────

    @Test
    void timeouts_completeTheStepWhenTheFrontStopsReporting() throws Exception {
        setUp();
        String a = seatClient();
        restaurant.markOrderTaken(a, "round-1");

        WaiterWorker w1 = waiter("w1");
        Instant longAgo = Instant.now().minusSeconds(120);
        w1.assignOrderRound("t1", List.of(a), new Point(40, 100), longAgo);
        w1.arrivedAtClient(longAgo);
        w1.orderTaken(longAgo); // walking to the kitchen for 2 minutes

        scheduler.tick();

        verify(kafka).send(eq("orders.in"), eq(a), any());
        assertThat(session(a).getOrderPhase()).isEqualTo(OrderPhase.IN_KITCHEN);
        assertThat(w1.isIdle()).isTrue();
    }
}
