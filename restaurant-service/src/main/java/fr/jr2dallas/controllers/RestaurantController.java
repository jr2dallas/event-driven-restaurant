package fr.jr2dallas.controllers;

import fr.jr2dallas.generated.api.RestaurantApi;
import fr.jr2dallas.generated.model.ClientEnqueuedResponse;
import fr.jr2dallas.generated.model.ClientStateUpdate;
import fr.jr2dallas.generated.model.RestaurantState;
import fr.jr2dallas.generated.model.SpawnResponse;
import fr.jr2dallas.mappers.RestaurantMapper;
import fr.jr2dallas.services.RestaurantService;
import fr.jr2dallas.services.WaiterScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class RestaurantController implements RestaurantApi {

    private final RestaurantService restaurantService;
    private final WaiterScheduler waiterScheduler;
    private final RestaurantMapper restaurantMapper;

    public RestaurantController(RestaurantService restaurantService,
                                WaiterScheduler waiterScheduler,
                                RestaurantMapper restaurantMapper) {
        this.restaurantService = restaurantService;
        this.waiterScheduler = waiterScheduler;
        this.restaurantMapper = restaurantMapper;
    }

    @Override
    public ResponseEntity<RestaurantState> getRestaurantState() {
        RestaurantState state = restaurantMapper.fromRestaurantStateToRestaurantStateDto(
                restaurantService.getState(),
                waiterScheduler.getWaiters()
        );
        return ResponseEntity.ok(state);
    }

    @Override
    public ResponseEntity<ClientEnqueuedResponse> enqueueClient() {
        String clientId = restaurantService.enqueueNewClient();
        return ResponseEntity.status(HttpStatus.CREATED).body(new ClientEnqueuedResponse(UUID.fromString(clientId)));
    }

    @Override
    public ResponseEntity<SpawnResponse> enqueueClientBatch(Integer count, String tag) {
        int capped = Math.min(count != null ? count : 10, 500);
        for (int i = 0; i < capped; i++) restaurantService.enqueueNewClient(tag);
        return ResponseEntity.status(HttpStatus.CREATED).body(new SpawnResponse(capped));
    }

    @Override
    public ResponseEntity<Void> updateClientState(UUID clientId, ClientStateUpdate body) {
        switch (body.getState()) {
            case AT_ENTRANCE -> restaurantService.clientAtEntrance(clientId.toString());
            case SEATED      -> restaurantService.clientSeated(clientId.toString());
            case DESPAWNED   -> restaurantService.clientLeft(clientId.toString());
            default          -> { return ResponseEntity.badRequest().build(); }
        }
        return ResponseEntity.ok().build();
    }
}
