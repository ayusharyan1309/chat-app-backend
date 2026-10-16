/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Optional REST base URL. Empty = same origin (recommended, uses the dev proxy). */
  readonly VITE_API_BASE?: string
  /** Optional STOMP WebSocket URL override. */
  readonly VITE_WS_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
