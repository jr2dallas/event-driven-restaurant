/** @type {import('tailwindcss').Config} */
export default {
    content: [
        "./index.html",
        "./src/**/*.{js,ts,jsx,tsx}",
    ],
    theme: {
        extend: {
            colors: {
                'cyber-dark': '#1a0f2e',
                'cyber-purple': '#2a1b4a',
                'neon-pink': '#ff6b9b',
                'glass': 'rgba(255,255,255,0.05)',
            },
            backdropBlur: {
                xs: '2px',
            }
        },
    },
    plugins: [],
    darkMode: 'class',
}