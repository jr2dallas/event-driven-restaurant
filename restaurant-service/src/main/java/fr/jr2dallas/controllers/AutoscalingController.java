package fr.jr2dallas.controllers;

import fr.jr2dallas.generated.api.AutoscalingApi;
import fr.jr2dallas.generated.model.AutoscalingSettings;
import fr.jr2dallas.services.AutoscalingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AutoscalingController implements AutoscalingApi {

    private final AutoscalingService autoscaling;

    public AutoscalingController(AutoscalingService autoscaling) {
        this.autoscaling = autoscaling;
    }

    @Override
    public ResponseEntity<AutoscalingSettings> getAutoscaling() {
        return ResponseEntity.ok(current());
    }

    @Override
    public ResponseEntity<AutoscalingSettings> updateAutoscaling(AutoscalingSettings body) {
        autoscaling.configure(Boolean.TRUE.equals(body.getWaiters()), Boolean.TRUE.equals(body.getKitchens()));
        return ResponseEntity.ok(current());
    }

    private AutoscalingSettings current() {
        return new AutoscalingSettings()
                .waiters(autoscaling.waitersEnabled())
                .kitchens(autoscaling.kitchensEnabled());
    }
}
