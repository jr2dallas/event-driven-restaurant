package fr.jr2dallas.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.jr2dallas.services.FloorSnapshotService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Pushes the floor state to the front as soon as it changes, instead of the front polling
 * every 2 s: a seat assignment, a waiter dispatch or a dish ready reaches the animations
 * within ~100 ms. Documented in restaurant-api.yml (tag "stream"); implemented by hand
 * because the generated interface cannot return an SseEmitter.
 */
@RestController
public class StateStreamController {

    private static final Logger log = LoggerFactory.getLogger(StateStreamController.class);
    private static final long HEARTBEAT_MS = 15_000;

    private final FloorSnapshotService snapshots;
    private final ObjectMapper         objectMapper;
    private final Set<SseEmitter>      emitters = ConcurrentHashMap.newKeySet();

    private String lastPayload = null;
    private long   lastSentAt  = 0;

    public StateStreamController(FloorSnapshotService snapshots, ObjectMapper objectMapper) {
        this.snapshots    = snapshots;
        this.objectMapper = objectMapper;
    }

    @GetMapping(path = "/internal/restaurant/state/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response) throws IOException {
        // nginx must not buffer the stream
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");

        SseEmitter emitter = new SseEmitter(0L); // no timeout — closed by the client
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);

        // First frame right away so the front does not wait for the next change
        emitter.send(SseEmitter.event().name("state").data(currentPayload(), MediaType.APPLICATION_JSON));
        return emitter;
    }

    @Scheduled(fixedDelay = 100)
    public synchronized void publish() {
        if (emitters.isEmpty()) { lastPayload = null; return; }
        long now = System.currentTimeMillis();
        String payload;
        try {
            payload = currentPayload();
        } catch (IOException e) {
            log.warn("[SSE] Failed to serialise state: {}", e.getMessage());
            return;
        }

        if (payload.equals(lastPayload)) {
            if (now - lastSentAt >= HEARTBEAT_MS) {
                broadcast(() -> SseEmitter.event().comment("heartbeat"));
                lastSentAt = now;
            }
            return;
        }
        broadcast(() -> SseEmitter.event().name("state").data(payload, MediaType.APPLICATION_JSON));
        lastPayload = payload;
        lastSentAt  = now;
    }

    private String currentPayload() throws IOException {
        return objectMapper.writeValueAsString(snapshots.snapshot());
    }

    // One builder per emitter: an SseEventBuilder must not be sent twice
    private void broadcast(Supplier<SseEmitter.SseEventBuilder> event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event.get());
            } catch (Exception e) {
                emitters.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }
}
