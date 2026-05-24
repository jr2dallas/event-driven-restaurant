package fr.jr2dallas.mappers;

import fr.jr2dallas.domains.RestaurantStateDto;
import fr.jr2dallas.domains.WaiterWorker;
import fr.jr2dallas.generated.model.*;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RestaurantMapper {
    public RestaurantState fromRestaurantStateToRestaurantStateDto(RestaurantStateDto restaurantState, List<WaiterWorker> waiters) {
        return new RestaurantState()
            .sessions(fromSessionsToSessionsDto(restaurantState.getSessions()))
            .seats(fromSeatsToSeatsDto(restaurantState.getSeats()))
            .waiters(fromWaitersToWaitersDto(waiters))
            .full(restaurantState.isFull())
            .availableSeats(restaurantState.getAvailableSeats());
    }

    private List<ClientSession> fromSessionsToSessionsDto(List<fr.jr2dallas.domains.ClientSession> sessions) {
        return sessions.stream()
                .map(this::fromSessionToSessionDto)
                .toList();
    }

    private ClientSession fromSessionToSessionDto(fr.jr2dallas.domains.ClientSession session) {
        return new ClientSession()
                .id(session.getId())
                .state(fromSessionStateToSessionStateDto(session.getState()))
                .seatId(session.getSeatId())
                .tag(session.getTag());
    }

    private SessionState fromSessionStateToSessionStateDto(fr.jr2dallas.domains.SessionState sessionState){
        return SessionState.fromValue(sessionState.name());
    }

    private List<Seat> fromSeatsToSeatsDto(List<fr.jr2dallas.domains.Seat> seats) {
        return seats.stream()
                .map(this::fromSeatToSeatDto)
                .toList();
    }

    private Seat fromSeatToSeatDto(fr.jr2dallas.domains.Seat seat) {
        return new Seat()
                .id(seat.getId())
                .clientId(seat.getClientId())
                .free(seat.isFree());
    }

    private List<Waiter> fromWaitersToWaitersDto(List<fr.jr2dallas.domains.WaiterWorker> waiters) {
        return waiters.stream()
                .map(this::fromWaiterToWaiterDto)
                .toList();
    }

    private Waiter fromWaiterToWaiterDto(fr.jr2dallas.domains.WaiterWorker waiter) {
        return new Waiter()
                .id(waiter.getId())
                .state(fromWaiterStateToWaiterStateDto(waiter.getState()))
                .targetSessionId(waiter.getTargetSessionId());
    }

    private WaiterState fromWaiterStateToWaiterStateDto(fr.jr2dallas.domains.WaiterState waiterState){
        return WaiterState.fromValue(waiterState.name());
    }
}
