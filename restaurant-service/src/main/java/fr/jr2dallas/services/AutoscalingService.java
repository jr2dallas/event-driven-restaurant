package fr.jr2dallas.services;

import fr.jr2dallas.config.RestaurantProperties;
import fr.jr2dallas.generated.model.KitchenInstance;
import fr.jr2dallas.generated.model.KitchenState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Hires and fires staff from the observed load, and rescues lost kitchen orders.
 *
 * Waiters: hire when a job has waited too long and nobody is free; fire when several
 * waiters stay idle with nothing to do.
 * Kitchens: the Kafka consumer lag of orders.in is the kitchen backlog. Spawn a kitchen when
 * more than one full round of burners is queued; remove one when kitchens stay mostly idle.
 *
 * Runs on the scheduling pool, never under the floor lock while calling Docker or Kafka.
 */
@Service
public class AutoscalingService {

    private static final Logger log = LoggerFactory.getLogger(AutoscalingService.class);

    // Waiters
    private static final long HIRE_AFTER_WAIT_SECONDS = 8;
    private static final int  IDLE_CHECKS_BEFORE_FIRE = 4;   // × 5 s
    // Kitchens
    private static final int      HIGH_CHECKS_BEFORE_SPAWN  = 2;  // × 10 s
    private static final int      LOW_CHECKS_BEFORE_DESPAWN = 6;  // × 10 s
    private static final double   LOW_LOAD_RATIO = 0.3;
    private static final Duration KITCHEN_COOLDOWN = Duration.ofSeconds(45); // a kitchen needs ~40 s to start

    private final WaiterScheduler       waiterScheduler;
    private final KitchenScalingService kitchenScaling;
    private final ConsulService         consul;
    private final KafkaMetricsService   kafkaMetrics;
    private final RestaurantProperties.AutoscalingProperties cfg;

    private volatile boolean waitersEnabled;
    private volatile boolean kitchensEnabled;

    private int     idleChecks = 0;
    private int     highChecks = 0;
    private int     lowChecks  = 0;
    private Instant lastKitchenChange = Instant.EPOCH;

    public AutoscalingService(WaiterScheduler waiterScheduler,
                              KitchenScalingService kitchenScaling,
                              ConsulService consul,
                              KafkaMetricsService kafkaMetrics,
                              RestaurantProperties props) {
        this.waiterScheduler = waiterScheduler;
        this.kitchenScaling  = kitchenScaling;
        this.consul          = consul;
        this.kafkaMetrics    = kafkaMetrics;
        this.cfg             = props.autoscaling();
        this.waitersEnabled  = cfg.waiters();
        this.kitchensEnabled = cfg.kitchens();
    }

    public boolean waitersEnabled()  { return waitersEnabled; }
    public boolean kitchensEnabled() { return kitchensEnabled; }

    public void configure(boolean waiters, boolean kitchens) {
        this.waitersEnabled  = waiters;
        this.kitchensEnabled = kitchens;
        log.info("[Autoscaling] waiters={} kitchens={}", waiters, kitchens);
    }

    // ── Waiters ───────────────────────────────────────────────

    @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
    public void scaleWaiters() {
        if (!waitersEnabled) { idleChecks = 0; return; }
        WaiterScheduler.Backlog b = waiterScheduler.backlog();

        if (b.oldestWaitSeconds() >= HIRE_AFTER_WAIT_SECONDS && b.freeWaiters() == 0 && b.waiters() < cfg.maxWaiters()) {
            log.info("[Autoscaling] hiring a waiter — {} job(s) waiting, oldest {}s", b.tasks(), b.oldestWaitSeconds());
            waiterScheduler.hireWaiter();
            idleChecks = 0;
            return;
        }

        boolean overstaffed = b.tasks() == 0 && b.freeWaiters() >= Math.max(2, Math.round(b.waiters() * 0.25));
        idleChecks = overstaffed ? idleChecks + 1 : 0;
        if (idleChecks >= IDLE_CHECKS_BEFORE_FIRE && b.waiters() > cfg.minWaiters()) {
            log.info("[Autoscaling] firing a waiter — {} idle out of {}", b.freeWaiters(), b.waiters());
            waiterScheduler.fireWaiter();
            idleChecks = 0;
        }
    }

    // ── Kitchens + rescue ─────────────────────────────────────

    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void scaleKitchensAndRescue() {
        long lag = kafkaMetrics.consumerGroupLag("kitchen-group").getOrDefault("orders.in", 0L);

        int resent = waiterScheduler.rescueLostOrders(lag);
        if (resent > 0) log.warn("[Rescue] {} lost order(s) re-sent to the kitchen", resent);

        if (!kitchensEnabled) { highChecks = 0; lowChecks = 0; return; }

        List<KitchenInstance> kitchens = consul.getKitchenInstances().stream()
                .filter(k -> k.getStatus() != KitchenState.UNREACHABLE)
                .toList();
        int count    = kitchens.size();
        int capacity = kitchens.stream().map(KitchenInstance::getCapacity).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        int cooking  = kitchens.stream().map(KitchenInstance::getWaitingOrders).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        if (capacity == 0) capacity = count; // older kitchens without capacity info

        // Offsets are committed after cooking, so the lag also counts the dishes on the burners
        long queued = Math.max(0, lag - cooking);
        highChecks = queued > capacity ? highChecks + 1 : 0;
        lowChecks  = lag <= capacity * LOW_LOAD_RATIO ? lowChecks + 1 : 0;

        if (Duration.between(lastKitchenChange, Instant.now()).compareTo(KITCHEN_COOLDOWN) < 0) return;

        if (highChecks >= HIGH_CHECKS_BEFORE_SPAWN && count < cfg.maxKitchens()) {
            log.info("[Autoscaling] spawning a kitchen — {} order(s) queued for {} burner(s)", queued, capacity);
            kitchenScaling.spawn();
            lastKitchenChange = Instant.now();
            highChecks = 0;
        } else if (lowChecks >= LOW_CHECKS_BEFORE_DESPAWN && count > cfg.minKitchens()) {
            log.info("[Autoscaling] removing a kitchen — {} dish(es) cooking on {} burner(s)", cooking, capacity);
            kitchenScaling.despawn();
            lastKitchenChange = Instant.now();
            lowChecks = 0;
        }
    }
}
