import {
  createContext, useCallback, useContext, useRef, useState, type ReactNode,
} from "react";

/**
 * Заміна native `window.confirm()` стилізованою модалкою.
 *
 * <p>Native confirm:
 * <ul>
 *   <li>не відповідає темізації застосунку;</li>
 *   <li>на macOS виглядає як OS-alert (не локалізується);</li>
 *   <li>блокує JavaScript-event-loop (хоч і не у webview).</li>
 * </ul>
 *
 * <p>Використання через хук:
 * <pre>
 *   const confirm = useConfirm();
 *   if (await confirm({ title: "Видалити?", message: "Дія незворотна" })) {
 *     await api.delete(...);
 *   }
 * </pre>
 */

export interface ConfirmOptions {
  title?: string;
  message: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  kind?: "default" | "danger";
}

type Pending = {
  opts: ConfirmOptions;
  resolve: (yes: boolean) => void;
};

const ConfirmCtx = createContext<((opts: ConfirmOptions) => Promise<boolean>) | undefined>(undefined);

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null);
  const pendingRef = useRef<Pending | null>(null);

  const confirm = useCallback((opts: ConfirmOptions) => {
    return new Promise<boolean>((resolve) => {
      const p: Pending = { opts, resolve };
      pendingRef.current = p;
      setPending(p);
    });
  }, []);

  function answer(yes: boolean) {
    const p = pendingRef.current;
    if (p) { p.resolve(yes); pendingRef.current = null; }
    setPending(null);
  }

  return (
    <ConfirmCtx.Provider value={confirm}>
      {children}
      {pending && (
        <div className="lv-dialog-backdrop" onClick={() => answer(false)}>
          <div
            className="lv-dialog lv-dialog--narrow"
            onClick={e => e.stopPropagation()}
          >
            <header className="lv-dialog__header">
              <h3>{pending.opts.title ?? "Confirmation"}</h3>
              <button className="icon-btn" onClick={() => answer(false)}
                      title="Close (Esc)" aria-label="Cancel">✕</button>
            </header>
            <div className="lv-dialog__body">
              <div style={{ padding: "8px 4px" }}>{pending.opts.message}</div>
            </div>
            <footer className="lv-dialog__footer">
              <button className="btn" onClick={() => answer(false)} autoFocus>
                {pending.opts.cancelLabel ?? "Cancel"}
              </button>
              <button
                className={`btn ${pending.opts.kind === "danger" ? "btn--danger" : "btn--primary"}`}
                onClick={() => answer(true)}
              >
                {pending.opts.confirmLabel ?? "Yes"}
              </button>
            </footer>
          </div>
        </div>
      )}
    </ConfirmCtx.Provider>
  );
}

export function useConfirm(): (opts: ConfirmOptions) => Promise<boolean> {
  const ctx = useContext(ConfirmCtx);
  if (!ctx) throw new Error("useConfirm must be used inside ConfirmProvider");
  return ctx;
}
