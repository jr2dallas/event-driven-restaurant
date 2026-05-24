package fr.jr2dallas.services;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.jr2dallas.generated.model.KitchenInstance;
import fr.jr2dallas.generated.model.KitchenState;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class ConsulService {

    private static final Logger log = LoggerFactory.getLogger(ConsulService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${spring.cloud.consul.host:localhost}")
    private String consulHost;

    @Value("${spring.cloud.consul.port:8500}")
    private int consulPort;

    public ConsulService(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
    }

    public List<KitchenInstance> getKitchenInstances() {
        ConsulHealthEntry[] entries = restTemplate.getForObject(
                consulUrl("/v1/health/service/kitchen-service"),
                ConsulHealthEntry[].class);

        if (entries == null) return List.of();

        return Arrays.stream(entries)
                .filter(this::isHealthy)
                .map(e -> resolveInstance(e.getService().getId()))
                .toList();
    }

    // ── Private helpers ────────────────────────────────────────

    private boolean isHealthy(ConsulHealthEntry entry) {
        return entry.getChecks().stream().allMatch(c -> "passing".equals(c.getStatus()));
    }

    private KitchenInstance resolveInstance(String instanceId) {
        KitchenInstance dto = new KitchenInstance().instanceId(instanceId);
        try {
            ConsulKvEntry[] kvEntries = restTemplate.getForObject(
                    consulUrl("/v1/kv/kitchen-state/" + instanceId),
                    ConsulKvEntry[].class);
            decodeKitchenKv(kvEntries).ifPresentOrElse(
                    kv -> dto.status(kv.getStatus()).waitingOrders(kv.getWaitingOrders()),
                    () -> dto.status(KitchenState.STARTING)
            );
        } catch (HttpClientErrorException.NotFound e) {
            // KV key not yet written by the kitchen instance — treat as starting
            dto.status(KitchenState.STARTING);
        } catch (Exception e) {
            log.warn("[Consul] Failed to resolve instance={}: {}", instanceId, e.getMessage());
            dto.status(KitchenState.UNREACHABLE);
        }
        return dto;
    }

    private Optional<KitchenInstance> decodeKitchenKv(ConsulKvEntry[] entries) {
        if (entries == null || entries.length == 0 || entries[0].getValue() == null)
            return Optional.empty();
        try {
            String json = new String(Base64.getDecoder().decode(entries[0].getValue()));
            return Optional.of(objectMapper.readValue(json, KitchenInstance.class));
        } catch (Exception e) {
            log.warn("[Consul] Failed to decode KV entry: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String consulUrl(String path) {
        return "http://" + consulHost + ":" + consulPort + path;
    }

    // ── Consul DTOs ────────────────────────────────────────────

    @Data static class ConsulKvEntry { @JsonProperty("Value") private String value; }

    @Data static class ConsulHealthEntry {
        @JsonProperty("Service") private ConsulServiceInfo service;
        @JsonProperty("Checks")  private List<ConsulCheckInfo> checks;
    }

    @Data static class ConsulServiceInfo { @JsonProperty("ID") private String id; }

    @Data static class ConsulCheckInfo    { @JsonProperty("Status") private String status; }
}
