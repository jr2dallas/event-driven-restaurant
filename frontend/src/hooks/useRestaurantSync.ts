// src/hooks/useRestaurantSync.ts
import { useEffect } from 'react';
import { useRestaurantSyncStore } from '../store/restaurantSyncStore';
import { useKitchenStore } from '../store/kitchenStore';

interface Options {
    pollInterval?: number;
    enabled?: boolean;
}

export function useRestaurantSync({ pollInterval = 2000, enabled = true }: Options = {}) {
    const startPolling    = useRestaurantSyncStore(s => s.startPolling);
    const stopPolling     = useRestaurantSyncStore(s => s.stopPolling);
    const setPollInterval = useRestaurantSyncStore(s => s.setPollInterval);
    const isPolling       = useRestaurantSyncStore(s => s.isPolling);
    const error           = useRestaurantSyncStore(s => s.error);
    const lastState       = useRestaurantSyncStore(s => s.lastState);
    const startKitchen    = useKitchenStore(s => s.startPolling);
    const stopKitchen     = useKitchenStore(s => s.stopPolling);

    useEffect(() => {
        if (!enabled) { stopPolling(); stopKitchen(); return; }
        setPollInterval(pollInterval);
        startPolling();
        startKitchen();
        return () => { stopPolling(); stopKitchen(); };
    }, [enabled, pollInterval]);

    useEffect(() => {
        const onVisibility = () => {
            if (document.hidden) { stopPolling(); stopKitchen(); }
            else if (enabled)    { startPolling(); startKitchen(); }
        };
        document.addEventListener('visibilitychange', onVisibility);
        return () => document.removeEventListener('visibilitychange', onVisibility);
    }, [enabled]);

    return { isPolling, error, lastState };
}
