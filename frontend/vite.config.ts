import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // DEV_PROXY_TARGET is read from .env files / the shell but never exposed to the browser bundle.
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.DEV_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [react()],
    build: {
      rolldownOptions: {
        output: {
          // Named vendor chunks (instead of whichever module happened to be shared first) so
          // bundle reports and cache invalidation are readable. `codeSplitting` supersedes the
          // deprecated `advancedChunks` / `manualChunks` in Vite 8 (Rolldown).
          codeSplitting: {
            groups: [
              { name: 'react', test: /node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler)[\\/]/, priority: 40 },
              {
                name: 'charts',
                test: /node_modules[\\/](recharts|victory-vendor|d3-[^\\/]+|internmap|decimal\.js-light|eventemitter3|es-toolkit|immer|reselect|redux|redux-thunk|@reduxjs[\\/]toolkit|react-redux|react-is|tiny-invariant|use-sync-external-store|clsx)[\\/]/,
                priority: 30,
              },
              { name: 'stomp', test: /node_modules[\\/]@stomp[\\/]/, priority: 30 },
              { name: 'icons', test: /node_modules[\\/]lucide-react[\\/]/, priority: 30 },
              { name: 'vendor', test: /node_modules[\\/]/, priority: 10 },
            ],
          },
        },
      },
    },
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
