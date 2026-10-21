package fr.jr2dallas.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.jr2dallas.generated.model.KitchenInstance;
import fr.jr2dallas.generated.model.KitchenState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class KitchenService {

    private static final Logger log = LoggerFactory.getLogger(KitchenService.class);

    private final RestTemplate restTemplate;
    private final ConsulDiscoveryProperties discoveryProperties;
    private final ObjectMapper objectMapper;

    @Value("${spring.cloud.consul.host:localhost}")
    private String consulHost;

    @Value("${spring.cloud.consul.port:8500}")
    private int consulPort;

    // volatile ensures visibility across Kafka consumer threads and HTTP threads
    private volatile KitchenState kitchenState = KitchenState.UNREACHABLE;

    private final ConcurrentHashMap<String, Long> waitingOrders = new ConcurrentHashMap<>();

    private final Counter ordersProcessedCounter;

    // Burners = dishes cooked in parallel (one Kafka listener thread per burner)
    @Value("${kitchen.burners:2}")
    private int burners = 2;

    public KitchenService(RestTemplateBuilder builder,
                          ConsulDiscoveryProperties discoveryProperties,
                          ObjectMapper objectMapper,
                          MeterRegistry meterRegistry) {
        this.restTemplate = builder.build();
        this.discoveryProperties = discoveryProperties;
        this.objectMapper = objectMapper;

        Gauge.builder("kitchen.orders.active", waitingOrders, ConcurrentHashMap::size)
                .description("Orders currently being cooked")
                .register(meterRegistry);

        Gauge.builder("kitchen.burners.capacity", this, k -> k.burners)
                .description("Number of burners (dishes cooked in parallel)")
                .register(meterRegistry);

        this.ordersProcessedCounter = Counter.builder("kitchen.orders.processed")
                .description("Total orders finished cooking")
                .register(meterRegistry);
    }

    @PostConstruct
    private void init() {
        kitchenState = KitchenState.STARTING;
        publishStateToConsul();
    }

    /** Ready to cook as soon as the application (and its Kafka listeners) are up. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onReady() {
        if (waitingOrders.isEmpty()) kitchenState = KitchenState.IDLE;
        publishStateToConsul();
    }

    /** Remove our KV entry so a stopped kitchen does not linger in the floor view. */
    @PreDestroy
    public void onShutdown() {
        try {
            restTemplate.delete(kvUrl());
        } catch (Exception e) {
            log.debug("[Kitchen] Unable to delete Consul KV entry: {}", e.getMessage());
        }
    }

    public int capacity() {
        return burners;
    }

    public String getStatus() {
        return kitchenState.getValue();
    }

    public int waitingOrdersCount() {
        return waitingOrders.size();
    }

    public synchronized void startCooking(String orderId) {
        waitingOrders.put(orderId, System.currentTimeMillis());
        kitchenState = KitchenState.COOKING;
        publishStateToConsul();
        log.info("[Kitchen] startCooking orderId={} activeOrders={}", orderId, waitingOrders.size());
    }

    public synchronized void finishCooking(String orderId) {
        waitingOrders.remove(orderId);
        // Stay COOKING if other orders are still in progress
        kitchenState = waitingOrders.isEmpty() ? KitchenState.IDLE : KitchenState.COOKING;
        publishStateToConsul();
        ordersProcessedCounter.increment();
        log.info("[Kitchen] finishCooking orderId={} activeOrders={}", orderId, waitingOrders.size());
    }

    private void publishStateToConsul() {
        try {
            String instanceId = discoveryProperties.getInstanceId();
            KitchenInstance kitchenInstance = new KitchenInstance()
                    .instanceId(instanceId)
                    .status(kitchenState)
                    .waitingOrders(waitingOrders.size())
                    .capacity(burners);

            restTemplate.put(
                    kvUrl(),
                    objectMapper.writeValueAsString(kitchenInstance)
            );
        } catch (Exception e) {
            log.warn("[Kitchen] Unable to publish state to Consul", e);
        }
    }

    private String kvUrl() {
        return "http://" + consulHost + ":" + consulPort + "/v1/kv/kitchen-state/" + discoveryProperties.getInstanceId();
    }
}
