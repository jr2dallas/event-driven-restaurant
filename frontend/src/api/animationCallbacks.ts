export async function notifyClientState(clientId: string, state: string): Promise<void> {
    try {
        await fetch(`/internal/restaurant/clients/${clientId}/state`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ state }),
        });
    } catch (e) { console.error('[Client] state update failed', e); }
}

export async function notifyWaiterState(waiterId: string, state: string): Promise<void> {
    try {
        await fetch(`/internal/restaurant/waiters/${waiterId}/state`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ state }),
        });
    } catch (e) { console.error('[Waiter] state update failed', e); }
}
