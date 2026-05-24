package fr.jr2dallas.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.jr2dallas.generated.model.KitchenInstance;
import fr.jr2dallas.generated.model.KitchenState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties;
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

        this.ordersProcessedCounter = Counter.builder("kitchen.orders.processed")
                .description("Total orders finished cooking")
                .register(meterRegistry);
    }

    @PostConstruct
    private void init() {
        kitchenState = KitchenState.STARTING;
        publishStateToConsul();
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
                    .waitingOrders(waitingOrders.size());

            restTemplate.put(
                    "http://" + consulHost + ":" + consulPort + "/v1/kv/kitchen-state/" + instanceId,
                    objectMapper.writeValueAsString(kitchenInstance)
            );
        } catch (Exception e) {
            log.warn("[Kitchen] Unable to publish state to Consul", e);
        }
    }
}
