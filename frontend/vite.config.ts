import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // DEV_PROXY_TARGET is read from .env files / the shell but never exposed to the browser bundle.
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.DEV_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [react()],
    server: {
      port: 5173,
      // Same-origin in development: the browser talks to Vite, Vite forwards to the backend.
      proxy: {
        '/api': { target, changeOrigin: true },
        // Order matters: the more specific SockJS prefix must come before /ws.
        '/ws-sockjs': { target, changeOrigin: true, ws: true },
        '/ws': { target, changeOrigin: true, ws: true },
      },
    },
    preview: {
      proxy: {
        '/api': { target, changeOrigin: true },
        '/ws-sockjs': { target, changeOrigin: true, ws: true },
        '/ws': { target, changeOrigin: true, ws: true },
      },
    },
  }
})
