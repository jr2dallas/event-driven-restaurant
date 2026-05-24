package fr.jr2dallas.controllers;

import fr.jr2dallas.domains.RestaurantStateDto;
import fr.jr2dallas.domains.exceptions.ObjectNotFoundException;
import fr.jr2dallas.mappers.RestaurantMapper;
import fr.jr2dallas.services.RestaurantService;
import fr.jr2dallas.services.WaiterScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RestaurantController.class)
@Import(RestaurantMapper.class)
class RestaurantControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RestaurantService restaurantService;
    @MockitoBean WaiterScheduler   waiterScheduler;

    @Test
    void enqueueClient_returns201WithClientId() throws Exception {
        String clientId = UUID.randomUUID().toString();
        when(restaurantService.enqueueNewClient()).thenReturn(clientId);

        mvc.perform(post("/internal/restaurant/clients"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").value(clientId));
    }

    @Test
    void getRestaurantState_returns200WithPayload() throws Exception {
        when(restaurantService.getState())
                .thenReturn(new RestaurantStateDto(List.of(), List.of(), false, 5));
        when(waiterScheduler.getWaiters()).thenReturn(List.of());

        mvc.perform(get("/internal/restaurant/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.full").value(false))
                .andExpect(jsonPath("$.availableSeats").value(5))
                .andExpect(jsonPath("$.sessions").isArray())
                .andExpect(jsonPath("$.waiters").isArray());
    }

    @Test
    void updateClientState_atEntrance_invokesServiceAndReturns200() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(put("/internal/restaurant/clients/{id}/state", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state":"AT_ENTRANCE"}
                                """))
                .andExpect(status().isOk());

        verify(restaurantService).clientAtEntrance(id.toString());
    }

    @Test
    void updateClientState_unknownClient_returns404WithMessage() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new ObjectNotFoundException("No client found: " + id))
                .when(restaurantService).clientAtEntrance(id.toString());

        mvc.perform(put("/internal/restaurant/clients/{id}/state", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state":"AT_ENTRANCE"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No client found: " + id));
    }

    @Test
    void updateClientState_malformedJson_returns400() throws Exception {
        mvc.perform(put("/internal/restaurant/clients/{id}/state", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not valid json}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enqueueClientBatch_returns201WithSpawnedCount() throws Exception {
        mvc.perform(post("/internal/restaurant/clients/batch")
                        .param("count", "3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.spawned").value(3));

        verify(restaurantService, times(3)).enqueueNewClient(null);
    }
}
