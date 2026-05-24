package fr.jr2dallas.domains;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestaurantTest {

    private Restaurant restaurant;

    @BeforeEach
    void setUp() {
        restaurant = new Restaurant("main", List.of(new Seat("s1"), new Seat("s2")));
    }

    @Test
    void seatNextClient_assignsClientToFirstFreeSeat() {
        restaurant.enqueueClient("c1");

        var result = restaurant.seatNextClient();

        assertThat(result).isPresent();
        assertThat(result.get().clientId()).isEqualTo("c1");
        assertThat(result.get().seatId()).isEqualTo("s1");
    }

    @Test
    void seatNextClient_returnsEmptyWhenQueueIsEmpty() {
        assertThat(restaurant.seatNextClient()).isEmpty();
    }

    @Test
    void seatNextClient_returnsEmptyWhenAllSeatsOccupied() {
        restaurant.enqueueClient("c1");
        restaurant.enqueueClient("c2");
        restaurant.enqueueClient("c3");
        restaurant.seatNextClient();
        restaurant.seatNextClient();

        var result = restaurant.seatNextClient();

        assertThat(result).isEmpty();
        assertThat(restaurant.getWaitingQueueClientIds()).containsExactly("c3");
    }

    @Test
    void seatNextClient_respectsFifoOrder() {
        restaurant.enqueueClient("c1");
        restaurant.enqueueClient("c2");

        var first  = restaurant.seatNextClient();
        var second = restaurant.seatNextClient();

        assertThat(first.get().clientId()).isEqualTo("c1");
        assertThat(second.get().clientId()).isEqualTo("c2");
    }

    @Test
    void isFull_trueWhenAllSeatsOccupied() {
        restaurant.enqueueClient("c1");
        restaurant.enqueueClient("c2");
        restaurant.seatNextClient();
        restaurant.seatNextClient();

        assertThat(restaurant.isFull()).isTrue();
    }

    @Test
    void isFull_falseWithAtLeastOneFreeSet() {
        restaurant.enqueueClient("c1");
        restaurant.seatNextClient();

        assertThat(restaurant.isFull()).isFalse();
    }

    @Test
    void availableSeats_decreasesAsClientsSeated() {
        assertThat(restaurant.availableSeats()).isEqualTo(2);

        restaurant.enqueueClient("c1");
        restaurant.seatNextClient();
        assertThat(restaurant.availableSeats()).isEqualTo(1);

        restaurant.enqueueClient("c2");
        restaurant.seatNextClient();
        assertThat(restaurant.availableSeats()).isEqualTo(0);
    }

    @Test
    void freeSeat_makesItAvailableAgain() {
        restaurant.enqueueClient("c1");
        var assignment = restaurant.seatNextClient().orElseThrow();

        restaurant.freeSeat(assignment.seatId());

        assertThat(restaurant.availableSeats()).isEqualTo(2);
    }

    @Test
    void freeSeatIfHeldBy_doesNotFreeIfWrongClient() {
        restaurant.enqueueClient("c1");
        var assignment = restaurant.seatNextClient().orElseThrow();

        restaurant.freeSeatIfHeldBy(assignment.seatId(), "wrong-client");

        assertThat(restaurant.availableSeats()).isEqualTo(1);
    }

    @Test
    void freeSeatIfHeldBy_freesIfCorrectClient() {
        restaurant.enqueueClient("c1");
        var assignment = restaurant.seatNextClient().orElseThrow();

        restaurant.freeSeatIfHeldBy(assignment.seatId(), "c1");

        assertThat(restaurant.availableSeats()).isEqualTo(2);
    }

    @Test
    void seat_throwsWhenOccupiedTwice() {
        Seat seat = new Seat("s1");
        seat.occupy("c1");

        assertThatThrownBy(() -> seat.occupy("c2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("s1");
    }
}
