import {
  createContext, useCallback, useContext, useState, type ReactNode,
} from "react";

/**
 * Toast-сповіщення про результат операцій.
 *
 * <p>ТЗ1 §5.4 і ТЗ2 §3.1: «Виводиться повідомлення про успішне створення
 * запису». Реалізується через невелику стейк-подобу плашку справа знизу,
 * автоматично згасає через 3.5 сек.
 *
 * <p>Усі повідомлення накопичуються в стек — нові з'являються знизу, попередні
 * залишаються видимими, тому одна швидка послідовність CRUD-операцій не глитає
 * перші повідомлення.
 */

export interface ToastAction {
  /** Текст кликабельного посилання у плашці. */
  label: string;
  /** Обробник кліку (наприклад, відкрити форму збереженого об'єкта). */
  onClick: () => void;
}

export interface Toast {
  id: string;
  kind: "success" | "info" | "error" | "warning";
  message: string;
  /** Необов'язкове кликабельне посилання (ссилкове представлення об'єкта). */
  action?: ToastAction;
}

interface ToastCtxValue {
  push(t: Omit<Toast, "id">): void;
  success(message: string, action?: ToastAction): void;
  info(message: string, action?: ToastAction): void;
  error(message: string, action?: ToastAction): void;
  warning(message: string, action?: ToastAction): void;
}

const ToastCtx = createContext<ToastCtxValue | undefined>(undefined);

const ICONS: Record<Toast["kind"], string> = {
  success: "✓",
  info:    "ℹ",
  error:   "✕",
  warning: "⚠",
};

export function ToastProvider({
  children, durationMs = 3500,
}: { children: ReactNode; durationMs?: number }) {
  const [items, setItems] = useState<Toast[]>([]);

  const push = useCallback((t: Omit<Toast, "id">) => {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2,7)}`;
    setItems(prev => [...prev, { ...t, id }]);
    window.setTimeout(() => {
      setItems(prev => prev.filter(x => x.id !== id));
    }, durationMs);
  }, [durationMs]);

  const value: ToastCtxValue = {
    push,
    success: (m, action) => push({ kind: "success", message: m, action }),
    info:    (m, action) => push({ kind: "info",    message: m, action }),
    error:   (m, action) => push({ kind: "error",   message: m, action }),
    warning: (m, action) => push({ kind: "warning", message: m, action }),
  };

  return (
    <ToastCtx.Provider value={value}>
      {children}
      <div className="toast-stack" aria-live="polite">
        {items.map(t => (
          <div key={t.id}
               className={`toast toast--${t.kind}`}
               role={t.kind === "error" ? "alert" : "status"}>
            <span className="toast__icon">{ICONS[t.kind]}</span>
            <span className="toast__body">
              <span className="toast__msg">{t.message}</span>
              {t.action && (
                <button
                  type="button"
                  className="toast__link"
                  onClick={() => {
                    // Виконуємо дію (зазвичай — відкрити форму об'єкта) і ховаємо плашку.
                    t.action!.onClick();
                    setItems(prev => prev.filter(x => x.id !== t.id));
                  }}
                  title="Open object"
                >{t.action.label}</button>
              )}
            </span>
            <button
              className="toast__close"
              onClick={() => setItems(prev => prev.filter(x => x.id !== t.id))}
              aria-label="Close notification"
              title="Close"
            >✕</button>
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

export function useToast(): ToastCtxValue {
  const ctx = useContext(ToastCtx);
  if (!ctx) throw new Error("useToast must be used inside ToastProvider");
  return ctx;
}
