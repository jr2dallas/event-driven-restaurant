package fr.jr2dallas.domains;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A waiter works in "rounds": one visit to one table, either to take the orders of every
 * seated client there, or to deliver a tray of dishes to them.
 *
 * Not thread-safe on its own — every access goes through {@link fr.jr2dallas.services.FloorLock}.
 */
public class WaiterWorker {

    private final String id;
    private final int    homePostIndex;
    private final Point  homePost;

    private WaiterState state = WaiterState.IDLE;
    private Instant     stateSince = Instant.now();
    private Point       position;   // last known position (estimate — the front animates)

    // Current round
    private String             tableId;
    private final List<String> sessionIds = new ArrayList<>();
    private Point              roundTarget;

    // After dropping an order slip the waiter may wait at the pass for that table's dishes
    private String  passReservedTable;
    private Instant passWaitUntil;
    private Point   passPosition;

    private boolean pendingRemoval = false;

    public WaiterWorker(String id) {
        this(id, 0, new Point(0, 0));
    }

    public WaiterWorker(String id, int homePostIndex, Point homePost) {
        this.id            = id;
        this.homePostIndex = homePostIndex;
        this.homePost      = homePost;
        this.position      = homePost;
    }

    // ── Getters ───────────────────────────────────────────────

    public String       getId()             { return id; }
    public WaiterState  getState()          { return state; }
    public Instant      getStateSince()     { return stateSince; }
    public boolean      isIdle()            { return state == WaiterState.IDLE; }
    public boolean      isPendingRemoval()  { return pendingRemoval; }
    public int          getHomePostIndex()  { return homePostIndex; }
    public Point        getHomePost()       { return homePost; }
    public String       getTableId()        { return tableId; }
    public List<String> getSessionIds()     { return List.copyOf(sessionIds); }
    public String       getPassReservedTable() { return passReservedTable; }

    /** Primary client of the round — the front walks to this client's seat. */
    public String getTargetSessionId() {
        return sessionIds.isEmpty() ? null : sessionIds.get(0);
    }

    /** Plates carried (only while delivering). */
    public int getDishes() {
        return state == WaiterState.DELIVERING ? sessionIds.size() : 0;
    }

    public void markForRemoval() { this.pendingRemoval = true; }

    public boolean isWaitingAtPass(Instant now) {
        return isIdle() && passReservedTable != null && passWaitUntil != null && now.isBefore(passWaitUntil);
    }

    /** Where the waiter stands while idle: at the pass during a pass wait, else at the home post. */
    public Point idlePosition(Instant now) {
        return isWaitingAtPass(now) ? passPosition : homePost;
    }

    /**
     * Best guess of where the waiter is, used to pick the closest waiter for a job.
     * An idle waiter is assumed to be at (or walking to) its idle position.
     */
    public Point estimatedPosition(Instant now) {
        return isIdle() ? idlePosition(now) : position;
    }

    // ── Round assignment (scheduler) ──────────────────────────

    public void assignOrderRound(String tableId, Collection<String> sessions, Point target, Instant now) {
        startRound(WaiterState.WALKING_TO_CLIENT, tableId, sessions, target, now);
    }

    public void assignDeliveryRound(String tableId, Collection<String> sessions, Point target, Instant now) {
        startRound(WaiterState.DELIVERING, tableId, sessions, target, now);
    }

    /** A client sat down at the table while the waiter was on the way: take their order too. */
    public void addToRound(String sessionId) {
        if (!sessionIds.contains(sessionId)) sessionIds.add(sessionId);
    }

    /** Drop clients that no longer belong to the round. Returns true when the round became empty. */
    public boolean removeFromRound(Collection<String> sessions) {
        sessionIds.removeAll(sessions);
        return sessionIds.isEmpty();
    }

    // ── Transitions triggered by the front (or by a timeout) ──

    /** WALKING_TO_CLIENT → TAKING_ORDER */
    public boolean arrivedAtClient(Instant now) {
        if (state != WaiterState.WALKING_TO_CLIENT) return false;
        position = roundTarget;
        setState(WaiterState.TAKING_ORDER, now);
        return true;
    }

    /** TAKING_ORDER → WALKING_TO_KITCHEN */
    public boolean orderTaken(Instant now) {
        if (state != WaiterState.TAKING_ORDER) return false;
        setState(WaiterState.WALKING_TO_KITCHEN, now);
        return true;
    }

    /** WALKING_TO_KITCHEN → IDLE at the pass, optionally waiting there for this table's dishes. */
    public void droppedAtKitchen(Point pass, Instant waitUntil, Instant now) {
        String table = tableId;
        clearRound();
        position          = pass;
        passPosition      = pass;
        passReservedTable = waitUntil != null && waitUntil.isAfter(now) ? table : null;
        passWaitUntil     = passReservedTable != null ? waitUntil : null;
        setState(WaiterState.IDLE, now);
    }

    /** DELIVERING → IDLE next to the table. */
    public void delivered(Instant now) {
        position = roundTarget != null ? roundTarget : position;
        clearRound();
        setState(WaiterState.IDLE, now);
    }

    public void reset() { reset(Instant.now()); }

    public void reset(Instant now) {
        clearRound();
        setState(WaiterState.IDLE, now);
    }

    // ── Internals ─────────────────────────────────────────────

    private void startRound(WaiterState next, String tableId, Collection<String> sessions, Point target, Instant now) {
        this.tableId     = tableId;
        this.roundTarget = target;
        this.sessionIds.clear();
        this.sessionIds.addAll(sessions);
        this.passReservedTable = null;
        this.passWaitUntil     = null;
        setState(next, now);
    }

    private void clearRound() {
        tableId     = null;
        roundTarget = null;
        sessionIds.clear();
        passReservedTable = null;
        passWaitUntil     = null;
    }

    private void setState(WaiterState next, Instant now) {
        this.state      = next;
        this.stateSince = now;
    }
}
