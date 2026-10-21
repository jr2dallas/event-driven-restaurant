package fr.jr2dallas.mappers;

import fr.jr2dallas.domains.Point;
import fr.jr2dallas.domains.RestaurantStateDto;
import fr.jr2dallas.domains.WaiterWorker;
import fr.jr2dallas.generated.model.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Component
public class RestaurantMapper {

    public RestaurantState fromRestaurantStateToRestaurantStateDto(RestaurantStateDto restaurantState, List<WaiterWorker> waiters) {
        Instant now = Instant.now();
        return new RestaurantState()
            .sessions(restaurantState.getSessions().stream().map(this::fromSessionToSessionDto).toList())
            .seats(restaurantState.getSeats().stream().map(this::fromSeatToSeatDto).toList())
            .waiters(waiters.stream().map(w -> fromWaiterToWaiterDto(w, now)).toList())
            .full(restaurantState.isFull())
            .availableSeats(restaurantState.getAvailableSeats());
    }

    private ClientSession fromSessionToSessionDto(fr.jr2dallas.domains.ClientSession session) {
        return new ClientSession()
                .id(session.getId())
                .state(SessionState.fromValue(session.getState().name()))
                .seatId(session.getSeatId())
                .tag(session.getTag())
                .orderPhase(OrderPhase.fromValue(session.getOrderPhase().name()));
    }

    private Seat fromSeatToSeatDto(fr.jr2dallas.domains.Seat seat) {
        return new Seat()
                .id(seat.getId())
                .clientId(seat.getClientId())
                .free(seat.isFree());
    }

    private Waiter fromWaiterToWaiterDto(WaiterWorker waiter, Instant now) {
        return new Waiter()
                .id(waiter.getId())
                .state(WaiterState.fromValue(waiter.getState().name()))
                .targetSessionId(waiter.getTargetSessionId())
                .targetSessionIds(waiter.getSessionIds())
                .targetTableId(waiter.getTableId())
                .dishes(waiter.getDishes())
                .idlePosition(toPosition(waiter.idlePosition(now)));
    }

    private static Position toPosition(Point p) {
        return new Position()
                .x(BigDecimal.valueOf(Math.round(p.x())))
                .y(BigDecimal.valueOf(Math.round(p.y())));
    }
}
