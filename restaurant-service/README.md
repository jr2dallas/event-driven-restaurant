# restaurant-service

Spring Boot service responsible for floor management: it owns the client and waiter state machines, publishes orders to Kafka, consumes kitchen replies, and exposes the REST API consumed by the frontend.

## Responsibilities

- Manage the lifecycle of every `ClientSession` and `WaiterWorker`
- Assign waiters to clients and dispatch delivery tasks
- Publish orders to the `orders.in` Kafka topic
- Consume `orders.ready` replies and trigger delivery
- Proxy kitchen instance state from Consul to the frontend
- Scale kitchen-service instances at runtime via Docker Compose

## State machines

### ClientSession

```
QUEUING → AT_ENTRANCE → WALKING_TO_SEAT → SEATED → WAITING_ORDER → EATING → LEAVING → DESPAWNED
```

Transitions are triggered by frontend callbacks (`PUT /clients/{id}/state`). The backend never advances the state unilaterally — it waits for the animation to confirm each step.

### WaiterWorker

```
IDLE → WALKING_TO_CLIENT → TAKING_ORDER → WALKING_TO_KITCHEN → DELIVERING → IDLE
```

The scheduler assigns tasks to idle waiters on every tick. The frontend drives each transition by calling `PUT /waiters/{id}/state` when the sprite reaches its destination.

Task priority inside `WaiterWorker.pickNextTask()`:
1. Urgent deliveries (dish has been waiting longer than `stale-dish-threshold-seconds`)
2. Order taking (clients waiting at table)
3. Normal deliveries

## Scheduler

A `@Scheduled` method fires every `tick-delay-ms` (default 200 ms) and:

1. Moves clients from `QUEUING` to `AT_ENTRANCE` when a seat is free
2. Assigns idle waiters to pending order and delivery tasks
3. Detects clients stuck in `WAITING_ORDER` with no assigned waiter for longer than `rescue-threshold-seconds` (default 45 s) and force-creates a delivery task to unblock them

The rescue path prevents permanent deadlocks after a waiter reset or a missed callback without requiring any manual intervention.

## Kafka

| Topic | Direction | Key | Value |
|---|---|---|---|
| `orders.in` | Produced | round-robin partition counter | `OrderMessage` |
| `orders.ready` | Consumed | — | `OrderReadyMessage` |

The topic has 10 partitions. Orders are routed by a monotonic partition counter so load spreads evenly across kitchen instances.

## Kitchen scaling

`KitchenScalingService` invokes `docker compose up --scale kitchen-service=N` through the Docker socket mounted at `/var/run/docker.sock`. No external orchestrator is needed. The desired count is rolled back automatically if the command fails or times out.

Kitchen instance states are read from Consul KV (`/v1/kv/kitchen-state/{instanceId}`), where each kitchen instance publishes its own state after every order event.

## Configuration

Key properties from `application.yml` (overridable via environment variables):

| Property | Default | Description |
|---|---|---|
| `restaurant.scheduler.tick-delay-ms` | `200` | Scheduler tick interval |
| `restaurant.scheduler.stale-dish-threshold-seconds` | `30` | Dish urgency threshold |
| `restaurant.scheduler.eating-timeout-seconds` | `20` | Auto-transition EATING → LEAVING |
| `restaurant.scheduler.rescue-threshold-seconds` | `45` | Stuck waiter detection |
| `kitchen.scaling.initial-count` | `1` | Kitchen instances at startup |

## Tests

```bash
./mvnw test
```

| Scope | What is covered |
|---|---|
| Domain unit tests | Full `ClientSession` and `WaiterWorker` lifecycle, all transitions |
| Service unit tests | `WaiterScheduler` tick logic, hire/fire, rescue path |
| `@WebMvcTest` | All controllers — happy path, 400/404 error cases |

## Running locally

```bash
./mvnw spring-boot:run
```

Requires Kafka (port 9093) and Consul (port 8500) to be reachable. The easiest way is to start only the infrastructure services:

```bash
docker compose up kafka consul
```
