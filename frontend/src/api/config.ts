//  Адреса бекенду (jar).
//
//  Фронтенд ходить на бекенд напряму, за абсолютним URL, БЕЗ vite-proxy —
//  тому origin бекенду треба звідкись узяти. Джерела, за спаданням пріоритету:
//
//    1) window.__API_BASE__ — правиться ПРЯМО у зібраному dist/index.html,
//       без перезбірки. Аварійний важіль для ops: перенесли бекенд на інший
//       хост — поправили один рядок у статиці.
//    2) import.meta.env.VITE_API_BASE — зашивається під час збірки. Саме це
//       використовує деплой на Render: статика збирається один раз і знає
//       URL свого Web Service (див. render.yaml).
//    3) той самий хост, порт 8080 — dev-режим за замовчуванням: `npm run dev`
//       на :5173, jar на :8080, обидва на localhost чи на LAN-адресі.
//
//  Явний порожній рядок (VITE_API_BASE="") → same-origin режим: запити підуть
//  туди ж, звідки віддано фронтенд. Це потрібно, якщо статику роздає сам
//  бекенд або reverse-proxy.

declare global {
  interface Window {
    __API_BASE__?: string;
  }
}

const RUNTIME_OVERRIDE =
  typeof window !== "undefined" ? window.__API_BASE__ : undefined;

const BUILD_TIME_BASE = import.meta.env.VITE_API_BASE as string | undefined;

// Протокол беремо зі сторінки, а не хардкодимо "http://". Інакше сторінка,
// віддана по HTTPS, просить http://…:8080 — і браузер блокує це як mixed
// content, повідомленням, яке виглядає як помилка мережі, а не як «забули
// налаштувати збірку».
const DEV_DEFAULT =
  typeof window !== "undefined"
    ? window.location.protocol + "//" + window.location.hostname + ":8080"
    : "";

// ?? а не || — щоб VITE_API_BASE="" означало «same-origin», а не «не задано».
const resolved = RUNTIME_OVERRIDE ?? BUILD_TIME_BASE ?? DEV_DEFAULT;

// Дефолт ":8080 на цьому ж хості" осмислений лише локально. Якщо сторінку
// віддано по HTTPS і при цьому ні window.__API_BASE__, ні VITE_API_BASE не
// задані — це розгортання, зібране без конфігурації, і запити підуть на
// неіснуючий порт власного домену. Мовчати про це дорого: симптом
// (mixed content або «Failed to fetch») не вказує на причину.
if (
  typeof window !== "undefined" &&
  window.location.protocol === "https:" &&
  RUNTIME_OVERRIDE === undefined &&
  BUILD_TIME_BASE === undefined
) {
  console.error(
    "[config] VITE_API_BASE не було задано під час збірки, тому клієнт " +
      "звертається до " + DEV_DEFAULT + " — майже напевно це не той бекенд.\n" +
      "Задайте VITE_API_BASE у середовищі збірки (на Render — змінна оточення " +
      "статичного сайту) і перезберіть; збірка без очищення кешу може віддати " +
      "старий бандл. Разовий обхід без перезбірки: розкоментуйте " +
      "window.__API_BASE__ у dist/index.html. Див. docs/DEPLOY.md."
  );
}

/** Базовий origin бекенду без кінцевого слеша, напр. "http://localhost:8080". */
export const API_BASE: string = resolved.replace(/\/+$/, "");

/**
 * Перетворює відносний шлях ("/api/...") на абсолютний URL до бекенду.
 * Абсолютні URL (http/https) повертаються без змін.
 */
export function apiUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) return path;
  if (!API_BASE) return path; // same-origin режим
  return API_BASE + (path.startsWith("/") ? path : "/" + path);
}
