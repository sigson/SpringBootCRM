import { useEffect, useState, type CSSProperties } from "react";
import { Link, useLocation } from "react-router-dom";
import { useNav } from "./NavProvider";
import type { NavNode } from "../types/navigation";

const COLLAPSE_KEY = "springbootcrm_nav_collapsed";

function readCollapsed(): boolean {
  try { return localStorage.getItem(COLLAPSE_KEY) === "1"; } catch { return false; }
}
function writeCollapsed(v: boolean): void {
  try { localStorage.setItem(COLLAPSE_KEY, v ? "1" : "0"); } catch {  }
}

/**
 * Лівий рейл навігації з {@link useNav} (джерело — {@code /api/navigation}).
 * Inline-стилізований (без нових CSS-класів), щоб не зачіпати наявну вёрстку.
 *
 * <p><b>Згортання.</b> Кнопка-перемикач у шапці згортає рейл у вузьку смужку
 * (лишається тільки «»»-кнопка розгортання). Стан зберігається в localStorage,
 * тож вибір тримається між сесіями.
 *
 * <p>GROUP — згортувана секція; OBJECT/TOOL — посилання на маршрут
 * ({@code /o/{slug}} або {@code /tool/{key}}).
 */
export function SideNav() {
  const { tree, ready } = useNav();
  const [collapsed, setCollapsed] = useState<boolean>(readCollapsed);

  useEffect(() => { writeCollapsed(collapsed); }, [collapsed]);

  if (!ready || tree.length === 0) return null;

  if (collapsed) {
    return (
      <aside style={{ ...railBaseStyle, width: 40, flex: "0 0 40px", padding: "12px 4px" }}>
        <button
          type="button"
          title="Expand panel"
          onClick={() => setCollapsed(false)}
          style={toggleBtnStyle}
        >»</button>
      </aside>
    );
  }

  return (
    <aside style={railBaseStyle}>
      <div style={headerStyle}>
        <span style={{ fontSize: 11, color: "var(--muted, #5b6470)", letterSpacing: 0.3 }}>
          NAVIGATION
        </span>
        <button
          type="button"
          title="Collapse panel"
          onClick={() => setCollapsed(true)}
          style={toggleBtnStyle}
        >«</button>
      </div>
      <nav style={{ display: "flex", flexDirection: "column", gap: 2 }}>
        {tree.map(n => <NavTreeNode key={n.id} node={n} depth={0} />)}
      </nav>
    </aside>
  );
}

function NavTreeNode({ node, depth }: { node: NavNode; depth: number }) {
  const loc = useLocation();
  const [open, setOpen] = useState(true);

  if (node.kind === "GROUP") {
    return (
      <div>
        <button
          type="button"
          onClick={() => setOpen(o => !o)}
          style={{ ...groupHeaderStyle, paddingLeft: 8 + depth * 12 }}
        >
          <span style={{ width: 12, display: "inline-block" }}>{open ? "▾" : "▸"}</span>
          {node.icon && <span>{node.icon}</span>}
          <span style={{ fontWeight: 600 }}>{node.label}</span>
        </button>
        {open && node.children.map(c => (
          <NavTreeNode key={c.id} node={c} depth={depth + 1} />
        ))}
      </div>
    );
  }

  const route = node.route ?? "#";
  const active = loc.pathname === route;
  return (
    <Link
      to={route}
      style={{
        ...leafStyle,
        paddingLeft: 12 + depth * 12,
        background: active ? "var(--accent-soft, rgba(60,120,240,0.12))" : "transparent",
        fontWeight: active ? 600 : 400,
      }}
    >
      {node.icon && <span>{node.icon}</span>}
      <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
        {node.label}
      </span>
    </Link>
  );
}

const railBaseStyle: CSSProperties = {
  width: 248,
  flex: "0 0 248px",
  borderRight: "1px solid var(--border, #e3e6ea)",
  padding: "12px 6px",
  overflowY: "auto",
  position: "sticky",
  top: 0,
  alignSelf: "flex-start",
  maxHeight: "100vh",
  boxSizing: "border-box",
};

const headerStyle: CSSProperties = {
  display: "flex", alignItems: "center", justifyContent: "space-between",
  padding: "0 8px 8px",
};

const toggleBtnStyle: CSSProperties = {
  border: "1px solid var(--border, #e3e6ea)", background: "transparent",
  borderRadius: 6, cursor: "pointer", width: 24, height: 24, lineHeight: "20px",
  color: "var(--text, #1c2230)",
};

const groupHeaderStyle: CSSProperties = {
  display: "flex", alignItems: "center", gap: 6,
  width: "100%", border: "none", background: "transparent",
  cursor: "pointer", textAlign: "left",
  padding: "6px 8px", fontSize: 12, color: "var(--muted, #5b6470)",
  textTransform: "uppercase", letterSpacing: 0.3,
};

const leafStyle: CSSProperties = {
  display: "flex", alignItems: "center", gap: 8,
  padding: "7px 8px", borderRadius: 6,
  fontSize: 14, textDecoration: "none",
  color: "var(--text, #1c2230)",
};
