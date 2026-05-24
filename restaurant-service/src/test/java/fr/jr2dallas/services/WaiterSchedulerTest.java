package fr.jr2dallas.services;

import fr.jr2dallas.config.RestaurantProperties;
import fr.jr2dallas.domains.ClientSession;
import fr.jr2dallas.domains.SessionState;
import fr.jr2dallas.domains.WaiterState;
import fr.jr2dallas.domains.WaiterWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WaiterSchedulerTest {

    @Mock RestaurantService restaurantService;

    private WaiterScheduler scheduler;

    private static final RestaurantProperties PROPS = new RestaurantProperties(
            "dish-1",
            new RestaurantProperties.KafkaProperties(10),
            new RestaurantProperties.SchedulerProperties(30, 20, 15, 45)
    );

    @BeforeEach
    void setUp() {
        // Defensive stubs — only consumed by tests that call tick(), lenient avoids
        // UnnecessaryStubbingException in tests that don't reach tick()
        lenient().when(restaurantService.getOrderTasks()).thenReturn(new ConcurrentLinkedQueue<>());
        lenient().when(restaurantService.getDeliveryTasks()).thenReturn(new ConcurrentLinkedQueue<>());
        lenient().when(restaurantService.getSessions()).thenReturn(List.of());

        scheduler = new WaiterScheduler(restaurantService, PROPS, new SimpleMeterRegistry());
        // Default: starts with w1 and w2
    }

    // ── setWaiterCount ────────────────────────────────────────

    @Test
    void setWaiterCount_addsWaitersWhenCountHigher() {
        scheduler.setWaiterCount(5);
        assertThat(scheduler.getWaiterCount()).isEqualTo(5);
    }

    @Test
    void setWaiterCount_removesWaitersFromEndWhenCountLower() {
        scheduler.setWaiterCount(5);
        scheduler.setWaiterCount(2);
        assertThat(scheduler.getWaiterCount()).isEqualTo(2);
    }

    @Test
    void setWaiterCount_noopWhenCountUnchanged() {
        scheduler.setWaiterCount(2);
        assertThat(scheduler.getWaiterCount()).isEqualTo(2);
    }

    // ── hire / fire ───────────────────────────────────────────

    @Test
    void hireWaiter_incrementsCount() {
        scheduler.hireWaiter();
        assertThat(scheduler.getWaiterCount()).isEqualTo(3);
    }

    @Test
    void fireWaiter_removesIdleWaiterImmediately() {
        // Both w1 and w2 are idle at start
        scheduler.fireWaiter();
        assertThat(scheduler.getWaiterCount()).isEqualTo(1);
    }

    @Test
    void fireWaiter_marksBusyWaiterForRemovalWhenNoneIdle() {
        // Make all waiters busy
        scheduler.getWaiters().forEach(w -> w.assignOrderTask("c-busy"));

        scheduler.fireWaiter();

        assertThat(scheduler.getWaiterCount()).isEqualTo(2);
        assertThat(scheduler.getWaiters().get(0).isPendingRemoval()).isTrue();
    }

    @Test
    void tick_removesPendingWaitersOnceBecomeIdle() {
        WaiterWorker w = scheduler.getWaiters().get(0);
        w.markForRemoval();
        // w is idle + pendingRemoval → should be removed on tick
        scheduler.tick();

        assertThat(scheduler.getWaiterCount()).isEqualTo(1);
    }

    // ── State callbacks ───────────────────────────────────────

    @Test
    void onWaiterArrivedForOrder_transitionsToTakingOrder() {
        WaiterWorker w = scheduler.getWaiters().get(0);
        w.assignOrderTask("c1");

        scheduler.onWaiterArrivedForOrder(w.getId());

        assertThat(w.getState()).isEqualTo(WaiterState.TAKING_ORDER);
    }

    @Test
    void onWaiterArrivedForOrder_noopForUnknownWaiter() {
        // Should not throw
        scheduler.onWaiterArrivedForOrder("unknown-waiter");
    }

    @Test
    void onOrderTaken_transitionsToWalkingToKitchen() {
        WaiterWorker w = scheduler.getWaiters().get(0);
        w.assignOrderTask("c1");
        w.arrivedAtClient(); // TAKING_ORDER

        ClientSession session = new ClientSession("c1");
        session.seatedAt("s1");
        when(restaurantService.getSession("c1")).thenReturn(session);

        scheduler.onOrderTaken(w.getId());

        assertThat(w.getState()).isEqualTo(WaiterState.WALKING_TO_KITCHEN);
        verify(restaurantService).markOrderTaken("c1");
    }

    @Test
    void transitionToIdle_fromDelivering_servesAndResetsWaiter() {
        WaiterWorker w = scheduler.getWaiters().get(0);
        w.assignDeliveryTask("c1");

        scheduler.transitionToIdle(w.getId());

        assertThat(w.isIdle()).isTrue();
        assertThat(w.getTargetSessionId()).isNull();
        verify(restaurantService).serveDish("c1");
    }

    @Test
    void transitionToIdle_fromWalkingToKitchen_sendsOrderAndResets() {
        WaiterWorker w = scheduler.getWaiters().get(0);
        w.assignOrderTask("c1");
        w.arrivedAtClient();
        w.orderTaken(); // WALKING_TO_KITCHEN

        scheduler.transitionToIdle(w.getId());

        assertThat(w.isIdle()).isTrue();
        verify(restaurantService).sendOrderToKitchen("c1");
    }

    @Test
    void waiterExists_returnsTrueForKnownId() {
        assertThat(scheduler.waiterExists("w1")).isTrue();
        assertThat(scheduler.waiterExists("unknown")).isFalse();
    }

    // ── onDishReady ───────────────────────────────────────────

    @Test
    void onDishReady_assignsToWaiterWalkingToKitchen() {
        WaiterWorker w = scheduler.getWaiters().get(0);
        w.assignOrderTask("c1");
        w.arrivedAtClient();
        w.orderTaken(); // WALKING_TO_KITCHEN, target = c1

        scheduler.onDishReady("c1");

        assertThat(w.getState()).isEqualTo(WaiterState.DELIVERING);
    }

    @Test
    void onDishReady_fallsBackToDeliveryTaskWhenNoMatchingWaiter() {
        scheduler.onDishReady("c-orphan");

        verify(restaurantService).addDeliveryTask("c-orphan");
    }
}
