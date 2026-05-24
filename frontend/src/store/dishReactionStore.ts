import { create } from 'zustand'
import { useRestaurantSyncStore } from './restaurantSyncStore'
import { useWaiterSpritesStore } from './waiterSpritesStore'
import { useKitchenStore } from './kitchenStore'
import { SessionState } from '../api/generated/model/session-state'
import { hashId } from '../utils/hashId'

const DISHES = ['burger', 'pizza', 'salad', 'pasta', 'steak', 'sushi'] as const
type Dish = typeof DISHES[number]

const DISH_EMOJI: Record<Dish, string> = {
    burger: '🍔',
    pizza:  '🍕',
    salad:  '🥗',
    pasta:  '🍝',
    steak:  '🥩',
    sushi:  '🍣',
}

const WAITER_LINES: Record<Dish, string[]> = {
    burger: [
        'Encore un burger… c\'est la 5e ce soir.',
        'Ketchup ou mayo ? Les deux. Évidemment.',
        'Au moins c\'est pas compliqué à porter.',
    ],
    pizza: [
        'Margherita ou napolitaine ? Il a dit les deux.',
        'Classique. Je savais même avant qu\'il ouvre la bouche.',
        'Il a commandé une pizza… et une autre pizza.',
    ],
    salad: [
        'Sans croûtons, sans fromage, juste… de la laitue.',
        'Une salade. Ce soir. Dans un resto. OK.',
        'Santé ! (dit le serveur, un peu envieux)',
    ],
    pasta: [
        'Al dente. Il sait ce que ça veut dire ?',
        'Pasta ! *accent italien qui s\'intensifie*',
        'Carbo ou bolognaise ? Il a dit "surprenez-moi".',
    ],
    steak: [
        'Quelle cuisson ? …Bien cuit. Évidemment.',
        'Saignant ! Enfin quelqu\'un de courageux.',
        'Il a hésité 3 minutes. Puis il a dit bleu.',
    ],
    sushi: [
        'On sert des sushis ici ? Apparemment oui.',
        'Wasabi ? Il a dit non. Il va le regretter.',
        'Nouveau menu. Le chef est au courant ?',
    ],
}

const CHEF_LINES: Record<Dish, string[]> = {
    burger: [
        'Un burger. J\'ai fait l\'école hôtelière pour ça.',
        'Encore. Je suis cuistot, pas McDo.',
        'Bun, steak, sauce. Je gère. (soupir)',
    ],
    pizza: [
        'Pizza ! Au moins c\'est simple. Enfin…',
        'Je m\'appelle pas Luigi mais je gère.',
        '400°C, 90 secondes. C\'est un art, qu\'ils disent.',
    ],
    salad: [
        'Une salade. Je pose des feuilles dans une assiette.',
        'C\'est un peu vexant pour mes 10 ans de formation.',
        'Enfin quelqu\'un de raisonnable ! (soupir intérieur)',
    ],
    pasta: [
        'PASTA ! Enfin un plat digne de ce nom !',
        'Al dente. Pas négociable.',
        'Carbonara SANS crème. Je surveille.',
    ],
    steak: [
        'Bien cuit ?! Quel gâchis de bonne viande.',
        'Bleu. Parfait. Le seul choix acceptable.',
        '5 minutes max. Promis. (3 en vrai)',
    ],
    sushi: [
        'Je vais avoir besoin d\'un couteau très tranchant.',
        '…Je google la recette.',
        'On improvise. Ça ira. Probablement.',
    ],
}

function pickDish(id: string): Dish {
    return DISHES[hashId(id) % DISHES.length]
}

function pick<T>(arr: T[]): T {
    return arr[Math.floor(Math.random() * arr.length)]
}

export interface DishReaction {
    message: string
    dish: Dish
    actorType: 'waiter' | 'chef'
    actorId: string
}

interface DishReactionState {
    reaction: DishReaction | null
}

export const useDishReactionStore = create<DishReactionState>(() => ({ reaction: null }))

const seen = new Set<string>()
let lastShownAt = 0
const MIN_INTERVAL_MS = 3000

useRestaurantSyncStore.subscribe(state => {
    const sessions = state.lastState?.sessions ?? []

    // Clean up sessions that are gone
    const activeIds = new Set(sessions.map(s => s.id))
    for (const id of seen) {
        if (!activeIds.has(id)) seen.delete(id)
    }

    // Rate limit
    const now = Date.now()
    if (now - lastShownAt < MIN_INTERVAL_MS) return

    for (const session of sessions) {
        if (session.state !== SessionState.WaitingOrder) continue
        if (seen.has(session.id)) continue

        seen.add(session.id)
        lastShownAt = now

        const dish = pickDish(session.id)

        const waiterIds = [...useWaiterSpritesStore.getState().waiters.keys()]
        const chefIds   = useKitchenStore.getState().chefs.map(c => c.instanceId)

        let actorType: 'waiter' | 'chef'
        let actorId: string

        if (Math.random() < 0.5 && waiterIds.length > 0) {
            actorType = 'waiter'
            actorId   = pick(waiterIds)
        } else if (chefIds.length > 0) {
            actorType = 'chef'
            actorId   = pick(chefIds)
        } else if (waiterIds.length > 0) {
            actorType = 'waiter'
            actorId   = pick(waiterIds)
        } else {
            break
        }

        const lines   = actorType === 'waiter' ? WAITER_LINES[dish] : CHEF_LINES[dish]
        const message = `${DISH_EMOJI[dish]} ${pick(lines)}`

        useDishReactionStore.setState({ reaction: { message, dish, actorType, actorId } })
        setTimeout(() => useDishReactionStore.setState({ reaction: null }), 2800)
        break
    }
})
