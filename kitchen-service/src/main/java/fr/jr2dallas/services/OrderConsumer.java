package fr.jr2dallas.services;

import fr.jr2dallas.generated.model.OrderMessage;
import fr.jr2dallas.generated.model.OrderReadyMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * One listener thread = one burner.
 *
 * The listener cooks synchronously, so a kitchen never holds more orders than it has burners:
 * extra orders stay in Kafka (the consumer lag is the kitchen backlog) and are picked up by
 * whichever kitchen frees a burner first. Hiring a kitchen therefore adds real throughput.
 *
 * The offset is committed only after the "ready" message is acknowledged by Kafka, so an order
 * is never lost when a kitchen is stopped mid-cooking — it is redelivered to another kitchen
 * (at-least-once; restaurant-service ignores duplicate "ready" messages by orderId).
 */
@Component
public class OrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);

    @Value("${kitchen.cooking.duration-ms:5000}")
    private long cookingDurationMs;

    @Value("${kitchen.ready-send-timeout-ms:10000}")
    private long readySendTimeoutMs;

    private final KitchenService kitchenService;
    private final KafkaTemplate<String, OrderReadyMessage> kafkaTemplate;

    public OrderConsumer(KitchenService kitchenService,
                         KafkaTemplate<String, OrderReadyMessage> kafkaTemplate) {
        this.kitchenService = kitchenService;
        this.kafkaTemplate  = kafkaTemplate;
    }

    @KafkaListener(topics = "orders.in", groupId = "kitchen-group", concurrency = "${kitchen.burners:2}")
    public void handleOrder(OrderMessage order, Acknowledgment ack) throws Exception {
        String clientId = order.getClientId().toString();
        String orderId  = order.getOrderId().toString();
        log.info("[Kitchen] Order received orderId={} clientId={} seat={}", orderId, clientId, order.getSeatId());

        kitchenService.startCooking(orderId);
        try {
            Thread.sleep(cookingDurationMs);
        } finally {
            kitchenService.finishCooking(orderId);
        }

        // Block until Kafka has the "ready" message: if this fails, the listener throws,
        // the offset is not committed and the order is cooked again.
        kafkaTemplate.send(
                "orders.ready",
                clientId,
                new OrderReadyMessage().orderId(order.getOrderId()).clientId(order.getClientId())
        ).get(readySendTimeoutMs, TimeUnit.MILLISECONDS);
        log.info("[Kitchen] Order ready sent for orderId={}", orderId);

        ack.acknowledge();
    }
}
