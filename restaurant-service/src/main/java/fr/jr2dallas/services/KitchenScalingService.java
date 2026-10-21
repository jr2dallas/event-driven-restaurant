package fr.jr2dallas.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class KitchenScalingService {

    private static final Logger log = LoggerFactory.getLogger(KitchenScalingService.class);

    @Value("${kitchen.scaling.compose-dir:../}")
    private String composeDir;

    @Value("${kitchen.scaling.compose-project:event-driven-restaurant}")
    private String composeProject;

    @Value("${kitchen.scaling.initial-count:1}")
    private int initialCount;

    // Unknown until the first scaling call: read from Docker (compose may start several
    // replicas, and the count survives a restaurant-service restart)
    private Integer desiredCount;

    public synchronized int spawn() {
        int current = currentDesired();
        if (scale(current + 1)) desiredCount = current + 1;
        return currentDesired();
    }

    public synchronized int despawn() {
        int current = currentDesired();
        int next = Math.max(0, current - 1);
        if (next != current && scale(next)) desiredCount = next;
        return currentDesired();
    }

    public synchronized int currentDesired() {
        if (desiredCount == null) {
            desiredCount = runningContainers().orElse(initialCount);
            log.info("[Kitchen scaling] {} kitchen container(s) currently running", desiredCount);
        }
        return desiredCount;
    }

    private Optional<Integer> runningContainers() {
        List<String> out = run(List.of(
                "docker", "compose", "-p", composeProject,
                "ps", "-q", "--status", "running", "kitchen-service"));
        if (out == null) return Optional.empty();
        return Optional.of((int) out.stream().filter(l -> !l.isBlank()).count());
    }

    private boolean scale(int count) {
        List<String> cmd = List.of(
            "docker", "compose",
            "-p", composeProject,
            "up", "--scale", "kitchen-service=" + count,
            "-d", "--no-recreate", "--no-deps", "kitchen-service"
        );
        log.info("[Kitchen scaling] → {} instance(s) | cmd: {}", count, String.join(" ", cmd));
        return run(cmd) != null;
    }

    /** Runs a docker command; returns its output lines, or null on failure / timeout. */
    private List<String> run(List<String> cmd) {
        try {
            Process process = new ProcessBuilder(cmd)
                .directory(new File(composeDir))
                .redirectErrorStream(true)
                .start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[Kitchen scaling] '{}' timed out", String.join(" ", cmd));
                return null;
            }
            String out = new String(process.getInputStream().readAllBytes());
            if (process.exitValue() != 0) {
                log.error("[Kitchen scaling] '{}' exited {}: {}", String.join(" ", cmd), process.exitValue(), out);
                return null;
            }
            return new ArrayList<>(out.lines().toList());
        } catch (Exception e) {
            log.error("[Kitchen scaling] Failed to call docker compose", e);
            return null;
        }
    }
}
