# kitchen-service

Spring Boot service responsible for cooking orders. It consumes from the `orders.in` Kafka topic, simulates cooking with a configurable delay, then publishes to `orders.ready`. Multiple instances run in parallel as independent Kafka consumers sharing the same group.

## Responsibilities

- Consume orders from `orders.in`
- Simulate cooking asynchronously (configurable duration, default 5 s)
- Publish `OrderReadyMessage` to `orders.ready`
- Publish its internal state to Consul KV after every order event
- Expose a health endpoint for Consul service discovery

## Order processing

```
Kafka orders.in
      │
      ▼  (concurrency = 3, max.poll.records = 1)
OrderConsumer.handleOrder()
      │
      ├── kitchenService.startCooking(orderId)  → updates ConcurrentHashMap + Consul KV
      │
      ├── ack.acknowledge()                      → Kafka offset committed immediately
      │
      └── cookingScheduler.schedule(delay)       → async, does not block Kafka thread
                │
                ├── kitchenService.finishCooking(orderId) → updates ConcurrentHashMap + Consul KV
                │
                └── kafkaTemplate.send("orders.ready", ...)
```

### Why `max.poll.records=1` + `concurrency=3`

`max.poll.records=1` ensures each Kafka consumer thread receives exactly one order at a time, which matches the visual one-cook-per-order constraint. `concurrency=3` creates three consumer threads per instance, allowing up to three orders to be cooked in parallel.

### Why the cooking pool is separate from the Kafka thread

`ack.acknowledge()` is called immediately after scheduling the cooking task, not after the dish is ready. This frees the Kafka consumer thread to pick up the next order without waiting 5 seconds, while the cooking timer runs independently on a `ScheduledExecutorService`.

## State tracking

`KitchenService` maintains a `ConcurrentHashMap<String, Long>` of active orders (orderId → start timestamp). After every `startCooking` or `finishCooking` call, the current state is serialized and written to Consul KV at:

```
/v1/kv/kitchen-state/{instanceId}
```

The restaurant-service reads this key to aggregate kitchen states without direct HTTP calls between services.

## Horizontal scaling

All instances share the `kitchen-group` Kafka consumer group. Kafka automatically distributes the 10 partitions across live instances — adding an instance increases throughput proportionally up to 10 instances.

Each instance registers independently with Consul and writes its own KV entry, so the restaurant-service always sees the real per-instance state regardless of how many instances are running.

## Configuration

| Property | Default | Description |
|---|---|---|
| `kitchen.cooking.duration-ms` | `5000` | Simulated cooking time per order |

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
