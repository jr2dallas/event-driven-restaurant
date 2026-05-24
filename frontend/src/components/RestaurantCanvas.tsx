// src/components/RestaurantCanvas.tsx
import { useEffect, useRef } from 'react';
import { SceneManager } from '../pixi/SceneManager';
import { useRestaurantSync } from '../hooks/useRestaurantSync';

const IMAGE_W = 1200;
const IMAGE_H = 896;

export function RestaurantCanvas() {
    const canvasRef  = useRef<HTMLCanvasElement>(null);
    const managerRef = useRef<SceneManager | null>(null);

    const { error } = useRestaurantSync({ pollInterval: 2000 });

    useEffect(() => {
        if (!canvasRef.current) return;

        const manager = new SceneManager(canvasRef.current, IMAGE_W, IMAGE_H);
        managerRef.current = manager;

        // PixiJS v8 : init() est async
        manager.init('/resto_final.png').catch(console.error);

        return () => {
            manager.destroy();
            managerRef.current = null;
        };
    }, []);

    return (
        <div style={{ position: 'relative', width: '100%', height: '100%' }}>
            {error && (
                <div style={{
                    position: 'absolute', top: 8, left: '50%', transform: 'translateX(-50%)',
                    background: '#cc2222', color: 'white', padding: '4px 16px',
                    borderRadius: 6, fontSize: 12, zIndex: 10,
                }}>
                    ⚠ Sync error: {error}
                </div>
            )}
            <canvas
                ref={canvasRef}
                style={{ width: '100%', height: '100%', display: 'block' }}
            />
        </div>
    );
}
