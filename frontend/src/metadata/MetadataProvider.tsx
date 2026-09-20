import {
  createContext, useContext, useEffect, useState, type ReactNode,
} from "react";
import { metadataApi } from "../api/endpoints";
import type { TypeDescriptor } from "../types/api";
import { useAuth } from "../auth/AuthProvider";

interface MetadataContext {
  /** Завантажений з backend'а реєстр доменних типів. */
  types: TypeDescriptor[];
  /** Чи готовий контекст до використання (метадані завантажені, або auth завершено). */
  ready: boolean;
  /** Помилка завантаження (rare). */
  error: string | null;
  byTypeId: (typeId: number) => TypeDescriptor | null;
  bySlug: (slug: string) => TypeDescriptor | null;
}

const MetadataCtx = createContext<MetadataContext | undefined>(undefined);

/**
 * Завантажує метадані типів — <b>тільки</b> після того, як AuthProvider підтвердив
 * наявність користувача. Це усуває race condition: при першому mount'і
 * запит {@code /api/metadata/types} проходив до того, як auth-куки приходили,
 * і повертався 401 → дашборд залишався порожнім без помилки в консолі.
 */
export function MetadataProvider({ children }: { children: ReactNode }) {
  const { user, loading: authLoading } = useAuth();
  const [types, setTypes] = useState<TypeDescriptor[]>([]);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Чекаємо завершення auth-перевірки.
    if (authLoading) return;
    // Якщо користувача немає — нічого не вантажимо. ProtectedRoute redirect'не на /login.
    if (!user) {
      setTypes([]);
      setReady(true);
      return;
    }
    let cancelled = false;
    setReady(false);
    setError(null);
    (async () => {
      try {
        const t = await metadataApi.types();
        if (!cancelled) {
          setTypes(t);
          setReady(true);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Metadata loading error");
          setReady(true);
        }
      }
    })();
    return () => { cancelled = true; };
  }, [user?.id, authLoading]);

  const byTypeId = (typeId: number) =>
    types.find(t => t.typeId === typeId) ?? null;
  const bySlug = (slug: string) =>
    types.find(t => t.slug === slug) ?? null;

  return (
    <MetadataCtx.Provider value={{ types, ready, error, byTypeId, bySlug }}>
      {children}
    </MetadataCtx.Provider>
  );
}

export function useMetadata(): MetadataContext {
  const ctx = useContext(MetadataCtx);
  if (!ctx) throw new Error("useMetadata must be used inside MetadataProvider");
  return ctx;
}
