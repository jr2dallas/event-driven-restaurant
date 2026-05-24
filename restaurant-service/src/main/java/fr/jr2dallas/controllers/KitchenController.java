package fr.jr2dallas.controllers;

import fr.jr2dallas.generated.api.KitchenApi;
import fr.jr2dallas.generated.model.KitchenInstance;
import fr.jr2dallas.generated.model.ScaleResponse;
import fr.jr2dallas.services.ConsulService;
import fr.jr2dallas.services.KitchenScalingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class KitchenController implements KitchenApi {

    private final ConsulService consulService;
    private final KitchenScalingService kitchenScalingService;

    public KitchenController(ConsulService consulService, KitchenScalingService kitchenScalingService) {
        this.consulService = consulService;
        this.kitchenScalingService = kitchenScalingService;
    }

    @Override
    public ResponseEntity<List<KitchenInstance>> getKitchenInstances() {
        return ResponseEntity.ok(consulService.getKitchenInstances());
    }

    @Override
    public ResponseEntity<ScaleResponse> spawnKitchen() {
        int count = kitchenScalingService.spawn();
        return ResponseEntity.status(HttpStatus.CREATED).body(new ScaleResponse(count));
    }

    @Override
    public ResponseEntity<ScaleResponse> despawnKitchen() {
        int count = kitchenScalingService.despawn();
        return ResponseEntity.ok(new ScaleResponse(count));
    }
}
