package fr.jr2dallas.domains;

import java.time.Instant;

public class ClientSession {

    private final String id;
    private SessionState state;
    private String       seatId;
    private Instant      lastUpdate;
    private String       tag; // ex: "tfc"

    public ClientSession(String id) {
        this.id         = id;
        this.state      = SessionState.QUEUING;
        this.lastUpdate = Instant.now();
    }

    public ClientSession(String id, String tag) {
        this(id);
        this.tag = tag;
    }

    // ── Getters ───────────────────────────────────────────────
    public String       getId()         { return id; }
    public SessionState getState()      { return state; }
    public String       getSeatId()     { return seatId; }
    public Instant      getLastUpdate() { return lastUpdate; }
    public String       getTag()        { return tag; }

    // ── Transitions ───────────────────────────────────────────
    public void seatedAt(String seatId) {
        this.seatId = seatId;
        transition(SessionState.SEATED);
    }

    public void markAtEntrance() { transition(SessionState.AT_ENTRANCE); }
    public void markOrdered()    { transition(SessionState.WAITING_ORDER); }
    public void markEating()   { transition(SessionState.EATING); }
    public void markLeaving()  { transition(SessionState.LEAVING); }
    public void walkingToSeat(String seatId) {
        this.seatId = seatId;
        this.state = SessionState.WALKING_TO_SEAT;
        this.lastUpdate = Instant.now();
    }

    private void transition(SessionState next) {
        this.state      = next;
        this.lastUpdate = Instant.now();
    }

    public void markDespawned() { transition(SessionState.DESPAWNED); }
}
