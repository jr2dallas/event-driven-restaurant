package fr.jr2dallas.domains;

/**
 * Progress of a client's order. Kept apart from {@link SessionState} (which drives the
 * client animation) so the backend can tell "slip still in the waiter's pocket" from
 * "dish on the burners" — both are WAITING_ORDER for the front.
 */
public enum OrderPhase {
    NONE,             // not ordered yet
    SLIP_IN_TRANSIT,  // order taken, waiter walking to the kitchen
    IN_KITCHEN,       // sent to Kafka, waiting for orders.ready
    READY_AT_PASS,    // dish ready, waiting for a waiter to pick it up
    SERVED            // delivered
}
