import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

// https://vite.dev/config/
export default defineConfig({
    plugins: [react()],
    resolve: {
        alias: {
            "@": path.resolve(__dirname, "./src"),
        },
    },
    server: {
        port: 3000,
        proxy: {
            // Proxy vers ton back restaurant
            "/internal": {
                target: "http://host.docker.internal:8080",
                changeOrigin: true,
                secure: false
            }
        }
    },
    define: {
        global: 'globalThis', // PixiJS
    },
    optimizeDeps: {
        include: ['pixi.js', 'eventemitter3'],
    }
})