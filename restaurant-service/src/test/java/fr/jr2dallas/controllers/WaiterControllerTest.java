package fr.jr2dallas.controllers;

import fr.jr2dallas.services.WaiterScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WaiterController.class)
class WaiterControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean WaiterScheduler waiterScheduler;

    @Test
    void updateWaiterState_takingOrder_returns200() throws Exception {
        when(waiterScheduler.waiterExists("w1")).thenReturn(true);

        mvc.perform(put("/internal/restaurant/waiters/{id}/state", "w1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state":"TAKING_ORDER"}
                                """))
                .andExpect(status().isOk());

        verify(waiterScheduler).onWaiterArrivedForOrder("w1");
    }

    @Test
    void updateWaiterState_walkingToKitchen_returns200() throws Exception {
        when(waiterScheduler.waiterExists("w1")).thenReturn(true);

        mvc.perform(put("/internal/restaurant/waiters/{id}/state", "w1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state":"WALKING_TO_KITCHEN"}
                                """))
                .andExpect(status().isOk());

        verify(waiterScheduler).onOrderTaken("w1");
    }

    @Test
    void updateWaiterState_idle_returns200() throws Exception {
        when(waiterScheduler.waiterExists("w1")).thenReturn(true);

        mvc.perform(put("/internal/restaurant/waiters/{id}/state", "w1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state":"IDLE"}
                                """))
                .andExpect(status().isOk());

        verify(waiterScheduler).transitionToIdle("w1");
    }

    @Test
    void updateWaiterState_unknownWaiter_returns404() throws Exception {
        when(waiterScheduler.waiterExists("unknown")).thenReturn(false);

        mvc.perform(put("/internal/restaurant/waiters/{id}/state", "unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state":"IDLE"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateWaiterCount_returns204() throws Exception {
        mvc.perform(put("/internal/restaurant/waiters/count")
                        .param("count", "4"))
                .andExpect(status().isNoContent());

        verify(waiterScheduler).setWaiterCount(4);
    }

    @Test
    void hireWaiter_returns204() throws Exception {
        mvc.perform(post("/internal/restaurant/waiters"))
                .andExpect(status().isNoContent());

        verify(waiterScheduler).hireWaiter();
    }

    @Test
    void fireWaiter_returns204() throws Exception {
        mvc.perform(delete("/internal/restaurant/waiters"))
                .andExpect(status().isNoContent());

        verify(waiterScheduler).fireWaiter();
    }
}
