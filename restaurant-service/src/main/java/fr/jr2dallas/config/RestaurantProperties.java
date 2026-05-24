package fr.jr2dallas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "restaurant")
public record RestaurantProperties(
        String defaultDish,
        KafkaProperties kafka,
        SchedulerProperties scheduler
) {
    public record KafkaProperties(int ordersPartitions) {}

    public record SchedulerProperties(
            long staleDishThresholdSeconds,
            long eatingTimeoutSeconds,
            long leavingCleanupTimeoutSeconds,
            long rescueThresholdSeconds
    ) {}
}
