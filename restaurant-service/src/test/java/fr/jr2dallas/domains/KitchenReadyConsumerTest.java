package fr.jr2dallas.domains;

import fr.jr2dallas.generated.model.OrderReadyMessage;
import fr.jr2dallas.services.WaiterScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KitchenReadyConsumerTest {

    @Mock WaiterScheduler waiterScheduler;

    @InjectMocks KitchenReadyConsumer consumer;

    @Test
    void handleDishReady_callsOnDishReadyWithCorrectClientId() {
        UUID clientId = UUID.randomUUID();
        OrderReadyMessage message = new OrderReadyMessage()
                .orderId(UUID.randomUUID())
                .clientId(clientId);

        consumer.handleDishReady(message);

        verify(waiterScheduler).onDishReady(clientId.toString());
    }

    @Test
    void handleDishReady_propagatesClientIdExactly() {
        UUID clientId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        OrderReadyMessage message = new OrderReadyMessage()
                .orderId(UUID.randomUUID())
                .clientId(clientId);

        consumer.handleDishReady(message);

        verify(waiterScheduler).onDishReady("550e8400-e29b-41d4-a716-446655440000");
    }
}
