/// <reference types="vite/client" />

/**
 * Build-time configuration injected by Vite from the environment (`.env` files
 * locally, the service's env vars on Render). Only `VITE_`-prefixed variables
 * are exposed to the bundle — see https://vitejs.dev/guide/env-and-mode.
 */
interface ImportMetaEnv {
  /**
   * Origin of the backend, e.g. "https://springbootcrmbackend.onrender.com".
   * Unset -> src/api/config.ts falls back to <this host>:8080 (dev default).
   * Set to "" -> same-origin requests, for when a proxy fronts both.
   */
  readonly VITE_API_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
