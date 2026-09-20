import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Проксі більше НЕ потрібен: фронтенд звертається напряму до бекенду за
// абсолютним URL із src/api/config.ts (window.__API_BASE__). CORS вмикається
// на бекенді. Тут лишаються тільки порти dev-сервера та preview.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
  },
  preview: {
    port: 4173,
  },
});
