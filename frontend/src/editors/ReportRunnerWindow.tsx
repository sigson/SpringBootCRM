import { Component, lazy, Suspense, type ReactNode } from "react";
import { getStoredToken } from "../api/client";

/**
 * Вікно <b>формування</b> збереженого звіту — те, що відкриває звичайний користувач
 * (на відміну від конструктора, який відкриває автор звіту).
 *
 * <p>Рендерить форму звіту з модуля компоновки; без модуля — порожній стан замість
 * помилки, бо відсутній конструктор не є збоєм довідника.
 */
const ReportRunner = lazy(() =>
  import("../modules/dcs/index").then((m) => ({ default: m.ReportRunner })),
);

class Boundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch() { /* тихо */ }
  render() {
    if (this.state.failed) {
      return <div className="empty-state">The data composition module is not available.</div>;
    }
    return this.props.children;
  }
}

export function ReportRunnerWindow({
  reportId, title, onClose,
}: { reportId: string; title?: string; onClose: () => void }) {
  return (
    <Boundary>
      <Suspense fallback={<div className="empty-state">Loading…</div>}>
        <div style={{ height: "74vh", display: "flex", minHeight: 0 }}>
          <ReportRunner
            reportId={reportId}
            title={title}
            getToken={() => getStoredToken()}
            onClose={onClose}
          />
        </div>
      </Suspense>
    </Boundary>
  );
}
