package fr.jr2dallas.domains;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class WaiterWorkerTest {

    private Queue<WaiterTask> orderTasks;
    private Queue<WaiterTask> deliveryTasks;

    @BeforeEach
    void setUp() {
        orderTasks    = new LinkedList<>();
        deliveryTasks = new LinkedList<>();
    }

    @Test
    void newWaiter_isIdle() {
        WaiterWorker waiter = new WaiterWorker("w1");

        assertThat(waiter.isIdle()).isTrue();
        assertThat(waiter.getState()).isEqualTo(WaiterState.IDLE);
        assertThat(waiter.getTargetSessionId()).isNull();
    }

    @Test
    void pickNextTask_takesOrderTask() {
        WaiterWorker waiter = new WaiterWorker("w1");
        orderTasks.add(WaiterTask.takeOrder("c1", "s1"));

        var task = waiter.pickNextTask(orderTasks, deliveryTasks, Duration.ofMinutes(5));

        assertThat(task).isPresent();
        assertThat(waiter.getState()).isEqualTo(WaiterState.WALKING_TO_CLIENT);
        assertThat(waiter.getTargetSessionId()).isEqualTo("c1");
        assertThat(orderTasks).isEmpty();
    }

    @Test
    void pickNextTask_prefersUrgentDeliveryOverOrderTask() {
        WaiterWorker waiter = new WaiterWorker("w1");
        orderTasks.add(WaiterTask.takeOrder("c1", "s1"));
        // Delivery created 10 min ago — older than the 5 min threshold
        deliveryTasks.add(new WaiterTask(WaiterState.DELIVERING, "c2", "s2",
                Instant.now().minus(Duration.ofMinutes(10))));

        var task = waiter.pickNextTask(orderTasks, deliveryTasks, Duration.ofMinutes(5));

        assertThat(task).isPresent();
        assertThat(waiter.getState()).isEqualTo(WaiterState.DELIVERING);
        assertThat(waiter.getTargetSessionId()).isEqualTo("c2");
        assertThat(orderTasks).hasSize(1); // order task untouched
    }

    @Test
    void pickNextTask_takesNormalDeliveryAsLastResort() {
        WaiterWorker waiter = new WaiterWorker("w1");
        deliveryTasks.add(WaiterTask.deliverDish("c1", "s1"));

        var task = waiter.pickNextTask(orderTasks, deliveryTasks, Duration.ofMinutes(5));

        assertThat(task).isPresent();
        assertThat(waiter.getState()).isEqualTo(WaiterState.DELIVERING);
        assertThat(deliveryTasks).isEmpty();
    }

    @Test
    void pickNextTask_doesNothingWhenBusy() {
        WaiterWorker waiter = new WaiterWorker("w1");
        waiter.assignOrderTask("c1");
        orderTasks.add(WaiterTask.takeOrder("c2", "s2"));

        var task = waiter.pickNextTask(orderTasks, deliveryTasks, Duration.ofMinutes(5));

        assertThat(task).isEmpty();
        assertThat(orderTasks).hasSize(1);
    }

    @Test
    void pickNextTask_doesNothingWhenPendingRemoval() {
        WaiterWorker waiter = new WaiterWorker("w1");
        waiter.markForRemoval();
        orderTasks.add(WaiterTask.takeOrder("c1", "s1"));

        var task = waiter.pickNextTask(orderTasks, deliveryTasks, Duration.ofMinutes(5));

        assertThat(task).isEmpty();
        assertThat(waiter.isPendingRemoval()).isTrue();
    }

    @Test
    void fullOrderCycle_idle_to_idle() {
        WaiterWorker waiter = new WaiterWorker("w1");

        waiter.assignOrderTask("c1");
        assertThat(waiter.getState()).isEqualTo(WaiterState.WALKING_TO_CLIENT);

        waiter.arrivedAtClient();
        assertThat(waiter.getState()).isEqualTo(WaiterState.TAKING_ORDER);

        waiter.orderTaken();
        assertThat(waiter.getState()).isEqualTo(WaiterState.WALKING_TO_KITCHEN);

        waiter.dishReady("c1");
        assertThat(waiter.getState()).isEqualTo(WaiterState.DELIVERING);

        waiter.dishDelivered();
        assertThat(waiter.getState()).isEqualTo(WaiterState.IDLE);
        assertThat(waiter.getTargetSessionId()).isNull();
    }

    @Test
    void reset_returnsWaiterToIdle() {
        WaiterWorker waiter = new WaiterWorker("w1");
        waiter.assignOrderTask("c1");

        waiter.reset();

        assertThat(waiter.isIdle()).isTrue();
        assertThat(waiter.getTargetSessionId()).isNull();
    }

    @Test
    void arrivedAtClient_noopIfNotWalkingToClient() {
        WaiterWorker waiter = new WaiterWorker("w1");
        // IDLE → arrivedAtClient should do nothing
        waiter.arrivedAtClient();

        assertThat(waiter.getState()).isEqualTo(WaiterState.IDLE);
    }
}
