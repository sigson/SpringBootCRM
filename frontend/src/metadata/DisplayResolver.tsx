import {
  createContext, useCallback, useContext, useEffect, useRef,
  useState, type ReactNode,
} from "react";
import { referencesApi } from "../api/endpoints";
import { isErrorEnvelope } from "../api/client";
import type { ResolvedRef } from "../types/api";
import { useAuth } from "../auth/AuthProvider";

/**
 * Єдиний підпис, який показуємо замість display'у, коли у користувача немає
 * доступу до ссилкового значення (репозиторій/екземпляр недоступний). Винесено в
 * константу, щоб усі споживачі (списки, picker'и, поля вводу) показували
 * однаковий текст.
 */
export const NO_ACCESS_LABEL = "[No access]";

/**
 * Stateful provider, що тримає LRU-подібний кеш {@code (typeId,id) → ResolvedRef}
 * та батчить запити resolve'у через {@code POST /api/references/resolve}.
 *
 * <p>Гарантує, що жоден запит не йде до завершення authProvider'а — інакше
 * 401 при першому рендері «забруднював» консоль, а компоненти повторно ставили
 * запити (інфініт-loop).
 *
 * <p>При logout'і {@link useEffect} нижче автоматично очищає весь кеш — щоб
 * наступний користувач не побачив display'і, до яких не має доступу.
 */

const CACHE_LIMIT = 2000;

type CacheKey = string;

interface ResolverContext {
  resolve(typeId: number, id: string | null | undefined): ResolvedRef | null;
  /**
   * Зручний хелпер: повертає текст для відображення ссилки з урахуванням
   * прав доступу:
   *   • {@code null} — ще резолвиться (показати UUID/спінер);
   *   • {@link NO_ACCESS_LABEL} — резолв повернув {@code accessible=false};
   *   • інакше — людиночитабельний display.
   */
  displayOf(typeId: number, id: string | null | undefined): string | null;
  invalidate(typeId: number, id: string): void;
  clear(): void;
}

const ResolverCtx = createContext<ResolverContext | undefined>(undefined);

function mkKey(typeId: number, id: string): CacheKey {
  return `${typeId}:${id}`;
}

/** Локальний «недоступно»-плейсхолдер (коли резолв повернув 403 на весь батч). */
function inaccessiblePlaceholder(typeId: number, id: string): ResolvedRef {
  return {
    typeId, id,
    display: NO_ACCESS_LABEL,
    code: null, name: null,
    accessible: false,
    createdBy: null, updatedBy: null,
  };
}

export function DisplayResolverProvider({ children }: { children: ReactNode }) {
  const { user, loading: authLoading } = useAuth();
  const cache = useRef(new Map<CacheKey, ResolvedRef>());
  const pendingQueue = useRef<Set<CacheKey>>(new Set());
  const inFlightKeys = useRef<Set<CacheKey>>(new Set());
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const canQuery = useRef<boolean>(false);
  const [, forceRender] = useState(0);

  // Реєструємо стан auth у ref'і — flushQueue побачить актуальне значення без перерендерів
  useEffect(() => {
    canQuery.current = !authLoading && !!user;
    // Очищаємо кеш при logout або зміні користувача — щоб display'і чужих
    // об'єктів не залишалися
    if (!user) {
      cache.current.clear();
      pendingQueue.current.clear();
      inFlightKeys.current.clear();
    } else {
      // При появі (або зміні) користувача — якщо є щось у черзі, спробуємо flush
      if (pendingQueue.current.size > 0) {
        if (debounceTimer.current) clearTimeout(debounceTimer.current);
        debounceTimer.current = setTimeout(() => void flushQueue(), 10);
      }
    }
    forceRender(x => x + 1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.id, authLoading]);

  const triggerRender = useCallback(() => forceRender(x => x + 1), []);

  const flushQueue = useCallback(async () => {
    if (!canQuery.current) return;
    if (pendingQueue.current.size === 0) return;
    const batch = Array.from(pendingQueue.current);
    pendingQueue.current.clear();
    batch.forEach(k => inFlightKeys.current.add(k));
    const keys = batch.map(k => {
      const [typeIdRaw, ...idParts] = k.split(":");
      return { typeId: Number(typeIdRaw), id: idParts.join(":") };
    });
    try {
      const resolved = await referencesApi.resolve(keys);
      for (const r of resolved) {
        cache.current.set(mkKey(r.typeId, r.id), r);
        if (cache.current.size > CACHE_LIMIT) {
          const first = cache.current.keys().next().value;
          if (first) cache.current.delete(first);
        }
      }
    } catch (err) {
      // Access-denied на весь батч — кешуємо «недоступно»-плейсхолдери для всіх
      // ключів, щоб не зациклити запит. Запобіжник: сучасний бекенд резолвить
      // недоступні ключі по-одному (accessible=false), не віддаючи 403 на батч.
      if (isErrorEnvelope(err) && err.kind === "ACCESS_DENIED") {
        for (const k of keys) {
          cache.current.set(mkKey(k.typeId, k.id), inaccessiblePlaceholder(k.typeId, k.id));
        }
      }
      // Для інших помилок (мережа/500) — нічого не кешуємо; повторні виклики
      // поставлять ключ у чергу знову (транзієнтна помилка має право на retry).
    } finally {
      batch.forEach(k => inFlightKeys.current.delete(k));
      triggerRender();
    }
  }, [triggerRender]);

  const enqueue = useCallback((typeId: number, id: string) => {
    const k = mkKey(typeId, id);
    if (cache.current.has(k) || inFlightKeys.current.has(k)) return;
    pendingQueue.current.add(k);
    if (!canQuery.current) return;   // ще не залогінений — flush відбудеться пізніше
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(() => void flushQueue(), 10);
  }, [flushQueue]);

  const resolve = useCallback((typeId: number, id: string | null | undefined): ResolvedRef | null => {
    if (!id) return null;
    const k = mkKey(typeId, id);
    const cached = cache.current.get(k);
    if (cached) return cached;
    enqueue(typeId, id);
    return null;
  }, [enqueue]);

  const displayOf = useCallback((typeId: number, id: string | null | undefined): string | null => {
    if (!id) return null;
    const r = resolve(typeId, id);
    if (!r) return null;                         // ще резолвиться
    if (r.accessible === false) return NO_ACCESS_LABEL;
    return r.display;
  }, [resolve]);

  const invalidate = useCallback((typeId: number, id: string) => {
    cache.current.delete(mkKey(typeId, id));
    triggerRender();
  }, [triggerRender]);

  const clear = useCallback(() => {
    cache.current.clear();
    triggerRender();
  }, [triggerRender]);

  useEffect(() => {
    return () => {
      if (debounceTimer.current) clearTimeout(debounceTimer.current);
    };
  }, []);

  return (
    <ResolverCtx.Provider value={{ resolve, displayOf, invalidate, clear }}>
      {children}
    </ResolverCtx.Provider>
  );
}

export function useDisplayResolver(): ResolverContext {
  const ctx = useContext(ResolverCtx);
  if (!ctx) throw new Error("useDisplayResolver must be used inside DisplayResolverProvider");
  return ctx;
}
