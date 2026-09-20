import {
  createContext, useContext, useEffect, useState, type ReactNode,
} from "react";
import { navigationApi } from "../api/navigation";
import type { NavNode } from "../types/navigation";
import { useAuth } from "../auth/AuthProvider";

/**
 * Постачальник дерева навігації. Джерело істини — backend {@code GET /api/navigation}:
 * він уже вибрав потрібний layout (призначений інтерфейс або згенерований дефолт) і
 * відсік недоступні гілки за правами. Фронт лише рендерить.
 *
 * <p>Завантажується тільки після завершення auth (як {@code MetadataProvider}),
 * щоб не ловити 401 на першому mount'і.
 */
interface NavContextValue {
  tree: NavNode[];
  ready: boolean;
  error: string | null;
  reload: () => void;
}

const NavCtx = createContext<NavContextValue | undefined>(undefined);

export function NavProvider({ children }: { children: ReactNode }) {
  const { user, loading: authLoading } = useAuth();
  const [tree, setTree] = useState<NavNode[]>([]);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    if (authLoading) return;
    if (!user) { setTree([]); setReady(true); return; }
    let cancelled = false;
    setReady(false);
    setError(null);
    (async () => {
      try {
        const t = await navigationApi.tree();
        if (!cancelled) { setTree(t); setReady(true); }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Navigation loading error");
          setReady(true);
        }
      }
    })();
    return () => { cancelled = true; };
  }, [user?.id, authLoading, version]);

  return (
    <NavCtx.Provider value={{ tree, ready, error, reload: () => setVersion(v => v + 1) }}>
      {children}
    </NavCtx.Provider>
  );
}

export function useNav(): NavContextValue {
  const ctx = useContext(NavCtx);
  if (!ctx) throw new Error("useNav must be used inside NavProvider");
  return ctx;
}
