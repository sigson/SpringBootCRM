import {
  createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode,
} from "react";

/**
 * Стек субокон (LIFO).
 *
 * <p>Концепція: основне «вікно» — це поточна сторінка з URL-routing'ом
 * (наприклад {@code /users}). Над нею може бути 0..N «субокон»:
 * <ul>
 *   <li>модальний редактор конкретного об'єкту (відкритий через клік «Створити»);</li>
 *   <li>RefPicker — обрати ссилкове значення;</li>
 *   <li>сабредактор всередині picker'а (для редагування обраного значення).</li>
 * </ul>
 *
 * <p>Якщо стек має 2+ вікон, користувач повинен закрити верхнє щоб побачити
 * попереднє — як у 1С (модальний flow).
 *
 * <p>Кожне субокно — це React-вузол з власним заголовком та опціональним
 * footer'ом. {@link WindowStackProvider} рендерить ВСІ субокна в стеку,
 * але показує лише останнє через CSS (z-index).
 */

export interface SubWindow {
  /** Стабільний унікальний ID — для key prop'у в React. */
  id: string;
  /** Заголовок субокна. */
  title: string;
  /** Зміст. */
  content: ReactNode;
  /** Опціональний footer (кнопки). */
  footer?: ReactNode;
  /** Ширина модала. */
  width?: "narrow" | "default" | "wide" | "full";
  /** Чи дозволити закриття через клік по backdrop'у (default: false). */
  closeOnBackdrop?: boolean;
  /** Hook: викликається при намаганні закрити (повертає false → відмінити). */
  onBeforeClose?: () => boolean | Promise<boolean>;
  /**
   * Hook: викликається ОДИН раз, коли вікно фактично залишило стек (через ✕/Esc,
   * {@code closeTop} або {@code closeById}). Використовується, зокрема, для
   * синхронізації адресного рядка (скинути {@code :recordId} зі списку незалежно
   * від того, яким способом вікно закрили).
   */
  onClosed?: () => void;
}

interface WindowStackContext {
  stack: SubWindow[];
  /** Відкриває нове субокно поверх стеку. */
  open(w: SubWindow): void;
  /** Закриває верхнє субокно (з онбекіоркклоуз гачком). */
  closeTop(): void;
  /** Закриває конкретне субокно (наприклад, після save). */
  closeById(id: string): void;
  /** Чистить весь стек (рідко — наприклад, при logout). */
  clear(): void;
  /** Замінює верхнє вікно (для «provalivation»). */
  replaceTop(w: SubWindow): void;
}

const WindowStackCtx = createContext<WindowStackContext | undefined>(undefined);

let idCounter = 0;
export function nextWindowId(): string {
  return `win-${++idCounter}-${Date.now()}`;
}

export function WindowStackProvider({ children }: { children: ReactNode }) {
  const [stack, setStack] = useState<SubWindow[]>([]);
  // Дзеркало стеку для обробників подій: дозволяє прочитати поточний верх/вікно
  // та викликати {@code onClosed} рівно один раз (обробники подій, на відміну від
  // updater'ів setState, не подвоюються у StrictMode).
  const stackRef = useRef<SubWindow[]>(stack);
  stackRef.current = stack;

  const open = useCallback((w: SubWindow) => {
    setStack(prev => [...prev, w]);
  }, []);

  const closeTop = useCallback(() => {
    const top = stackRef.current[stackRef.current.length - 1];
    if (!top) return;
    const finish = () => {
      setStack(p => p.slice(0, -1));
      top.onClosed?.();
    };
    if (top.onBeforeClose) {
      // Async closing: чекаємо рішення хука, потім закриваємо.
      Promise.resolve(top.onBeforeClose()).then(canClose => {
        if (canClose !== false) finish();
      });
      return;
    }
    finish();
  }, []);

  const closeById = useCallback((id: string) => {
    const w = stackRef.current.find(x => x.id === id);
    setStack(prev => prev.filter(x => x.id !== id));
    w?.onClosed?.();
  }, []);

  const clear = useCallback(() => setStack([]), []);

  const replaceTop = useCallback((w: SubWindow) => {
    setStack(prev => prev.length === 0 ? [w] : [...prev.slice(0, -1), w]);
  }, []);

  return (
    <WindowStackCtx.Provider value={{ stack, open, closeTop, closeById, clear, replaceTop }}>
      {children}
      <WindowStackRenderer stack={stack} onCloseTop={closeTop} />
    </WindowStackCtx.Provider>
  );
}

export function useWindowStack(): WindowStackContext {
  const ctx = useContext(WindowStackCtx);
  if (!ctx) throw new Error("useWindowStack must be used inside WindowStackProvider");
  return ctx;
}

function WindowStackRenderer({
  stack, onCloseTop,
}: { stack: SubWindow[]; onCloseTop: () => void }) {
  useEffect(() => {
    if (stack.length === 0) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCloseTop();
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [stack.length, onCloseTop]);

  if (stack.length === 0) return null;
  const top = stack[stack.length - 1]!;

  return (
    <div className="window-stack-root">
      {stack.map((w, idx) => {
        const isTop = idx === stack.length - 1;
        const depth = stack.length - 1 - idx;
        return (
          <div
            key={w.id}
            className={`subwindow-backdrop ${isTop ? "subwindow-backdrop--top" : ""}`}
            style={{
              zIndex: 100 + idx * 2,
              filter: isTop ? undefined : `brightness(${1 - depth * 0.05}) blur(${depth}px)`,
              pointerEvents: isTop ? "auto" : "none",
            }}
            onClick={isTop && top.closeOnBackdrop ? () => onCloseTop() : undefined}
          >
            <div
              className={`subwindow subwindow--${w.width ?? "default"}`}
              onClick={e => e.stopPropagation()}
            >
              <div className="subwindow__header">
                <div className="subwindow__title-wrap">
                  {isTop && stack.length > 1 && (
                    <div className="subwindow__breadcrumb" title="Path in the window stack">
                      {stack.map((sw, j) => (
                        <span key={sw.id}>
                          {j > 0 && <span className="subwindow__bc-sep">›</span>}
                          <span
                            className={j === stack.length - 1
                              ? "subwindow__bc-cur"
                              : "subwindow__bc-prev"}
                          >
                            {sw.title}
                          </span>
                        </span>
                      ))}
                    </div>
                  )}
                  <h2 className="subwindow__title">{w.title}</h2>
                </div>
                {isTop && (
                  <button
                    className="icon-btn"
                    onClick={onCloseTop}
                    aria-label="Close window"
                    title="Close (ESC)"
                  >✕</button>
                )}
              </div>
              <div className="subwindow__body">{w.content}</div>
              {w.footer && <div className="subwindow__footer">{w.footer}</div>}
            </div>
          </div>
        );
      })}
    </div>
  );
}
