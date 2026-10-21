# Frontend

React 19 + PixiJS 8 SPA that renders the restaurant simulation in real time and drives backend state transitions through REST callbacks.

## Tech stack

| Layer | Technology |
|---|---|
| UI framework | React 19 + TypeScript 5.7 |
| WebGL rendering | PixiJS 8 |
| State management | Zustand |
| Styling | Tailwind CSS |
| Charts | Apache ECharts (`echarts-for-react`) |
| Pathfinding | EasyStar.js (A\*) |
| API client | Generated from OpenAPI (`openapi-generator`) |
| Build | Vite |

## Architecture

The frontend is split between **Pixi rendering** (canvas, sprites, animations) and **Zustand stores** (shared state, server push, derived metrics). React components read from stores but never talk to PixiJS directly.

```
SSE push ─────► restaurantSyncStore ──► clientSpritesStore
                                    └──► waiterSpritesStore ──► SceneManager (PixiJS)
REST polling ──► kitchenStore        ──► ChefSprites
                      │
                      └──► statsStore (derived metrics)
                      └──► chartsHistoryStore (time series)
```

### Store responsibilities

| Store | Role |
|---|---|
| `restaurantSyncStore` | Receives the floor state pushed on `/internal/restaurant/state/stream` (SSE) as soon as it changes; falls back to polling `/internal/restaurant/state` every 2 s while the stream is down |
| `kitchenStore` | Polls `/internal/kitchen/instances` every 3 s (fast-polls on hire/fire); each chef keeps its station for life |
| `clientSpritesStore` | Sprite state per client (position, animation state, target seat) |
| `waiterSpritesStore` | Sprite state per waiter (position, animation state, round: clients of the table, plates carried, idle spot chosen by the backend) |
| `statsStore` | Derives all dashboard metrics from restaurant + kitchen state |
| `chartsHistoryStore` | Maintains 60-second rolling time series for the left-panel charts |
| `dishReactionStore` | Triggers emoji reactions when a dish is delivered to a table |

## Animation-driven state machine

The backend only advances a client or waiter's state when the **frontend animation confirms the transition**. For example:

- Clients wait outside in the crowd. As soon as the backend assigns them a seat (every free seat goes to the queue, first come first served), they walk to the door — several at once, on three lanes — and in. Nobody enters without a seat.
- A waiter is assigned a table → frontend animates the walk → on arrival, calls `PUT /waiters/{id}/state TAKING_ORDER` and takes every order of the table
- Waiter drops the slips at the pass → calls `PUT /waiters/{id}/state IDLE` → the orders go to Kafka
- Dishes ready → the backend assigns a tray → the waiter walks to the pass, collects the plates, walks to the table → calls `PUT /waiters/{id}/state IDLE`

Animation timing and business logic stay in sync. If the tab is hidden (the browser pauses animations), backend timeouts complete the pending steps so the floor keeps moving.

## Pathfinding

The restaurant floor is mapped to a tile grid by `buildNavGrid.ts`, which reads `src/config/restaurant-layout.json` and marks obstacle/table tiles as impassable. EasyStar.js runs A\* on this grid to compute walking paths for each sprite.

`resolveSeatsFromLayout.ts` pre-computes the waiter service position for every chair (the spot a waiter stands to take an order), so pathfinding targets are resolved instantly at runtime.

Press **`P`** in the browser to toggle a real-time debug overlay: green lines for clients, blue for waiters, each drawn from the sprite's current position to its destination.

![Pathfinding debug overlay](../docs/pathfinding-debug.png)

### Updating the layout (new restaurant image)

Follow these steps whenever you change the floor plan:

**1. Replace the background image**

Drop the new image into `public/` and update the filename in `src/components/RestaurantCanvas.tsx`:

```ts
manager.init('/your-new-image.png')
```

**2. Update the canvas dimensions**

If the image dimensions differ from the current `1200 × 896`, update the two constants at the top of `RestaurantCanvas.tsx`:

```ts
const IMAGE_W = 1200; // ← new width
const IMAGE_H = 896;  // ← new height
```

The nav grid tile size is 8 px, so `IMAGE_W` and `IMAGE_H` should ideally be multiples of 8.

**3. Remap the layout with the editor**

Open `scripts/layout-editor.html` directly in a browser (no server needed). Load the new image as background, then place every element on the floor plan:

| Element | Role in pathfinding |
|---|---|
| `table` | Blocked zone (with padding) |
| `chair` | Passable but costly — A\* routes around them |
| `obstacle` / `plant` | Fully blocked zone |
| `kitchen_station` | Blocked zone (larger padding) |
| `entrance` | Defines the wall gap sprites walk through |
| `queue_start` / `queue_end` | Passable — crowd queue positions |

**4. Export and replace the JSON**

Click **Download** in the editor and save the file as `src/config/restaurant-layout.json`, replacing the existing one.

**5. Restart the dev server**

```bash
npm run dev
```

Vite re-imports the JSON at startup. The nav grid is built once when the canvas mounts — no runtime reload is needed.

## API code generation

The TypeScript API client is generated from the OpenAPI contract at `../api/restaurant-api.yml`:

```bash
node scripts/generate-api-from-backend.mjs
```

Generated files land in `src/api/generated/` and are committed to the repo so the project builds without running the generator. Re-run the script whenever the contract changes.

## Running locally

```bash
npm install
npm run dev      # starts Vite dev server on http://localhost:5173
```

The dev server proxies `/internal` to `http://localhost:8080`, so the backend must be running. Use `docker compose up` to start all services, or start only the infrastructure (Kafka, Consul) and run the Spring Boot services locally.
