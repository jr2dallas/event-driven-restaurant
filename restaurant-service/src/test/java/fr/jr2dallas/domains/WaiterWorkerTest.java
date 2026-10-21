package fr.jr2dallas.domains;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WaiterWorkerTest {

    private static final Point HOME  = new Point(10, 10);
    private static final Point PASS  = new Point(900, 450);
    private static final Point TABLE = new Point(300, 300);

    private WaiterWorker waiter() {
        return new WaiterWorker("w1", 0, HOME);
    }

    @Test
    void newWaiter_isIdleAtHomePost() {
        WaiterWorker w = waiter();
        Instant now = Instant.now();

        assertThat(w.isIdle()).isTrue();
        assertThat(w.getTargetSessionId()).isNull();
        assertThat(w.idlePosition(now)).isEqualTo(HOME);
        assertThat(w.estimatedPosition(now)).isEqualTo(HOME);
    }

    @Test
    void orderRound_coversEveryClientOfTheTable() {
        WaiterWorker w = waiter();
        w.assignOrderRound("t1", List.of("c1", "c2"), TABLE, Instant.now());
        w.addToRound("c3");
        w.addToRound("c3"); // no duplicates

        assertThat(w.getState()).isEqualTo(WaiterState.WALKING_TO_CLIENT);
        assertThat(w.getTableId()).isEqualTo("t1");
        assertThat(w.getSessionIds()).containsExactly("c1", "c2", "c3");
        assertThat(w.getTargetSessionId()).isEqualTo("c1");
        assertThat(w.getDishes()).isZero();
    }

    @Test
    void fullOrderCycle_endsWaitingAtThePass() {
        WaiterWorker w = waiter();
        Instant now = Instant.now();
        w.assignOrderRound("t1", List.of("c1"), TABLE, now);

        assertThat(w.arrivedAtClient(now)).isTrue();
        assertThat(w.getState()).isEqualTo(WaiterState.TAKING_ORDER);
        assertThat(w.estimatedPosition(now)).isEqualTo(TABLE);

        assertThat(w.orderTaken(now)).isTrue();
        assertThat(w.getState()).isEqualTo(WaiterState.WALKING_TO_KITCHEN);

        w.droppedAtKitchen(PASS, now.plusSeconds(5), now);
        assertThat(w.isIdle()).isTrue();
        assertThat(w.getSessionIds()).isEmpty();
        assertThat(w.isWaitingAtPass(now)).isTrue();
        assertThat(w.getPassReservedTable()).isEqualTo("t1");
        assertThat(w.idlePosition(now)).isEqualTo(PASS);

        // Once the pass wait is over the waiter goes back to its post
        Instant later = now.plusSeconds(6);
        assertThat(w.isWaitingAtPass(later)).isFalse();
        assertThat(w.idlePosition(later)).isEqualTo(HOME);
    }

    @Test
    void deliveryRound_carriesOnePlatePerClient() {
        WaiterWorker w = waiter();
        Instant now = Instant.now();
        w.assignDeliveryRound("t1", List.of("c1", "c2"), TABLE, now);

        assertThat(w.getState()).isEqualTo(WaiterState.DELIVERING);
        assertThat(w.getDishes()).isEqualTo(2);

        w.delivered(now);
        assertThat(w.isIdle()).isTrue();
        assertThat(w.getDishes()).isZero();
        assertThat(w.getTargetSessionId()).isNull();
    }

    @Test
    void transitions_areIgnoredFromTheWrongState() {
        WaiterWorker w = waiter();
        Instant now = Instant.now();

        assertThat(w.arrivedAtClient(now)).isFalse();
        assertThat(w.orderTaken(now)).isFalse();
        assertThat(w.isIdle()).isTrue();
    }

    @Test
    void removeFromRound_reportsWhenTheRoundIsEmpty() {
        WaiterWorker w = waiter();
        w.assignOrderRound("t1", List.of("c1", "c2"), TABLE, Instant.now());

        assertThat(w.removeFromRound(List.of("c1"))).isFalse();
        assertThat(w.getTargetSessionId()).isEqualTo("c2");
        assertThat(w.removeFromRound(List.of("c2"))).isTrue();
    }

    @Test
    void reset_returnsWaiterToIdle() {
        WaiterWorker w = waiter();
        w.assignOrderRound("t1", List.of("c1"), TABLE, Instant.now());
        w.reset();

        assertThat(w.isIdle()).isTrue();
        assertThat(w.getTableId()).isNull();
        assertThat(w.getSessionIds()).isEmpty();
    }
}
