import { RestaurantCanvas } from './components/RestaurantCanvas'
import LeftPanel from './components/LeftPanel'
import RightPanel from './components/RightPanel'
import ChartsModal from './components/ChartsModal'
import ErrorBoundary from './components/ErrorBoundary'
import { useKitchenStore } from './store/kitchenStore'
import { useWaiterSpritesStore } from './store/waiterSpritesStore'
import { useClientSpritesStore } from './store/clientSpritesStore'
import { useState, useCallback, useRef } from 'react'

// ── Waiter toast ──────────────────────────────────────────────

const HIRE_WAITER_MESSAGES = [
    '📋 Entretien réussi ! Bienvenue dans l\'équipe !',
    '🎓 Nouveau recrutement en cours… il commence lundi !',
    '🤝 Poignée de main, contrat signé !',
    '✨ Un renfort arrive en salle !',
]

const FIRE_WAITER_MESSAGES = [
    '😬 Aïe… il a renversé une assiette de trop.',
    '🤦 Licencié pour faute grave : plateau mal tenu.',
    '💔 C\'est avec regret que nous… non en fait pas tant que ça.',
    '🏃 Il est parti avant qu\'on lui demande.',
]

const HIRE_CHEF_MESSAGES = [
    '👨‍🍳 Nouveau cuistot embauché ! La casserole est à lui.',
    '🔥 Il a passé le test du soufflé… de justesse.',
    '🍳 Toque commandée, tablier repassé, il est prêt !',
    '⭐ Un chef étoilé (enfin, presque) rejoint les fourneaux !',
]

const FIRE_CHEF_MESSAGES = [
    '🧯 Il a mis le feu à la cuisine. Littéralement.',
    '😤 Trop de sel, trop souvent. Au revoir.',
    '🍽️ Renvoyé après avoir servi une carbonara avec de la crème.',
    '💨 Parti en claquant la porte du four.',
]

function pick(arr: string[]) { return arr[Math.floor(Math.random() * arr.length)] }

interface Toast { message: string; type: 'hire' | 'fire' }

const btnBase = 'text-white rounded-lg py-1.5 px-3.5 text-[13px] font-bold cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed'

function StaffToast({ toast }: { toast: Toast }) {
    return (
        <div
            className="fixed bottom-8 left-1/2 -translate-x-1/2 z-[200] rounded-xl px-6 py-3.5 text-white text-sm font-medium whitespace-nowrap toast-in"
            style={{
                background: toast.type === 'hire'
                    ? 'linear-gradient(135deg, #1e3a5f, #1e40af)'
                    : 'linear-gradient(135deg, #3b1414, #7f1d1d)',
                border: `1px solid ${toast.type === 'hire' ? '#3b82f6' : '#ef4444'}88`,
                boxShadow: '0 8px 32px rgba(0,0,0,0.5)',
            }}
        >
            {toast.message}
        </div>
    )
}

// ── TFC button ────────────────────────────────────────────────

function TfcButton() {
    const [firing, setFiring] = useState(false)

    const surge = async () => {
        if (firing) return
        setFiring(true)
        await fetch('/internal/restaurant/clients/batch?count=100&tag=tfc', { method: 'POST' })
        setTimeout(() => setFiring(false), 3000)
    }

    return (
        <button
            onClick={surge}
            disabled={firing}
            className="text-white rounded-lg py-1.5 px-4 text-[13px] font-bold tracking-[0.5px] transition-all cursor-pointer disabled:opacity-70 disabled:cursor-default"
            style={{
                background: firing
                    ? 'linear-gradient(135deg, #3b0764, #4c1d95)'
                    : 'linear-gradient(135deg, #5B21B6, #7C3AED)',
                border: firing ? '2px solid #6d28d9' : '2px solid #a78bfa',
                boxShadow: firing ? 'none' : '0 0 12px #7c3aed88',
            }}
        >
            {firing ? '⏳ Les fans arrivent…' : '🏟️ SORTIE TFC — 100 supporters !'}
        </button>
    )
}

// ── App ───────────────────────────────────────────────────────

function App() {
    const [showCharts, setShowCharts] = useState(false)
    const [toast, setToast] = useState<Toast | null>(null)
    const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
    const [clientLoading, setClientLoading] = useState(false)
    const [waiterLoading, setWaiterLoading] = useState(false)
    const [chefLoading,   setChefLoading]   = useState(false)

    const addClients = useCallback(async (url: string) => {
        const before = new Set(useClientSpritesStore.getState().clients.keys())
        setClientLoading(true)
        try {
            const res = await fetch(url, { method: 'POST' })
            if (!res.ok) { setClientLoading(false); return }
        } catch { setClientLoading(false); return }
        const tid = setTimeout(() => setClientLoading(false), 8000)
        const unsub = useClientSpritesStore.subscribe(({ clients }) => {
            if ([...clients.keys()].some(id => !before.has(id))) {
                clearTimeout(tid); unsub(); setClientLoading(false)
            }
        })
    }, [])

    const showToast = useCallback((type: 'hire' | 'fire', actor: 'waiter' | 'chef') => {
        if (toastTimer.current) clearTimeout(toastTimer.current)
        const pool = type === 'hire'
            ? (actor === 'waiter' ? HIRE_WAITER_MESSAGES : HIRE_CHEF_MESSAGES)
            : (actor === 'waiter' ? FIRE_WAITER_MESSAGES : FIRE_CHEF_MESSAGES)
        setToast({ message: pick(pool), type })
        toastTimer.current = setTimeout(() => setToast(null), 2500)
    }, [])

    const hireWaiter = useCallback(async () => {
        const before = useWaiterSpritesStore.getState().waiters.size
        setWaiterLoading(true)
        try {
            const res = await fetch('/internal/restaurant/waiters', { method: 'POST' })
            if (!res.ok) { setWaiterLoading(false); return }
            showToast('hire', 'waiter')
        } catch { setWaiterLoading(false); return }
        const tid = setTimeout(() => setWaiterLoading(false), 8000)
        const unsub = useWaiterSpritesStore.subscribe(({ waiters }) => {
            if (waiters.size > before) { clearTimeout(tid); unsub(); setWaiterLoading(false) }
        })
    }, [showToast])

    const fireWaiter = useCallback(async () => {
        const before = useWaiterSpritesStore.getState().waiters.size
        setWaiterLoading(true)
        try {
            const res = await fetch('/internal/restaurant/waiters', { method: 'DELETE' })
            if (!res.ok) { setWaiterLoading(false); return }
            showToast('fire', 'waiter')
        } catch { setWaiterLoading(false); return }
        const tid = setTimeout(() => setWaiterLoading(false), 8000)
        const unsub = useWaiterSpritesStore.subscribe(({ waiters }) => {
            if (waiters.size < before) { clearTimeout(tid); unsub(); setWaiterLoading(false) }
        })
    }, [showToast])

    const hireChef = useCallback(async () => {
        const before = useKitchenStore.getState().chefs.length
        setChefLoading(true)
        try {
            const res = await fetch('/internal/kitchen/instances', { method: 'POST' })
            if (!res.ok) { setChefLoading(false); return }
            useKitchenStore.getState().triggerFastPoll()
            showToast('hire', 'chef')
        } catch { setChefLoading(false); return }
        const tid = setTimeout(() => setChefLoading(false), 8000)
        const unsub = useKitchenStore.subscribe(({ chefs }) => {
            if (chefs.length > before) { clearTimeout(tid); unsub(); setChefLoading(false) }
        })
    }, [showToast])

    const fireChef = useCallback(async () => {
        const before = useKitchenStore.getState().chefs.length
        setChefLoading(true)
        try {
            const res = await fetch('/internal/kitchen/instances', { method: 'DELETE' })
            if (!res.ok) { setChefLoading(false); return }
            useKitchenStore.getState().triggerFastPoll()
            showToast('fire', 'chef')
        } catch { setChefLoading(false); return }
        const tid = setTimeout(() => setChefLoading(false), 8000)
        const unsub = useKitchenStore.subscribe(({ chefs }) => {
            if (chefs.length < before) { clearTimeout(tid); unsub(); setChefLoading(false) }
        })
    }, [showToast])

    return (
        <>
            <div className="grid grid-cols-[280px_1fr_280px] grid-rows-[60px_1fr] h-screen bg-cyber-dark text-white gap-3 p-3">

                {/* Header */}
                <header className="col-span-full bg-white/5 rounded-xl flex items-center px-5 gap-4">
                    <span className="font-bold text-base">🏟️ Restaurant l'Occitan</span>
                    <span className="text-orange-500 text-[13px]"></span>
                    <div className="ml-auto flex gap-2 items-center">
                        <button onClick={() => addClients('/internal/restaurant/clients')}
                            disabled={clientLoading}
                            className={`${btnBase} bg-green-600`}>
                            +1
                        </button>
                        <button onClick={() => addClients('/internal/restaurant/clients/batch?count=10')}
                            disabled={clientLoading}
                            className={`${btnBase} bg-green-700`}>
                            +10
                        </button>
                        <button onClick={() => addClients('/internal/restaurant/clients/batch?count=50')}
                            disabled={clientLoading}
                            className={`${btnBase} bg-green-800`}>
                            +50
                        </button>
                        <TfcButton />
                        <span className="opacity-20">|</span>
                        <button
                            onClick={() => setShowCharts(true)}
                            className="bg-[#1e3a5f] text-sky-300 border border-blue-500/50 rounded-lg py-1.5 px-3.5 text-[13px] font-bold cursor-pointer">
                            📈 Stats
                        </button>
                        <span className="opacity-20">|</span>
                        <button onClick={hireWaiter} disabled={waiterLoading}
                            className={`${btnBase} bg-blue-600`}>
                            {waiterLoading ? '⏳ + Waiter' : '+ Waiter'}
                        </button>
                        <button onClick={fireWaiter} disabled={waiterLoading}
                            className={`${btnBase} bg-violet-600`}>
                            {waiterLoading ? '⏳ − Waiter' : '− Waiter'}
                        </button>
                        <span className="opacity-20">|</span>
                        <button onClick={hireChef} disabled={chefLoading}
                            className={`${btnBase} bg-amber-700`}>
                            {chefLoading ? '⏳ + Chef' : '+ Chef'}
                        </button>
                        <button onClick={fireChef} disabled={chefLoading}
                            className={`${btnBase} bg-amber-800`}>
                            {chefLoading ? '⏳ − Chef' : '− Chef'}
                        </button>
                    </div>
                </header>

                <LeftPanel />
                <ErrorBoundary>
                    <RestaurantCanvas />
                </ErrorBoundary>
                <RightPanel />

                {showCharts && <ChartsModal onClose={() => setShowCharts(false)} />}

            </div>

            {toast && <StaffToast toast={toast} />}
        </>
    )
}

export default App
