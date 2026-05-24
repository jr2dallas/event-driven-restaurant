package fr.jr2dallas.controllers;

import fr.jr2dallas.generated.api.WaitersApi;
import fr.jr2dallas.generated.model.WaiterStateUpdate;
import fr.jr2dallas.services.WaiterScheduler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WaiterController implements WaitersApi {

    private final WaiterScheduler waiterScheduler;

    public WaiterController(WaiterScheduler waiterScheduler) {
        this.waiterScheduler = waiterScheduler;
    }

    @Override
    public ResponseEntity<Void> updateWaiterState(String waiterId, WaiterStateUpdate body) {
        if (!waiterScheduler.waiterExists(waiterId)) {
            return ResponseEntity.notFound().build();
        }
        switch (body.getState()) {
            case TAKING_ORDER       -> waiterScheduler.onWaiterArrivedForOrder(waiterId);
            case WALKING_TO_KITCHEN -> waiterScheduler.onOrderTaken(waiterId);
            case IDLE               -> waiterScheduler.transitionToIdle(waiterId);
            default                 -> { return ResponseEntity.badRequest().build(); }
        }
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> updateWaiterCount(Integer count) {
        waiterScheduler.setWaiterCount(count);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> hireWaiter() {
        waiterScheduler.hireWaiter();
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> fireWaiter() {
        waiterScheduler.fireWaiter();
        return ResponseEntity.noContent().build();
    }
}
