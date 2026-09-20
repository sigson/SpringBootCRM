// Універсальний fetch-обгортки з:
//   * cookie-based credentials (за замовчуванням);
//   * Bearer-токеном з localStorage (для випадків, коли cookie не доступне);
//   * автоматичним redirect на /login при отриманні 401;
//   * парсингом тіла помилки в типізований {@link ErrorEnvelope}.

import type { ErrorEnvelope, ErrorKind } from "../types/api";
import { apiUrl } from "./config";

const TOKEN_KEY = "springbootcrm_token";

// Глобальний колбек — встановлюється в AuthProvider'і; при 401 викликається
// для редіректу на /login без потреби location.href = ...
let onUnauthorized: (() => void) | null = null;

export function setUnauthorizedHandler(fn: (() => void) | null) {
  onUnauthorized = fn;
}

// Глобальний колбек для показу uniform error-dialog (встановлюється в
// ErrorDialogProvider). Не всі помилки треба показувати модально —
// контекстні (form-level VALIDATION з conflict-кодом) обробляються локально;
// тому api.client'ом це не викликається сам собою — це робиться у пейджах
// через {@code useErrorDialog().showFromError(err)}.

export function getStoredToken(): string | null {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
}

export function setStoredToken(token: string | null) {
  try {
    if (token == null) localStorage.removeItem(TOKEN_KEY);
    else localStorage.setItem(TOKEN_KEY, token);
  } catch {  }
}

export interface FetchOptions {
  /** Не виводити 401 в onUnauthorized — використовується самим AuthProvider'ом. */
  silentOn401?: boolean;
}

async function rawFetch<T>(
  path: string,
  init: RequestInit,
  opts: FetchOptions = {}
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  const token = getStoredToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const resp = await fetch(apiUrl(path), {
    ...init,
    headers,
    credentials: "include",
  });

  if (resp.status === 401 && !opts.silentOn401) {
    setStoredToken(null);
    if (onUnauthorized) onUnauthorized();
  }

  if (resp.status === 204) {
    return undefined as unknown as T;
  }

  const text = await resp.text();
  let body: unknown = null;
  if (text) {
    try { body = JSON.parse(text); }
    catch { body = text; }
  }

  if (!resp.ok) {
    // Парсимо в типізований ErrorEnvelope.
    // Backend завжди шле new shape, але старий код, що йшов через дефолтні Spring-handler'и
    // (наприклад, 404 на неіснуючий route), може повернути spring-дефолт shape — нормалізуємо.
    throw normalizeError(body, resp.status, path);
  }

  return body as T;
}

/**
 * Нормалізує body відповіді з помилкою в {@link ErrorEnvelope}.
 * Підтримує:
 *   - сучасний envelope-shape (приходить з ErrorEnvelopeAdvice);
 *   - legacy {error, field} shape (fail-safe);
 *   - не-JSON body (наприклад, текстове 502 від reverse-proxy).
 */
function normalizeError(body: unknown, status: number, path: string): ErrorEnvelope {
  if (body && typeof body === "object") {
    const obj = body as Record<string, unknown>;
    // Новий shape — має `kind`. Просто приводимо до типу.
    if (typeof obj.kind === "string") {
      return body as ErrorEnvelope;
    }
    // Legacy shape — мапимо в новий.
    const legacyError = typeof obj.error === "string" ? obj.error : null;
    const legacyMsg   = typeof obj.message === "string" ? obj.message : null;
    const legacyField = typeof obj.field === "string"   ? obj.field   : null;
    const inferredKind: ErrorKind =
        status === 401 ? "UNAUTHORIZED"
      : status === 403 ? "ACCESS_DENIED"
      : status === 404 ? "NOT_FOUND"
      : status === 409 ? "CONFLICT"
      : status === 400 ? (legacyField ? "VALIDATION" : "BAD_REQUEST")
      : status >= 500  ? "INTERNAL"
      :                  "BAD_REQUEST";
    return {
      kind: inferredKind,
      status,
      message: legacyError || legacyMsg || `Error ${status}`,
      path,
      fieldErrors: legacyField ? [{ field: legacyField, message: legacyError || "Invalid value" }] : undefined,
    };
  }
  // Non-JSON body (текст, або null).
  return {
    kind: status === 401 ? "UNAUTHORIZED"
        : status === 403 ? "ACCESS_DENIED"
        : status === 404 ? "NOT_FOUND"
        : status >= 500  ? "INTERNAL"
        :                  "BAD_REQUEST",
    status,
    message: typeof body === "string" && body.length > 0 ? body : `Error ${status}`,
    path,
  };
}

export const api = {
  get: <T>(path: string, opts?: FetchOptions) =>
    rawFetch<T>(path, { method: "GET" }, opts),
  post: <T>(path: string, body?: unknown, opts?: FetchOptions) =>
    rawFetch<T>(path, { method: "POST", body: JSON.stringify(body ?? {}) }, opts),
  put: <T>(path: string, body?: unknown, opts?: FetchOptions) =>
    rawFetch<T>(path, { method: "PUT", body: JSON.stringify(body ?? {}) }, opts),
  patch: <T>(path: string, body?: unknown, opts?: FetchOptions) =>
    rawFetch<T>(path, { method: "PATCH", body: JSON.stringify(body ?? {}) }, opts),
  delete: <T = void>(path: string, opts?: FetchOptions) =>
    rawFetch<T>(path, { method: "DELETE" }, opts),
};

/**
 * Перевіряє, чи це {@link ErrorEnvelope}. Зручно для catch-блоків:
 * {@code if (isErrorEnvelope(err) && err.kind === "ACCESS_DENIED") ... }
 */
export function isErrorEnvelope(x: unknown): x is ErrorEnvelope {
  return !!x && typeof x === "object" && typeof (x as Record<string, unknown>).kind === "string"
              && typeof (x as Record<string, unknown>).status === "number";
}
