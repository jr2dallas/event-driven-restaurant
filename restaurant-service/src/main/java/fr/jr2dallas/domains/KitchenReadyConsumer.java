package fr.jr2dallas.domains;

import fr.jr2dallas.generated.model.OrderReadyMessage;
import fr.jr2dallas.services.WaiterScheduler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KitchenReadyConsumer {

    private final WaiterScheduler waiterScheduler;

    public KitchenReadyConsumer(WaiterScheduler waiterScheduler) {
        this.waiterScheduler = waiterScheduler;
    }

    @KafkaListener(topics = "orders.ready", groupId = "restaurant-group")
    public void handleDishReady(OrderReadyMessage message) {
        waiterScheduler.onDishReady(message.getClientId().toString());
    }
}
