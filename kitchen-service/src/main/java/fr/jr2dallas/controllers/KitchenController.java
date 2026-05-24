package fr.jr2dallas.controllers;

import fr.jr2dallas.services.KitchenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/kitchen")
public class KitchenController {

    private final KitchenService kitchenService;

    public KitchenController(KitchenService kitchenService) {
        this.kitchenService = kitchenService;
    }

    @GetMapping("/state")
    public KitchenStateResponse getState() {
        return new KitchenStateResponse(
                kitchenService.getStatus(),
                kitchenService.waitingOrdersCount()
        );
    }

    public record KitchenStateResponse(String status, int activeOrders) {}
}