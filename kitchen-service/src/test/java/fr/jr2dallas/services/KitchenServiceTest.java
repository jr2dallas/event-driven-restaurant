package fr.jr2dallas.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KitchenServiceTest {

    @Mock RestTemplateBuilder         builder;
    @Mock RestTemplate                restTemplate;
    @Mock ConsulDiscoveryProperties   discoveryProperties;

    private KitchenService kitchenService;

    @BeforeEach
    void setUp() {
        when(builder.build()).thenReturn(restTemplate);
        when(discoveryProperties.getInstanceId()).thenReturn("kitchen-test-1");
        kitchenService = new KitchenService(
                builder, discoveryProperties, new ObjectMapper(), new SimpleMeterRegistry());
    }

    @Test
    void startCooking_transitionsToCooking() {
        kitchenService.startCooking("order-1");

        assertThat(kitchenService.getStatus()).isEqualTo("COOKING");
        assertThat(kitchenService.waitingOrdersCount()).isEqualTo(1);
    }

    @Test
    void finishCooking_transitionsToIdleWhenNoPendingOrders() {
        kitchenService.startCooking("order-1");
        kitchenService.finishCooking("order-1");

        assertThat(kitchenService.getStatus()).isEqualTo("IDLE");
        assertThat(kitchenService.waitingOrdersCount()).isEqualTo(0);
    }

    @Test
    void finishCooking_staysCookingWhenOtherOrdersActive() {
        kitchenService.startCooking("order-1");
        kitchenService.startCooking("order-2");

        kitchenService.finishCooking("order-1");

        assertThat(kitchenService.getStatus()).isEqualTo("COOKING");
        assertThat(kitchenService.waitingOrdersCount()).isEqualTo(1);
    }

    @Test
    void waitingOrdersCount_tracksMultipleOrders() {
        kitchenService.startCooking("order-1");
        kitchenService.startCooking("order-2");
        kitchenService.startCooking("order-3");

        assertThat(kitchenService.waitingOrdersCount()).isEqualTo(3);

        kitchenService.finishCooking("order-2");
        assertThat(kitchenService.waitingOrdersCount()).isEqualTo(2);
    }

    @Test
    void finishCooking_unknownOrder_doesNotCrash() {
        // Should not throw — idempotent removal
        kitchenService.finishCooking("non-existent-order");

        assertThat(kitchenService.waitingOrdersCount()).isEqualTo(0);
    }
}
