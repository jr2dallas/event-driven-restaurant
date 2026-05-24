package fr.jr2dallas.domains;

import java.util.List;

public class RestaurantStateDto {

    private final List<ClientSession> sessions;
    private final List<Seat> seats;
    private final boolean isFull;
    private final int availableSeats;

    public RestaurantStateDto(
            List<ClientSession> sessions,
            List<Seat> seats,
            boolean isFull,
            int availableSeats
    ) {
        this.sessions = sessions;
        this.seats = seats;
        this.isFull = isFull;
        this.availableSeats = availableSeats;
    }

    public List<ClientSession> getSessions() {
        return sessions;
    }

    public List<Seat> getSeats() {
        return seats;
    }

    public boolean isFull() {
        return isFull;
    }

    public int getAvailableSeats() {
        return availableSeats;
    }
}