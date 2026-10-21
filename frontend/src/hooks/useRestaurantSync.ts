// src/hooks/useRestaurantSync.ts
import { useEffect } from 'react';
import { useRestaurantSyncStore } from '../store/restaurantSyncStore';
import { useKitchenStore } from '../store/kitchenStore';

interface Options {
    /** Fallback polling interval, used only while the push stream is down */
    pollInterval?: number;
    enabled?: boolean;
}

export function useRestaurantSync({ pollInterval = 2000, enabled = true }: Options = {}) {
    const connect         = useRestaurantSyncStore(s => s.connect);
    const disconnect      = useRestaurantSyncStore(s => s.disconnect);
    const setPollInterval = useRestaurantSyncStore(s => s.setPollInterval);
    const isPolling       = useRestaurantSyncStore(s => s.isPolling);
    const isStreaming     = useRestaurantSyncStore(s => s.isStreaming);
    const error           = useRestaurantSyncStore(s => s.error);
    const lastState       = useRestaurantSyncStore(s => s.lastState);
    const startKitchen    = useKitchenStore(s => s.startPolling);
    const stopKitchen     = useKitchenStore(s => s.stopPolling);

    useEffect(() => {
        if (!enabled) { disconnect(); stopKitchen(); return; }
        setPollInterval(pollInterval);
        connect();
        startKitchen();
        return () => { disconnect(); stopKitchen(); };
    }, [enabled, pollInterval]);

    // Hidden tab: animations are paused by the browser anyway — stop the stream,
    // the backend timeouts keep the floor moving, and we resync on return.
    useEffect(() => {
        const onVisibility = () => {
            if (document.hidden) { disconnect(); stopKitchen(); }
            else if (enabled)    { connect(); startKitchen(); }
        };
        document.addEventListener('visibilitychange', onVisibility);
        return () => document.removeEventListener('visibilitychange', onVisibility);
    }, [enabled]);

    return { isPolling, isStreaming, error, lastState };
}
