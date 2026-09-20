import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { useNavigate, useLocation, Navigate } from "react-router-dom";
import {
  setUnauthorizedHandler,
  setStoredToken,
  getStoredToken,
} from "../api/client";
import { authApi } from "../api/endpoints";
import { isAdmin } from "./accessFlags";
import type { UserDto } from "../types/api";

export { isAdmin };

interface AuthState {
  user: UserDto | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  register: (username: string, email: string, displayName: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
}

const AuthCtx = createContext<AuthState | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserDto | null>(null);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();
  const location = useLocation();

  // Глобальний 401-handler: при втраті аутентифікації редіректить на /login.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser(null);
      // Зберігаємо поточну адресу — щоб після логіну повернутися
      const from = location.pathname + location.search;
      navigate("/login", { replace: true, state: { from } });
    });
    return () => setUnauthorizedHandler(null);
  }, [navigate, location]);

  // На першому mount'і пробуємо отримати /me, щоб понять чи є валідна сесія
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const me = await authApi.me();
        if (!cancelled) setUser(me);
      } catch {
        if (!cancelled) setUser(null);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const login = async (username: string, password: string) => {
    const r = await authApi.login(username, password);
    setStoredToken(r.token);
    const me = await authApi.me();
    setUser(me);
  };

  const register = async (username: string, email: string, displayName: string, password: string) => {
    const r = await authApi.register(username, email, displayName, password);
    setStoredToken(r.token);
    const me = await authApi.me();
    setUser(me);
  };

  const logout = async () => {
    try { await authApi.logout(); } catch {  }
    setStoredToken(null);
    setUser(null);
    navigate("/login", { replace: true });
  };

  const refresh = async () => {
    try { setUser(await authApi.me()); } catch { setUser(null); }
  };

  return (
    <AuthCtx.Provider value={{ user, loading, login, register, logout, refresh }}>
      {children}
    </AuthCtx.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthCtx);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}

/** Wrapper-route — пропускає тільки автентифікованих. */
export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) {
    return (
      <div style={{ padding: 48, color: "var(--text-muted)" }}>Loading…</div>
    );
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}
