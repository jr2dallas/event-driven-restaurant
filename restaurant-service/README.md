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

A waiter works in **rounds**: one visit to one table. An order round takes the order of every
seated client at the table (clients who sit down while the waiter is on the way join the round);
a delivery round carries a tray with the table's ready dishes. The frontend drives each transition
by calling `PUT /waiters/{id}/state` when the sprite reaches its destination.

Each waiter has a home post (one per sector of the room, `restaurant.floor.waiter-posts`) where it
waits when idle. After dropping order slips at the pass it waits there for
`pass-wait-seconds`, since that is where the next dishes come out. The idle position is sent
to the front in the `Waiter.idlePosition` field.

## Scheduler

A `@Scheduled` method fires every `tick-delay-ms` (default 200 ms), under a single `FloorLock`
shared with the HTTP callbacks and the Kafka listener, and:

1. Seats the next client waiting at the door — preferably at a table where clients are about to order
2. Completes steps the front stopped reporting (`restaurant.timeouts.*`, e.g. hidden tab)
3. Rebuilds the pending jobs from the floor state (they can never go stale) and dispatches them:
   most urgent first (deliveries weigh `delivery-priority-weight` = 2×), each to the closest free
   waiter, plus `sector-penalty-px` for tables outside the waiter's sector
4. Moves EATING clients to LEAVING after `eating-timeout-seconds` and retries failed Kafka sends

Orders carry a phase (`SLIP_IN_TRANSIT` → `IN_KITCHEN` → `READY_AT_PASS` → `SERVED`). A separate
10 s loop re-sends (same `orderId`) orders still `IN_KITCHEN` after `rescue-threshold-seconds`, but
only when the kitchens have no backlog — a slow kitchen is not a lost order. A client is never
served a dish that was not cooked.

`FloorSnapshotService` builds a consistent state, which `StateStreamController` pushes over
server-sent events (`GET /internal/restaurant/state/stream`) whenever it changes.

## Kafka

| Topic | Direction | Key | Value |
|---|---|---|---|
| `orders.in` | Produced | client id | `OrderMessage` |
| `orders.ready` | Consumed | — | `OrderReadyMessage` (duplicates ignored by `orderId`) |

Orders are keyed by client id and spread over the 24 partitions of `orders.in` by Kafka (no partition
count hard-coded in the service). Sends never block the floor for more than `max.block.ms` = 2 s.

## Kitchen scaling

`KitchenScalingService` invokes `docker compose up --scale kitchen-service=N --no-deps kitchen-service`
through the Docker socket mounted at `/var/run/docker.sock`. No external orchestrator is needed. The
current count is read from Docker on first use, so it survives a restaurant-service restart.

Kitchen instance states (status, dishes on the burners, capacity) are read from Consul KV
(`/v1/kv/kitchen-state/{instanceId}`), where each kitchen instance publishes its own state.

## Autoscaling

`AutoscalingService` (off by default; `GET/PUT /internal/autoscaling`, or `AUTOSCALING_WAITERS` /
`AUTOSCALING_KITCHENS`):

- waiters: hire when a job has waited ≥ 8 s with nobody free; fire after ~20 s of overstaffing
- kitchens: spawn when the `orders.in` lag exceeds the total number of burners twice in a row;
  remove one after a minute of low load; 45 s cooldown (a kitchen needs ~40 s to start)

## Configuration

Key properties from `application.yml` (overridable via environment variables):

| Property | Default | Description |
|---|---|---|
| `restaurant.scheduler.tick-delay-ms` | `200` | Scheduler tick interval |
| `restaurant.scheduler.eating-timeout-seconds` | `20` | Auto-transition EATING → LEAVING |
| `restaurant.scheduler.rescue-threshold-seconds` | `60` | Order considered lost (only when kitchens have no backlog) |
| `restaurant.dispatch.tray-capacity` | `4` | Plates per delivery round |
| `restaurant.dispatch.delivery-priority-weight` | `2.0` | Serving vs taking orders at equal wait |
| `restaurant.dispatch.group-hold-seconds` | `4` | Max wait for the rest of a table's dishes |
| `restaurant.dispatch.pass-wait-seconds` | `6` | Wait at the pass after dropping slips |
| `restaurant.dispatch.sector-penalty-px` | `150` | Penalty for a table outside the waiter's sector |
| `restaurant.timeouts.walk-seconds` | `45` | Walk not reported by the front → completed |
| `restaurant.timeouts.taking-order-seconds` | `20` | Order taking not reported → completed |
| `restaurant.timeouts.walk-to-seat-seconds` | `60` | Client never reported SEATED → seated |
| `restaurant.floor.pass` / `waiter-posts` | see yml | Kitchen pass and idle posts (sectors) |
| `restaurant.autoscaling.*` | off | Autoscaling switches and bounds |
| `kitchen.scaling.initial-count` | `1` | Fallback when Docker cannot be queried |

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
