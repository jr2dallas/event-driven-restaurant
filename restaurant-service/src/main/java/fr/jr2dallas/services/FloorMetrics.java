package fr.jr2dallas.services;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where does a client's time go? One histogram per stage of the client journey
 * (restaurant_client_stage_seconds{stage=...}) plus counters for the safety nets.
 */
@Component
public class FloorMetrics {

    public enum Stage {
        QUEUE,         // spawned → seat assigned
        WALK_TO_SEAT,  // seat assigned → seated
        WAIT_ORDER,    // seated → order taken
        SLIP_TRANSIT,  // order taken → sent to the kitchen
        KITCHEN,       // sent → dish ready (includes the kitchen backlog)
        PASS_WAIT,     // dish ready → picked up by a waiter
        DELIVERY,      // picked up → served
        EATING,        // served → leaving
        SEAT_TOTAL     // seat assigned → leaving (how long a seat is blocked)
    }

    private final MeterRegistry registry;
    private final Map<Stage, Timer>    timers   = new ConcurrentHashMap<>();
    private final Map<String, Counter> timeouts = new ConcurrentHashMap<>();
    private final Counter resent;
    private final Counter duplicateDishes;

    public FloorMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Stage s : Stage.values()) {
            timers.put(s, Timer.builder("restaurant.client.stage")
                    .description("Time spent by clients in each stage of their visit")
                    .tag("stage", s.name().toLowerCase())
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(100))
                    .maximumExpectedValue(Duration.ofMinutes(10))
                    .register(registry));
        }
        this.resent = Counter.builder("restaurant.orders.resent")
                .description("Orders re-sent to the kitchen (send failure or lost order)")
                .register(registry);
        this.duplicateDishes = Counter.builder("restaurant.dishes.duplicate")
                .description("orders.ready messages ignored (duplicate or unknown order)")
                .register(registry);
    }

    public void record(Stage stage, Instant from, Instant to) {
        if (from == null || to == null || to.isBefore(from)) return;
        timers.get(stage).record(Duration.between(from, to));
    }

    public void timeout(String step) {
        timeouts.computeIfAbsent(step, s -> Counter.builder("restaurant.timeouts")
                .description("Steps auto-completed because the front stopped reporting")
                .tag("step", s)
                .register(registry)).increment();
    }

    public void resent()          { resent.increment(); }
    public void duplicateDish()   { duplicateDishes.increment(); }
}
