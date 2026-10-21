package fr.jr2dallas.domains;

import java.time.Instant;
import java.util.UUID;

public class ClientSession {

    private final String id;
    private SessionState state;
    private String       seatId;
    private String       tableId;
    private Instant      lastUpdate;
    private String       tag; // ex: "tfc"

    // ── Order tracking ────────────────────────────────────────
    private OrderPhase orderPhase = OrderPhase.NONE;
    private UUID       orderId;      // current order — used to ignore duplicate "ready" messages
    private String     orderRoundId; // clients ordered in the same waiter visit share it
    private boolean    sendFailed;   // Kafka send failed — retried by the scheduler
    private int        resendCount;

    // ── Stage timestamps (metrics + timeouts) ─────────────────
    private final Instant createdAt;
    private Instant seatAssignedAt;
    private Instant seatedAt;
    private Instant orderTakenAt;
    private Instant orderSentAt;
    private Instant dishReadyAt;
    private Instant pickedUpAt;
    private Instant servedAt;

    public ClientSession(String id) {
        this.id         = id;
        this.state      = SessionState.QUEUING;
        this.createdAt  = Instant.now();
        this.lastUpdate = createdAt;
    }

    public ClientSession(String id, String tag) {
        this(id);
        this.tag = tag;
    }

    // ── Getters ───────────────────────────────────────────────

    public String       getId()           { return id; }
    public SessionState getState()        { return state; }
    public String       getSeatId()       { return seatId; }
    public String       getTableId()      { return tableId; }
    public Instant      getLastUpdate()   { return lastUpdate; }
    public String       getTag()          { return tag; }
    public OrderPhase   getOrderPhase()   { return orderPhase; }
    public UUID         getOrderId()      { return orderId; }
    public String       getOrderRoundId() { return orderRoundId; }
    public boolean      isSendFailed()    { return sendFailed; }
    public int          getResendCount()  { return resendCount; }
    public Instant      getCreatedAt()      { return createdAt; }
    public Instant      getSeatAssignedAt() { return seatAssignedAt; }
    public Instant      getSeatedAt()       { return seatedAt; }
    public Instant      getOrderTakenAt()   { return orderTakenAt; }
    public Instant      getOrderSentAt()    { return orderSentAt; }
    public Instant      getDishReadyAt()    { return dishReadyAt; }
    public Instant      getPickedUpAt()     { return pickedUpAt; }
    public Instant      getServedAt()       { return servedAt; }

    // ── Client transitions ────────────────────────────────────

    public void markAtEntrance() { transition(SessionState.AT_ENTRANCE); }

    public void walkingToSeat(String seatId) {
        walkingToSeat(seatId, null);
    }

    public void walkingToSeat(String seatId, String tableId) {
        this.seatId  = seatId;
        this.tableId = tableId;
        transition(SessionState.WALKING_TO_SEAT);
        this.seatAssignedAt = lastUpdate;
    }

    public void seatedAt(String seatId) {
        this.seatId = seatId;
        transition(SessionState.SEATED);
        this.seatedAt = lastUpdate;
    }

    /** Waiter finished writing the order: the slip now travels to the kitchen. */
    public void markOrdered() { markOrdered(null); }

    public void markOrdered(String roundId) {
        transition(SessionState.WAITING_ORDER);
        this.orderPhase   = OrderPhase.SLIP_IN_TRANSIT;
        this.orderRoundId = roundId;
        this.orderTakenAt = lastUpdate;
    }

    public void markEating() {
        transition(SessionState.EATING);
        this.orderPhase = OrderPhase.SERVED;
        this.servedAt   = lastUpdate;
    }

    public void markLeaving()   { transition(SessionState.LEAVING); }
    public void markDespawned() { transition(SessionState.DESPAWNED); }

    // ── Order transitions (do not touch lastUpdate: it times the visible state) ──

    /** The order left for the kitchen (first send or resend of the same orderId). */
    public void orderSent(UUID orderId) {
        if (this.orderId != null && this.orderId.equals(orderId)) resendCount++;
        this.orderId     = orderId;
        this.orderPhase  = OrderPhase.IN_KITCHEN;
        this.orderSentAt = Instant.now();
        this.sendFailed  = false;
    }

    public void sendFailed() { this.sendFailed = true; }

    public void dishReady() {
        this.orderPhase  = OrderPhase.READY_AT_PASS;
        this.dishReadyAt = Instant.now();
    }

    public void pickedUp() { this.pickedUpAt = Instant.now(); }

    private void transition(SessionState next) {
        this.state      = next;
        this.lastUpdate = Instant.now();
    }
}
