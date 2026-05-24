package fr.jr2dallas.services;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class KitchenScalingService {

    private static final Logger log = LoggerFactory.getLogger(KitchenScalingService.class);

    @Value("${kitchen.scaling.compose-dir:../}")
    private String composeDir;

    @Value("${kitchen.scaling.compose-project:event-driven-restaurant}")
    private String composeProject;

    @Value("${kitchen.scaling.initial-count:1}")
    private int initialCount;

    private AtomicInteger desiredCount;

    @PostConstruct
    private void init() {
        desiredCount = new AtomicInteger(initialCount);
    }

    public synchronized int spawn() {
        int count = desiredCount.incrementAndGet();
        if (!scale(count)) desiredCount.decrementAndGet();
        return desiredCount.get();
    }

    public synchronized int despawn() {
        int before = desiredCount.get();
        int count = desiredCount.updateAndGet(n -> Math.max(0, n - 1));
        if (!scale(count)) desiredCount.set(before);
        return desiredCount.get();
    }

    public int currentDesired() {
        return desiredCount.get();
    }

    private boolean scale(int count) {
        List<String> cmd = List.of(
            "docker", "compose",
            "-p", composeProject,
            "up", "--scale", "kitchen-service=" + count,
            "-d", "--no-recreate"
        );
        log.info("[Kitchen scaling] → {} instance(s) | cmd: {}", count, String.join(" ", cmd));
        try {
            Process process = new ProcessBuilder(cmd)
                .directory(new File(composeDir))
                .redirectErrorStream(true)
                .start();

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[Kitchen scaling] docker compose timed out — desired count rolled back");
                return false;
            }
            if (process.exitValue() != 0) {
                String out = new String(process.getInputStream().readAllBytes());
                log.error("[Kitchen scaling] docker compose exited {} — desired count rolled back: {}", process.exitValue(), out);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("[Kitchen scaling] Failed to call docker compose — desired count rolled back", e);
            return false;
        }
    }
}
