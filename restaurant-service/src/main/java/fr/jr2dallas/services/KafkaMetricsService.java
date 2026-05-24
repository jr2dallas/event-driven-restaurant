package fr.jr2dallas.services;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class KafkaMetricsService {

    private static final Logger log = LoggerFactory.getLogger(KafkaMetricsService.class);

    private final AdminClient admin;

    public KafkaMetricsService(KafkaAdmin kafkaAdmin) {
        this.admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }

    @PreDestroy
    public void close() { admin.close(); }

    // Returns { topicName → total lag } for the given consumer group.
    // Returns empty map on error (Kafka unreachable, group unknown, etc.)
    public Map<String, Long> consumerGroupLag(String groupId) {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed =
                    admin.listConsumerGroupOffsets(groupId)
                         .partitionsToOffsetAndMetadata()
                         .get();

            if (committed == null || committed.isEmpty()) return Map.of();

            Map<TopicPartition, OffsetSpec> endRequest = committed.keySet().stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));

            var endOffsets = admin.listOffsets(endRequest).all().get();

            return committed.entrySet().stream()
                    .collect(Collectors.groupingBy(
                            e -> e.getKey().topic(),
                            Collectors.summingLong(e -> {
                                var end = endOffsets.get(e.getKey());
                                if (end == null) return 0L;
                                return Math.max(0L, end.offset() - e.getValue().offset());
                            })
                    ));

        } catch (Exception e) {
            log.warn("[Metrics] Failed to fetch consumer group lag for group={}: {}", groupId, e.getMessage());
            return Map.of();
        }
    }
}
