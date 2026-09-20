import { Component, lazy, Suspense, type ReactNode } from "react";
import type { TypeEditorProps } from "./registry";
import { getStoredToken } from "../api/client";

/**
 * <h2>Обгортка редактора елемента довідника «Звіти» (typeId=9500).</h2>
 *
 * <p>Сам конструктор живе в незалежному модулі {@code src/modules/dcs} і
 * підвантажується <b>ліниво</b>. Якщо модуль неможливо завантажити (папку видалено,
 * чанк не зібрався) — спрацьовує error-boundary і форма чесно каже, що конструктор
 * недоступний, а сам довідник лишається робочим: елементи звітів нікуди не діваються,
 * їх просто нема чим редагувати.
 *
 * <p>Та сама схема, що й у «SQL Workbench»: хост знає про модуль рівно один рядок —
 * динамічний {@code import()} нижче.
 */
const ReportDesigner = lazy(() =>
  import("../modules/dcs/index")
    .then((m) => ({ default: m.ReportDesigner })),
);

class ModuleBoundary extends Component<{ children: ReactNode; fallback: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch() { /* свідомо тихо: модуля немає — конструктора немає */ }
  render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}

export function ReportEditor({ id, prefetchedCode, onClose, onSaved }: TypeEditorProps) {
  return (
    <ModuleBoundary
      fallback={
        <div className="empty-state">
          The data composition module is not available: the report designer cannot be opened.
        </div>
      }
    >
      <Suspense fallback={<div className="empty-state">Loading the designer…</div>}>
        <div style={{ height: "78vh", display: "flex", minHeight: 0 }}>
          <ReportDesigner
            id={id}
            prefetchedCode={prefetchedCode}
            getToken={() => getStoredToken()}
            onClose={onClose}
            onSaved={onSaved}
          />
        </div>
      </Suspense>
    </ModuleBoundary>
  );
}
