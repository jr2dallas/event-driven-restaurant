package fr.jr2dallas.services;

import fr.jr2dallas.generated.model.OrderMessage;
import fr.jr2dallas.generated.model.OrderReadyMessage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class OrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);

    @Value("${kitchen.cooking.duration-ms:5000}")
    private long cookingDurationMs;

    private final KitchenService kitchenService;
    private final KafkaTemplate<String, OrderReadyMessage> kafkaTemplate;

    // Dedicated cooking pool — does not interfere with Kafka consumer threads
    private final ScheduledExecutorService cookingScheduler =
            Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2);

    public OrderConsumer(KitchenService kitchenService,
                         KafkaTemplate<String, OrderReadyMessage> kafkaTemplate) {
        this.kitchenService = kitchenService;
        this.kafkaTemplate  = kafkaTemplate;
    }

    @KafkaListener(topics = "orders.in", groupId = "kitchen-group", concurrency = "3")
    public void handleOrder(OrderMessage order, Acknowledgment ack) {
        String clientId = order.getClientId().toString();
        String orderId  = order.getOrderId().toString();
        log.info("[Kitchen] Order received orderId={} clientId={} seat={}", orderId, clientId, order.getSeatId());

        kitchenService.startCooking(clientId);

        // Schedule end of cooking without blocking the Kafka consumer thread
        cookingScheduler.schedule(() -> {
            kitchenService.finishCooking(clientId);
            kafkaTemplate.send(
                    "orders.ready",
                    clientId,
                    new OrderReadyMessage().orderId(order.getOrderId()).clientId(order.getClientId())
            ).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("[Kitchen] Failed to send order ready for orderId={}: {}", orderId, ex.getMessage(), ex);
                } else {
                    log.info("[Kitchen] Order ready sent for orderId={}", orderId);
                }
            });
        }, cookingDurationMs, TimeUnit.MILLISECONDS);

        // Acknowledge offset after scheduling — cooking itself is async
        ack.acknowledge();
    }

    @PreDestroy
    public void shutdown() {
        cookingScheduler.shutdown();
        try {
            if (!cookingScheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("[Kitchen] Cooking scheduler did not terminate in time — forcing shutdown");
                cookingScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cookingScheduler.shutdownNow();
        }
    }
}
