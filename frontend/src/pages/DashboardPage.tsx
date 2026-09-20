import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useAuth, isAdmin } from "../auth/AuthProvider";
import { useNav } from "../navigation/NavProvider";
import type { NavNode } from "../types/navigation";
import { Alert, PageHead } from "../components/Common";

/**
 * <h2>Дашборд — точка входу, що «провалюється» вглиб.</h2>
 *
 * <p>Дашборд НЕ знає свого вмісту — так само як {@link SideNav}, він будується
 * виключно з дерева навігації {@code GET /api/navigation} ({@link useNav}). Бекенд
 * уже обрав потрібний layout (призначений користувачу інтерфейс або згенерований
 * дефолт) і відсік недоступні гілки за правами; фронт лише рендерить. Жодних
 * захардкоджених модулів, slug'ів чи описів — усе (label, icon, маршрут,
 * вкладеність) приходить з метаданих.
 *
 * <p>На відміну від {@link SideNav} (дерево), дашборд показує ту саму структуру
 * <b>плитками</b>:
 * <ul>
 *   <li><b>Групова плитка</b> ({@code kind==="GROUP"}) — «провалює» на рівень
 *       глибше (показує дочірні плитки цієї групи); URL не змінюється, навігація
 *       суто в межах дашборда (як 1С-підсистеми);</li>
 *   <li><b>Плитка об'єкта БД</b> ({@code kind==="OBJECT"}) — веде на список
 *       {@code /o/{slug}};</li>
 *   <li><b>Плитка інструмента</b> ({@code kind==="TOOL"}) — веде на
 *       {@code /tool/{key}}.</li>
 * </ul>
 *
 * <p>Поточне місце занурення відображається «хлібними крихтами» з клікабельними
 * сегментами для повернення на будь-який рівень вгору.
 */
export function DashboardPage() {
  const { user } = useAuth();
  const admin = isAdmin(user);
  const { tree, ready, error } = useNav();

  // Шлях занурення — масив id GROUP-вузлів від кореня до поточного рівня.
  const [path, setPath] = useState<string[]>([]);

  // Спускаємось по path, збираючи «хлібні крихти». Якщо шлях «протух» (інтерфейс
  // перезавантажився і групи зникли) — зупиняємось на валідному префіксі.
  const { nodes, trail } = useMemo(() => {
    const trailAcc: NavNode[] = [];
    let level: NavNode[] = tree;
    for (const id of path) {
      const grp = level.find(n => n.id === id && n.kind === "GROUP");
      if (!grp) break;
      trailAcc.push(grp);
      level = grp.children;
    }
    return { nodes: level, trail: trailAcc };
  }, [tree, path]);

  const enterGroup = (node: NavNode) => {
    // Будуємо шлях наново з валідного trail + нова група: це самовиправляє path,
    // якщо він був частково протухлий.
    setPath([...trail.map(t => t.id), node.id]);
  };

  /** Перехід «хлібними крихтами»: index=-1 → корінь; інакше — до trail[index]. */
  const goTo = (index: number) => {
    if (index < 0) { setPath([]); return; }
    setPath(trail.slice(0, index + 1).map(t => t.id));
  };

  const subtitle = admin
    ? "Administrative mode. All subsystems and objects are available."
    : "Choose a subsystem or object. The list follows the assigned interface and rights.";

  return (
    <main className="page">
      <PageHead
        title={`Welcome, ${user?.displayName || user?.username || ""}!`}
        subtitle={subtitle}
      />

      {error && <Alert kind="error">Could not load the navigation: {error}</Alert>}

      {}
      {trail.length > 0 && (
        <nav className="dash-crumbs" aria-label="Path">
          <button type="button" className="dash-crumb" onClick={() => goTo(-1)}>
            🏠 Home
          </button>
          {trail.map((t, i) => (
            <span key={t.id} className="dash-crumb__seg">
              <span className="dash-crumb__sep">›</span>
              {i === trail.length - 1 ? (
                <span className="dash-crumb dash-crumb--current">
                  {t.icon && <span>{t.icon}</span>} {t.label}
                </span>
              ) : (
                <button type="button" className="dash-crumb" onClick={() => goTo(i)}>
                  {t.icon && <span>{t.icon}</span>} {t.label}
                </button>
              )}
            </span>
          ))}
        </nav>
      )}

      {!ready ? (
        <div className="empty-state">Loading…</div>
      ) : nodes.length === 0 ? (
        <div className="empty-state">
          {trail.length > 0
            ? "This subsystem has no available items."
            : "No modules available. Ask an administrator to assign you a role."}
        </div>
      ) : (
        <div className="module-grid">
          {nodes.map(node =>
            node.kind === "GROUP"
              ? <GroupTile key={node.id} node={node} onEnter={() => enterGroup(node)} />
              : <LeafTile key={node.id} node={node} />
          )}
        </div>
      )}
    </main>
  );
}

/** Групова плитка — провалює на рівень глибше (кнопка, не посилання). */
function GroupTile({ node, onEnter }: { node: NavNode; onEnter: () => void }) {
  const groups = node.children.filter(c => c.kind === "GROUP").length;
  const leaves = node.children.length - groups;
  const parts: string[] = [];
  if (groups > 0) parts.push(`${groups} ${plural(groups, "subsystem", "subsystems", "subsystems")}`);
  if (leaves > 0) parts.push(`${leaves} ${plural(leaves, "item", "items", "items")}`);

  return (
    <button
      type="button"
      className="module-card module-card--group"
      onClick={onEnter}
      data-nav-id={node.id}
    >
      <div className="hflex">
        <span className="module-card__icon">{node.icon ?? "📁"}</span>
        <span className="module-card__title">{node.label}</span>
      </div>
      <span className="module-card__desc">
        {parts.length > 0 ? parts.join(" · ") : "Empty subsystem"}
      </span>
      <span className="module-card__cta">Open subsystem →</span>
    </button>
  );
}

/** Плитка об'єкта БД / інструмента — веде на маршрут вузла. */
function LeafTile({ node }: { node: NavNode }) {
  const route = node.route ?? "#";
  const isTool = node.kind === "TOOL";
  return (
    <Link to={route} className="module-card" data-nav-id={node.id} data-typeid={node.typeId ?? undefined}>
      <div className="hflex">
        <span className="module-card__icon">{node.icon ?? (isTool ? "🛠" : "📦")}</span>
        <span className="module-card__title">{node.label}</span>
      </div>
      <span className="module-card__desc">
        {isTool
          ? <span className="tag">tool</span>
          : <span className="tag tag--soft">database object</span>}
      </span>
      <span className="module-card__cta">{isTool ? "Open →" : "Go to the list →"}</span>
    </Link>
  );
}

/** Проста плюралізація для українських лічильників. */
function plural(n: number, one: string, few: string, many: string): string {
  const mod10 = n % 10, mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return one;
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return few;
  return many;
}
