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

The frontend is split between **Pixi rendering** (canvas, sprites, animations) and **Zustand stores** (shared state, polling, derived metrics). React components read from stores but never talk to PixiJS directly.

```
REST polling ──► restaurantSyncStore ──► clientSpritesStore
                                    └──► waiterSpritesStore ──► SceneManager (PixiJS)
REST polling ──► kitchenStore        ──► ChefSprites
                      │
                      └──► statsStore (derived metrics)
                      └──► chartsHistoryStore (time series)
```

### Store responsibilities

| Store | Role |
|---|---|
| `restaurantSyncStore` | Polls `/internal/restaurant/state` every 2 s — source of truth for sessions and waiters |
| `kitchenStore` | Polls `/internal/kitchen/instances` every 3 s (fast-polls on hire/fire) |
| `clientSpritesStore` | Sprite state per client (position, animation state, target seat) |
| `waiterSpritesStore` | Sprite state per waiter (position, animation state, target) |
| `statsStore` | Derives all dashboard metrics from restaurant + kitchen state |
| `chartsHistoryStore` | Maintains 60-second rolling time series for the left-panel charts |
| `dishReactionStore` | Triggers emoji reactions when a dish is delivered to a table |

## Animation-driven state machine

The backend only advances a client or waiter's state when the **frontend animation confirms the transition**. For example:

- A waiter is assigned a client → frontend animates the walk → on arrival, calls `PUT /waiters/{id}/state TAKING_ORDER`
- The order is sent to Kafka → kitchen cooks → Kafka reply triggers `PUT /waiters/{id}/state DELIVERING`
- Waiter walks to table and delivers → calls `PUT /waiters/{id}/state IDLE`

This means animation timing and business logic are naturally in sync without any polling or timeouts on the backend side.

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
