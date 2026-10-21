package fr.jr2dallas.domains;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class Restaurant {
    private final String     id;
    private final List<Seat> seats;
    private final Deque<String> waitingQueue = new ArrayDeque<>();

    public Restaurant(String id, List<Seat> seats) {
        this.id    = id;
        this.seats = seats;
    }

    public String getId()        { return id; }
    public List<Seat> getSeats() { return seats; }

    public synchronized void enqueueClient(String clientId) {
        waitingQueue.addLast(clientId);
    }

    public synchronized List<String> getWaitingQueueClientIds() {
        return List.copyOf(waitingQueue);
    }

    public synchronized boolean isFull() {
        return seats.stream().allMatch(Seat::isOccupied);
    }

    public synchronized long availableSeats() {
        return seats.stream().filter(s -> !s.isOccupied()).count();
    }

    /** Gives a free seat (picked by {@code chooser}) to a client, whatever the internal queue. */
    public synchronized Optional<SeatAssignment> assignSeat(String clientId, Function<List<Seat>, Optional<Seat>> chooser) {
        List<Seat> free = seats.stream().filter(Seat::isFree).toList();
        if (free.isEmpty()) return Optional.empty();
        return chooser.apply(free).map(seat -> {
            seat.occupy(clientId);
            waitingQueue.remove(clientId);
            return new SeatAssignment(clientId, seat.getId());
        });
    }

    /** Assigns the next queued client to the first free seat */
    public synchronized Optional<SeatAssignment> seatNextClient() {
        return seatNextClient(free -> free.stream().findFirst());
    }

    /** Assigns the next queued client to the seat picked by {@code chooser} among the free seats */
    public synchronized Optional<SeatAssignment> seatNextClient(Function<List<Seat>, Optional<Seat>> chooser) {
        if (waitingQueue.isEmpty()) return Optional.empty();
        List<Seat> free = seats.stream().filter(Seat::isFree).toList();
        if (free.isEmpty()) return Optional.empty();

        return chooser.apply(free)
                .map(seat -> {
                    String clientId = waitingQueue.pollFirst();
                    seat.occupy(clientId);
                    return new SeatAssignment(clientId, seat.getId());
                });
    }

    public synchronized void freeSeat(String seatId) {
        seats.stream()
                .filter(s -> s.getId().equals(seatId))
                .findFirst()
                .ifPresent(Seat::free);
    }

    public synchronized void freeSeatIfHeldBy(String seatId, String clientId) {
        seats.stream()
                .filter(s -> s.getId().equals(seatId))
                .findFirst()
                .ifPresent(s -> s.freeIfHeldBy(clientId));
    }

    public record SeatAssignment(String clientId, String seatId) {}
}
