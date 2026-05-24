import * as PIXI from 'pixi.js';
import type { ChefData } from '../store/kitchenStore';
import { KitchenState } from '../api/generated/model/kitchen-state';

// ── Palette ────────────────────────────────────────────────────
const SKIN        = 0xfcd5a0;
const SKIN_STROKE = 0xd4954a;
const JACKET      = 0xf9fafb;
const JACKET_STR  = 0xd1d5db;
const HAT_FILL    = 0xffffff;
const HAT_STROKE  = 0xe5e7eb;

// Background colours by state — legible even at small size
const BG_IDLE        = 0x93c5fd; // light blue
const BG_COOKING     = 0xf97316; // bright orange
const BG_STARTING    = 0xfbbf24; // yellow
const BG_UNREACHABLE = 0x9ca3af; // grey

const FLAME_COLORS = [0xef4444, 0xf97316, 0xfbbf24];
const STEAM_COLOR  = 0xe5e7eb;

const COOKING_DURATION_MS = 5000;

// Local coords of the right shoulder and the pot
const SHOULDER = { x: 7, y: -4 };
const POT      = { x: 20, y: 8 };

export class KitchenSprite {
    public container: PIXI.Container;
    private inner:    PIXI.Container; // character

    // State background (bottom layer, high visibility)
    private bg:       PIXI.Graphics;
    private flames:   PIXI.Graphics;
    private steam:    PIXI.Graphics;

    // Character
    private hat:      PIXI.Graphics;
    private head:     PIXI.Graphics;
    private body:     PIXI.Graphics;
    private armL:     PIXI.Graphics;
    private armR:     PIXI.Container;
    private armRg:    PIXI.Graphics;

    // Cooking station (pot + hotplate) — visible only in COOKING state
    private station:  PIXI.Graphics;

    // UI
    private timer:    PIXI.Text;
    private label:    PIXI.Text;

    private status:           KitchenState;
    private cookingStartedAt: number | null;
    private elapsed = 0;
    private readonly baseAngle = Math.atan2(POT.y - SHOULDER.y, POT.x - SHOULDER.x);

    constructor(data: ChefData) {
        this.status           = data.status;
        this.cookingStartedAt = data.cookingStartedAt;

        this.container = new PIXI.Container();
        this.inner     = new PIXI.Container();

        // Layer order: bg → flames/steam → station → character
        this.bg      = new PIXI.Graphics();
        this.flames  = new PIXI.Graphics();
        this.steam   = new PIXI.Graphics();
        this.station = new PIXI.Graphics();
        this.container.addChild(this.bg, this.flames, this.steam, this.station, this.inner);

        // Character inside inner
        this.armL  = new PIXI.Graphics();
        this.body  = new PIXI.Graphics();
        this.hat   = new PIXI.Graphics();
        this.head  = new PIXI.Graphics();

        this.armR  = new PIXI.Container();
        this.armRg = new PIXI.Graphics();
        this.armR.addChild(this.armRg);
        this.armR.position.set(SHOULDER.x, SHOULDER.y);

        this.label = new PIXI.Text({
            text: 'Chef',
            style: { fontSize: 7, fill: 0x374151, align: 'center', fontWeight: 'bold' },
        });
        this.label.anchor.set(0.5, 0);
        this.label.position.set(0, 20);

        this.timer = new PIXI.Text({
            text: '',
            style: { fontSize: 10, fill: 0xffffff, align: 'center', fontWeight: 'bold' },
        });
        this.timer.anchor.set(0.5, 1);
        this.timer.position.set(0, -28);

        this.inner.addChild(this.armL, this.body, this.armR, this.hat, this.head, this.label);
        this.container.addChild(this.timer);

        this.container.position.set(data.position.x, data.position.y);

        this.redraw();
        if (this.status === KitchenState.Unreachable) this.bg.alpha = 0.4;
    }

    sync(next: ChefData) {
        const statusChanged = this.status !== next.status;
        this.cookingStartedAt = next.cookingStartedAt;
        if (!statusChanged) return;
        this.status = next.status;
        this.resetAnimation();
        this.redraw();
        if (this.status === KitchenState.Unreachable) this.bg.alpha = 0.4;
    }

    private redraw() {
        const cooking     = this.status === KitchenState.Cooking;
        const unreachable = this.status === KitchenState.Unreachable;

        // ── Coloured background (primary state indicator) ─────
        this.bg.clear();
        const bgColor = {
            [KitchenState.Idle]:        BG_IDLE,
            [KitchenState.Cooking]:     BG_COOKING,
            [KitchenState.Starting]:    BG_STARTING,
            [KitchenState.Unreachable]: BG_UNREACHABLE,
        }[this.status];
        this.bg.circle(cooking ? 10 : 0, 0, cooking ? 36 : 26)
            .fill({ color: bgColor, alpha: cooking ? 0.35 : 0.25 })
            .stroke({ color: bgColor, alpha: 0.7, width: cooking ? 2.5 : 1.5 });

        // ── Chef's hat ────────────────────────────────────────
        this.hat.clear();
        this.hat.roundRect(-7, -34, 14, 16, 2)
            .fill({ color: unreachable ? 0xd1d5db : HAT_FILL })
            .stroke({ color: HAT_STROKE, width: 1 });
        this.hat.roundRect(-9, -20, 18, 5, 2)
            .fill({ color: unreachable ? 0xd1d5db : HAT_FILL })
            .stroke({ color: HAT_STROKE, width: 1.5 });

        // ── Head ──────────────────────────────────────────────
        this.head.clear();
        this.head.circle(0, 0, 6)
            .fill({ color: unreachable ? 0xd4b896 : SKIN })
            .stroke({ color: SKIN_STROKE, width: 1 });
        this.head.position.set(0, -16);

        // ── Body ──────────────────────────────────────────────
        this.body.clear();
        this.body.roundRect(-8, -8, 16, 18, 3)
            .fill({ color: unreachable ? 0xd1d5db : JACKET })
            .stroke({ color: unreachable ? 0xaaaaaa : JACKET_STR, width: 1.5 });
        const btn = unreachable ? 0xaaaaaa : 0xd1d5db;
        this.body.circle(-3, -3, 1.2).fill({ color: btn });
        this.body.circle(-3,  1, 1.2).fill({ color: btn });
        this.body.circle(-3,  5, 1.2).fill({ color: btn });

        // ── Left arm (always pointing down) ──────────────────
        this.armL.clear();
        this.armL.moveTo(-7, -2).lineTo(-10, 10)
            .stroke({ color: unreachable ? 0xd4b896 : SKIN, width: 4, cap: 'round' });

        // ── Right arm ─────────────────────────────────────────
        this.armRg.clear();
        if (cooking) {
            const dx = POT.x - SHOULDER.x;
            const dy = POT.y - SHOULDER.y;
            const len = Math.hypot(dx, dy);
            this.armRg.moveTo(0, 0).lineTo(len, 0)
                .stroke({ color: SKIN, width: 4, cap: 'round' });
            // Ladle
            this.armRg.circle(len + 5, 0, 5)
                .fill({ color: 0x6b7280 })
                .stroke({ color: 0x4b5563, width: 1 });
            const baseAngle = Math.atan2(dy, dx);
            this.armR.rotation = baseAngle;
        } else {
            this.armRg.moveTo(0, 0).lineTo(3, 12)
                .stroke({ color: unreachable ? 0xd4b896 : SKIN, width: 4, cap: 'round' });
            this.armR.rotation = 0;
        }

        // ── Cooking station ───────────────────────────────────
        this.station.clear();
        if (cooking) {
            // Hotplate
            this.station.roundRect(POT.x - 13, POT.y - 3, 26, 14, 3)
                .fill({ color: 0x111827, alpha: 0.9 });
            this.station.circle(POT.x, POT.y + 4, 6)
                .fill({ color: 0xef4444, alpha: 0.5 });
            // Pot
            this.station.roundRect(POT.x - 10, POT.y - 9, 20, 12, 3)
                .fill({ color: 0x374151 })
                .stroke({ color: 0x1f2937, width: 1.5 });
            this.station.roundRect(POT.x - 10, POT.y - 9, 20, 5, 2)
                .fill({ color: 0x4b5563 });
            // Handle
            this.station.roundRect(POT.x - 18, POT.y - 8, 9, 4, 2)
                .fill({ color: 0x374151 })
                .stroke({ color: 0x1f2937, width: 1 });
        }

        // Dim entire sprite when unreachable
        this.inner.alpha   = unreachable ? 0.45 : 1;
        this.station.alpha = unreachable ? 0.3  : 1;
    }

    private drawFlames(elapsed: number) {
        this.flames.clear();
        if (this.status !== KitchenState.Cooking) return;
        for (let i = 0; i < 5; i++) {
            const offset = (i - 2) * 5;
            const t      = ((elapsed * 0.002 + i * 0.2) % 1);
            const h      = 8 + Math.sin(elapsed * 0.006 + i) * 4;
            const y      = POT.y - 2 - t * h;
            const alp    = 0.8 * (1 - t);
            const r      = 3 - t * 2;
            const color  = FLAME_COLORS[i % FLAME_COLORS.length];
            this.flames.circle(POT.x + offset, y, r)
                .fill({ color, alpha: alp });
        }
    }

    private drawSteam(elapsed: number) {
        this.steam.clear();
        if (this.status !== KitchenState.Cooking) return;
        const offsets = [-6, 0, 6];
        for (let i = 0; i < offsets.length; i++) {
            const t   = ((elapsed * 0.0006 + i * 0.33) % 1);
            const y   = POT.y - 12 - t * 24;
            const alp = 0.5 * (1 - t);
            const r   = 2 + t * 4;
            this.steam.circle(POT.x + offsets[i], y, r)
                .fill({ color: STEAM_COLOR, alpha: alp });
        }
    }

    private resetAnimation() {
        this.elapsed        = 0;
        this.bg.alpha       = 1;
        this.bg.scale.set(1);
        this.inner.alpha    = 1;
        this.inner.rotation = 0;
        this.flames.clear();
        this.steam.clear();
        this.timer.text     = '';
    }

    update(deltaMS: number) {
        this.elapsed += deltaMS;
        const t = this.elapsed / 1000;

        if (this.status === KitchenState.Idle) {
            this.bg.alpha = 0.6 + 0.4 * (0.5 + 0.5 * Math.sin(t * (2 * Math.PI / 3.5)));

        } else if (this.status === KitchenState.Cooking) {
            const STIR_SPEED = 0.0035;
            const STIR_AMP   = 0.38;
            this.armR.rotation  = this.baseAngle + Math.sin(this.elapsed * STIR_SPEED) * STIR_AMP;
            this.inner.rotation = Math.sin(this.elapsed * STIR_SPEED) * 0.07;
            this.drawFlames(this.elapsed);
            this.drawSteam(this.elapsed);
            this.bg.scale.set(1 + 0.08 * Math.sin(this.elapsed * 0.006));
            if (this.cookingStartedAt != null) {
                const rem = Math.max(0, COOKING_DURATION_MS - (Date.now() - this.cookingStartedAt));
                this.timer.text = `${(rem / 1000).toFixed(1)}s`;
            } else {
                this.timer.text = '';
            }

        } else if (this.status === KitchenState.Starting) {
            this.bg.alpha = 0.3 + 0.7 * Math.abs(Math.sin(t * Math.PI * 0.9));
        }
    }

    destroy() {
        this.container.destroy({ children: true });
    }
}
