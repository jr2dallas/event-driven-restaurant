import * as PIXI from 'pixi.js'

const BG_COLOR     = 0xfff9e6
const BORDER_COLOR = 0xd97706
const RADIUS       = 10
const PAD          = 10
const TAIL_H       = 13
const BUBBLE_W     = 190
const WRAP_W       = BUBBLE_W - PAD * 2

export class SpeechBubble {
    public container: PIXI.Container

    constructor(message: string) {
        this.container = new PIXI.Container()

        const text = new PIXI.Text({
            text: message,
            style: {
                fontSize: 12,
                fill: '#1a1a2e',
                fontFamily: 'Arial, sans-serif',
                wordWrap: true,
                wordWrapWidth: WRAP_W,
                align: 'center',
                lineHeight: 17,
            },
        })
        text.x = PAD
        text.y = PAD

        const bh = text.height + PAD * 2
        const cx = BUBBLE_W / 2

        const g = new PIXI.Graphics()
        g.moveTo(RADIUS, 0)
         .lineTo(BUBBLE_W - RADIUS, 0)
         .quadraticCurveTo(BUBBLE_W, 0, BUBBLE_W, RADIUS)
         .lineTo(BUBBLE_W, bh - RADIUS)
         .quadraticCurveTo(BUBBLE_W, bh, BUBBLE_W - RADIUS, bh)
         .lineTo(cx + 7, bh)
         .lineTo(cx, bh + TAIL_H)
         .lineTo(cx - 7, bh)
         .lineTo(RADIUS, bh)
         .quadraticCurveTo(0, bh, 0, bh - RADIUS)
         .lineTo(0, RADIUS)
         .quadraticCurveTo(0, 0, RADIUS, 0)
         .closePath()
         .fill({ color: BG_COLOR, alpha: 0.96 })
         .stroke({ color: BORDER_COLOR, width: 1.5 })

        this.container.addChild(g, text)
        this.container.pivot.set(BUBBLE_W / 2, bh + TAIL_H)
    }

    destroy() {
        this.container.destroy({ children: true })
    }
}
