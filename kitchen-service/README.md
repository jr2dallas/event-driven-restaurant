# kitchen-service

Spring Boot service responsible for cooking orders. It consumes from the `orders.in` Kafka topic, simulates cooking with a configurable delay, then publishes to `orders.ready`. Multiple instances run in parallel as independent Kafka consumers sharing the same group.

## Responsibilities

- Consume orders from `orders.in`, one per burner (`kitchen.burners`, default 2)
- Simulate cooking (configurable duration, default 5 s)
- Publish `OrderReadyMessage` to `orders.ready`, then commit the Kafka offset
- Publish its state (status, dishes on the burners, capacity) to Consul KV after every order event
- Expose a health endpoint for Consul service discovery

## Order processing

```
Kafka orders.in
      │
      ▼  (concurrency = kitchen.burners, max.poll.records = 1)
OrderConsumer.handleOrder()          ← one listener thread = one burner
      │
      ├── kitchenService.startCooking(orderId)  → updates ConcurrentHashMap + Consul KV
      ├── Thread.sleep(cooking duration)        → the burner is busy
      ├── kitchenService.finishCooking(orderId) → updates ConcurrentHashMap + Consul KV
      ├── kafkaTemplate.send("orders.ready").get()
      └── ack.acknowledge()                      → offset committed only now
```

### A kitchen has a real capacity

Each listener thread cooks one dish at a time, so a kitchen never holds more orders than it has
burners. Extra orders stay in Kafka: the `orders.in` consumer lag **is** the kitchen backlog, and
whichever kitchen frees a burner first takes the next order. Hiring a kitchen therefore adds
throughput (an earlier version acknowledged immediately and cooked in a separate pool, so one
kitchen could cook an unlimited number of dishes and extra kitchens did nothing).

### At-least-once

The offset is committed after `orders.ready` has been acknowledged by Kafka. If a kitchen is
stopped mid-cooking (scale down, crash), the order is redelivered to another kitchen. The
restaurant-service ignores duplicate "ready" messages by `orderId`. `stop_grace_period: 20s` in
Compose lets a kitchen finish the dishes on its burners before stopping.

## State tracking

`KitchenService` maintains a `ConcurrentHashMap<String, Long>` of active orders (orderId → start timestamp). After every `startCooking` or `finishCooking` call, the current state is serialized and written to Consul KV at:

```
/v1/kv/kitchen-state/{instanceId}
```

The restaurant-service reads this key to aggregate kitchen states without direct HTTP calls between services.

## Horizontal scaling

All instances share the `kitchen-group` Kafka consumer group. `orders.in` has 24 partitions (9 kitchen stations × 2 burners, with headroom); Kafka spreads them over every burner of every live instance. The `CooperativeStickyAssignor` only moves the partitions that need to move when a kitchen joins or leaves, instead of pausing every kitchen.

Each instance registers independently with Consul and writes its own KV entry, so the restaurant-service always sees the real per-instance state regardless of how many instances are running.

## Configuration

| Property | Default | Description |
|---|---|---|
| `kitchen.burners` (`KITCHEN_BURNERS`) | `2` | Dishes cooked in parallel by one instance |
| `kitchen.cooking.duration-ms` | `5000` | Simulated cooking time per order |
| `kitchen.ready-send-timeout-ms` | `10000` | Max wait for Kafka to accept the "ready" message |

## Running locally

```bash
./mvnw spring-boot:run
```

Requires Kafka (port 9093) and Consul (port 8500). Start them with:

```bash
docker compose up kafka consul
```

For multi-instance testing, use Docker Compose directly:

```bash
docker compose up --scale kitchen-service=3
```
