import { Component, lazy, Suspense, type ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuth, isAdmin } from "../auth/AuthProvider";
import { getStoredToken } from "../api/client";
import { PageHead } from "../components/Common";

/**
 * <h2>Сторінка-обгортка незалежного модуля «SQL Workbench».</h2>
 *
 * <p>Модуль ({@code src/modules/sqlworkbench}) підвантажується <b>лінива</b>
 * через {@link lazy}. Якщо його неможливо завантажити (папку видалено, чанк не
 * зібрався, runtime-помилка ініціалізації) — спрацьовує {@link ModuleBoundary},
 * і розділ <b>зникає</b> повністю: користувача повертає на дашборд, а плитка в
 * меню вже прихована (див. {@code useSqlWorkbenchAvailable}). Тобто: «не вдалося
 * підвантажити → модуля немає → і розділу немає».
 *
 * <p>Доступ — лише адміністратори (guard {@code isAdmin}); бекенд дублює
 * перевіркою admin-only-політики.
 */

// Лінивий імпорт модуля. Динамічний import дозволяє Vite винести модуль в
// окремий чанк; його відсутність/помилка не валить основний бандл.
const WorkbenchModule = lazy(() =>
  import("../modules/sqlworkbench/WorkbenchModule")
    .then((m) => ({ default: m.WorkbenchModule }))
);

/** Error boundary: будь-яка помилка завантаження/рендера модуля → розділ зникає. */
class ModuleBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch() { /* свідомо тихо: модуля немає — розділу немає */ }
  render() {
    if (this.state.failed) return <Navigate to="/" replace />;
    return this.props.children;
  }
}

export function SqlWorkbenchPage() {
  const { user } = useAuth();

  // Guard: тільки адміни (на випадок прямого переходу за URL).
  if (!isAdmin(user)) return <Navigate to="/" replace />;

  return (
    // Повноекранний макет: сторінка займає всю висоту в'юпорта під топ-навігацією,
    // без max-width і великих відступів — модуль заповнює весь доступний простір
    // (фікс: «порожні поля по боках і знизу»). Топ-навігація = 44px.
    <main
      className="page"
      style={{
        maxWidth: "none",
        margin: 0,
        padding: "12px 16px 12px",
        height: "calc(100vh - 44px)",
        display: "flex",
        flexDirection: "column",
        minHeight: 0,
      }}
    >
      <PageHead
        title="SQL Workbench"
        subtitle="Direct access to SQL and raw database tables - the query builder, SQL-editor, data view (admin-only)"
      />
      <ModuleBoundary>
        <Suspense fallback={<div className="empty-state">Loading module…</div>}>
          {/*
            baseUrl — шлях REST-API модуля під security-цепочкою хоста.
            getToken — Bearer хоста (той самий, що в api/client).
            Без accessPolicy: серверні capabilities (admin-only) — джерело істини.
          */}
          <div style={{ flex: 1, minHeight: 0, minWidth: 0, display: "flex" }}>
            <WorkbenchModule
              baseUrl="/api/sqlworkbench"
              getToken={() => getStoredToken()}
              onError={(e) => console.warn("[sqlworkbench]", e.status, e.message)}
            />
          </div>
        </Suspense>
      </ModuleBoundary>
    </main>
  );
}
