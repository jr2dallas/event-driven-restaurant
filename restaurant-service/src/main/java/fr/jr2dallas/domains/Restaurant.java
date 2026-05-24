package fr.jr2dallas.domains;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

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

    /** Assigne le prochain client en file au premier siège libre */
    public synchronized Optional<SeatAssignment> seatNextClient() {
        if (waitingQueue.isEmpty()) return Optional.empty();

        return seats.stream()
                .filter(s -> !s.isOccupied())
                .findFirst()
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
