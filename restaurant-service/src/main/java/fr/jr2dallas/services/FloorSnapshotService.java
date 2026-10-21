package fr.jr2dallas.services;

import fr.jr2dallas.generated.model.RestaurantState;
import fr.jr2dallas.mappers.RestaurantMapper;
import org.springframework.stereotype.Service;

/** Consistent picture of the floor (sessions + seats + waiters taken under the same lock). */
@Service
public class FloorSnapshotService {

    private final FloorLock         lock;
    private final RestaurantService restaurantService;
    private final WaiterScheduler   waiterScheduler;
    private final RestaurantMapper  mapper;

    public FloorSnapshotService(FloorLock lock,
                                RestaurantService restaurantService,
                                WaiterScheduler waiterScheduler,
                                RestaurantMapper mapper) {
        this.lock = lock;
        this.restaurantService = restaurantService;
        this.waiterScheduler = waiterScheduler;
        this.mapper = mapper;
    }

    public RestaurantState snapshot() {
        return lock.get(() -> mapper.fromRestaurantStateToRestaurantStateDto(
                restaurantService.getState(),
                waiterScheduler.getWaiters()
        ));
    }
}
