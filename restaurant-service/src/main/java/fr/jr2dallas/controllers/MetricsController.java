package fr.jr2dallas.controllers;

import fr.jr2dallas.generated.api.MetricsApi;
import fr.jr2dallas.services.KafkaMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class MetricsController implements MetricsApi {

    private final KafkaMetricsService kafkaMetricsService;

    public MetricsController(KafkaMetricsService kafkaMetricsService) {
        this.kafkaMetricsService = kafkaMetricsService;
    }

    @Override
    public ResponseEntity<Map<String, Long>> getKafkaMetrics() {
        Map<String, Long> merged = new HashMap<>();
        merged.putAll(kafkaMetricsService.consumerGroupLag("kitchen-group"));
        merged.putAll(kafkaMetricsService.consumerGroupLag("restaurant-group"));
        return ResponseEntity.ok(merged);
    }
}
