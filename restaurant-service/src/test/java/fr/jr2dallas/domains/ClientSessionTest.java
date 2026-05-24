package fr.jr2dallas.domains;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ClientSessionTest {

    @Test
    void newSession_startsQueuing() {
        ClientSession session = new ClientSession("c1");

        assertThat(session.getState()).isEqualTo(SessionState.QUEUING);
        assertThat(session.getSeatId()).isNull();
        assertThat(session.getLastUpdate()).isNotNull();
    }

    @Test
    void newSession_withTag_storesTag() {
        ClientSession session = new ClientSession("c1", "vip");

        assertThat(session.getTag()).isEqualTo("vip");
        assertThat(session.getState()).isEqualTo(SessionState.QUEUING);
    }

    @Test
    void markAtEntrance_transitionsFromQueuing() {
        ClientSession session = new ClientSession("c1");
        session.markAtEntrance();

        assertThat(session.getState()).isEqualTo(SessionState.AT_ENTRANCE);
    }

    @Test
    void walkingToSeat_setsSeatIdAndState() {
        ClientSession session = new ClientSession("c1");
        session.walkingToSeat("s1");

        assertThat(session.getState()).isEqualTo(SessionState.WALKING_TO_SEAT);
        assertThat(session.getSeatId()).isEqualTo("s1");
    }

    @Test
    void seatedAt_transitionsToSeated() {
        ClientSession session = new ClientSession("c1");
        session.walkingToSeat("s1");
        session.seatedAt("s1");

        assertThat(session.getState()).isEqualTo(SessionState.SEATED);
        assertThat(session.getSeatId()).isEqualTo("s1");
    }

    @Test
    void markOrdered_transitionsToWaitingOrder() {
        ClientSession session = new ClientSession("c1");
        session.markOrdered();

        assertThat(session.getState()).isEqualTo(SessionState.WAITING_ORDER);
    }

    @Test
    void markEating_transitionsToEating() {
        ClientSession session = new ClientSession("c1");
        session.markEating();

        assertThat(session.getState()).isEqualTo(SessionState.EATING);
    }

    @Test
    void markLeaving_transitionsToLeaving() {
        ClientSession session = new ClientSession("c1");
        session.markLeaving();

        assertThat(session.getState()).isEqualTo(SessionState.LEAVING);
    }

    @Test
    void markDespawned_transitionsToDespawned() {
        ClientSession session = new ClientSession("c1");
        session.markDespawned();

        assertThat(session.getState()).isEqualTo(SessionState.DESPAWNED);
    }

    @Test
    void lastUpdate_isRefreshedOnTransition() {
        ClientSession session = new ClientSession("c1");
        Instant before = session.getLastUpdate();

        session.markAtEntrance();

        assertThat(session.getLastUpdate()).isAfterOrEqualTo(before);
    }

    @Test
    void fullLifecycle_queuing_to_despawned() {
        ClientSession session = new ClientSession("c1");
        assertThat(session.getState()).isEqualTo(SessionState.QUEUING);

        session.markAtEntrance();
        assertThat(session.getState()).isEqualTo(SessionState.AT_ENTRANCE);

        session.walkingToSeat("s1");
        assertThat(session.getState()).isEqualTo(SessionState.WALKING_TO_SEAT);

        session.seatedAt("s1");
        assertThat(session.getState()).isEqualTo(SessionState.SEATED);

        session.markOrdered();
        assertThat(session.getState()).isEqualTo(SessionState.WAITING_ORDER);

        session.markEating();
        assertThat(session.getState()).isEqualTo(SessionState.EATING);

        session.markLeaving();
        assertThat(session.getState()).isEqualTo(SessionState.LEAVING);

        session.markDespawned();
        assertThat(session.getState()).isEqualTo(SessionState.DESPAWNED);
    }
}
