// Lightweight 2-D force simulation for the outside crowd.

const RADIUS    = 18;
const ATTRACT   = 0.012;
const REPEL     = 0.35;
const SIDE_W    = 0.008;
const DAMP      = 0.78;
const MAX_SPEED = 1.6;

const CELL      = RADIUS * 2;        // 36 px — reach du repulsion
const CELL_INV  = 1 / CELL;
const MN        = RADIUS * 2;        // min distance avant repulsion
const MN2       = MN * MN;

interface Agent {
    x: number; y: number;
    vx: number; vy: number;
    sideBias: number;
}

export class CrowdPhysics {
    private agents    = new Map<string, Agent>();
    private agentsList: Agent[] = [];              // parallel list for fast iteration
    private grid      = new Map<number, Agent[]>(); // spatial grid, reused every tick

    constructor(
        private readonly anchorX: number,
        private readonly anchorY: number,
        private readonly wallX:   number,
    ) {}

    add(id: string, x: number, y: number, sideBias: number) {
        if (this.agents.has(id)) return;
        const a: Agent = { x, y, vx: 0, vy: 0, sideBias };
        this.agents.set(id, a);
        this.agentsList.push(a);
    }

    remove(id: string) {
        const a = this.agents.get(id);
        if (!a) return;
        this.agents.delete(id);
        const i = this.agentsList.indexOf(a);
        if (i !== -1) this.agentsList.splice(i, 1);
    }

    has(id: string)    { return this.agents.has(id); }
    ids(): IterableIterator<string> { return this.agents.keys(); }

    getPos(id: string): { x: number; y: number } | null {
        const a = this.agents.get(id);
        return a ? { x: a.x, y: a.y } : null;
    }

    tick(deltaMS: number) {
        const dt     = Math.min(deltaMS / 16.67, 2.5);
        const agents = this.agentsList;
        if (!agents.length) return;

        // ── Build spatial grid ────────────────────────────────
        const grid = this.grid;
        grid.clear();
        for (const a of agents) {
            const key = ((Math.floor(a.x * CELL_INV) * 16384) + Math.floor(a.y * CELL_INV)) | 0;
            let cell = grid.get(key);
            if (!cell) { cell = []; grid.set(key, cell); }
            cell.push(a);
        }

        // ── Integrate ─────────────────────────────────────────
        for (const a of agents) {
            const toX = this.anchorX - a.x;
            const toY = this.anchorY - a.y;
            const len = Math.sqrt(toX * toX + toY * toY) || 1;

            // Attraction toward anchor
            a.vx += toX * ATTRACT * dt;
            a.vy += toY * ATTRACT * dt;

            // Side bias — perpendicular to anchor direction, stable per agent
            a.vx += (-toY / len) * a.sideBias * SIDE_W * dt;
            a.vy += ( toX / len) * a.sideBias * SIDE_W * dt;

            // Repulsion — 3×3 grid neighbourhood only
            const cx = Math.floor(a.x * CELL_INV);
            const cy = Math.floor(a.y * CELL_INV);
            for (let dx = -1; dx <= 1; dx++) {
                for (let dy = -1; dy <= 1; dy++) {
                    const neighbors = grid.get(((cx + dx) * 16384 + (cy + dy)) | 0);
                    if (!neighbors) continue;
                    for (const b of neighbors) {
                        if (a === b) continue;
                        const bx = a.x - b.x;
                        const by = a.y - b.y;
                        const d2 = bx * bx + by * by;
                        if (d2 < MN2 && d2 > 0.001) {
                            const d    = Math.sqrt(d2);
                            const push = (MN - d) / d * REPEL * dt;
                            a.vx += bx * push;
                            a.vy += by * push;
                        }
                    }
                }
            }

            // Hard wall: stay outside the restaurant
            if (a.x > this.wallX) {
                a.vx -= (a.x - this.wallX) * 0.6 * dt;
            }

            // Damping
            const damp = Math.pow(DAMP, dt);
            a.vx *= damp;
            a.vy *= damp;

            // Speed cap
            const spd = Math.sqrt(a.vx * a.vx + a.vy * a.vy);
            if (spd > MAX_SPEED) {
                a.vx = a.vx / spd * MAX_SPEED;
                a.vy = a.vy / spd * MAX_SPEED;
            }

            a.x += a.vx * dt;
            a.y += a.vy * dt;
        }
    }
}
