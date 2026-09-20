import { createContext, useContext, useEffect, useState } from "react";
import { useSpringBootCrm } from "../proxy/WorkbenchContext";
import type { DataSourceInfo, TableInfo } from "../types";
import { ConnectionsView } from "./ConnectionsView";
import { ExplorerView } from "./ExplorerView";
import { EditorView } from "./EditorView";
import { ContentView } from "./ContentView";
import { QueryBuilderView } from "./QueryBuilderView";

type TabId = "connections" | "explorer" | "editor" | "content" | "builder";

interface WorkbenchState {
  datasources: DataSourceInfo[];
  refreshDataSources: () => void;
  activeDs: string | null;
  setActiveDs: (id: string | null) => void;
  openTable: TableInfo | null;
  openInContent: (t: TableInfo) => void;
  goTab: (t: TabId) => void;
}
const WorkbenchCtx = createContext<WorkbenchState | null>(null);
export const useWorkbench = () => {
  const c = useContext(WorkbenchCtx);
  if (!c) throw new Error("WorkbenchCtx missing");
  return c;
};

const ALL_TABS: { id: TabId; label: string; action: Parameters<ReturnType<typeof useSpringBootCrm>["can"]>[0] }[] = [
  { id: "connections", label: "Connections", action: "VIEW_DATASOURCES" },
  { id: "explorer", label: "Explorer", action: "READ_METADATA" },
  { id: "editor", label: "SQL-editor", action: "RUN_QUERY" },
  { id: "content", label: "Data (CRUD)", action: "READ_DATA" },
  { id: "builder", label: "Query builder", action: "BUILD_QUERY" },
];

export function Workbench({ tabs }: { tabs?: TabId[] }) {
  const { can, loading, client } = useSpringBootCrm();
  const [datasources, setDatasources] = useState<DataSourceInfo[]>([]);
  const [activeDs, setActiveDs] = useState<string | null>(null);
  const [openTable, setOpenTable] = useState<TableInfo | null>(null);
  const [tab, setTab] = useState<TabId>("connections");

  const refreshDataSources = () => {
    client.listDataSources().then((ds) => {
      setDatasources(ds);
      setActiveDs((cur) => cur ?? (ds[0]?.id ?? null));
    }).catch(() => {});
  };
  useEffect(refreshDataSources, [client]);

  const visible = ALL_TABS
    .filter((t) => !tabs || tabs.includes(t.id))
    .filter((t) => can(t.action));

  const openInContent = (t: TableInfo) => { setOpenTable(t); setTab("content"); };

  const state: WorkbenchState = {
    datasources, refreshDataSources, activeDs, setActiveDs,
    openTable, openInContent, goTab: setTab,
  };

  return (
    <WorkbenchCtx.Provider value={state}>
      <div className="springbootcrm-root">
        <header className="rdr-header">
          <div className="rdr-brand">
            <span className="rdr-brand__logo" /> SQL&nbsp;Workbench
            <small>· visual database access</small>
          </div>
          <div className="rdr-ds-picker">
            <label htmlFor="rdr-ds">Data source</label>
            <select id="rdr-ds" className="rdr-select" style={{ minWidth: 220 }}
                    value={activeDs ?? ""} onChange={(e) => setActiveDs(e.target.value || null)}>
              <option value="">— not selected —</option>
              {datasources.map((d) => (
                <option key={d.id} value={d.id}>{d.name} {d.connected ? "●" : "○"}</option>
              ))}
            </select>
          </div>
        </header>

        <nav className="rdr-tabs">
          {visible.map((t) => (
            <button key={t.id} className={`rdr-tab ${tab === t.id ? "is-active" : ""}`} onClick={() => setTab(t.id)}>
              {t.label}
            </button>
          ))}
        </nav>

        <main className="rdr-main">
          {loading && <div className="rdr-loading">Loading capabilities…</div>}
          {!loading && visible.length === 0 && (
            <div className="rdr-empty">No sections available for the current user.</div>
          )}
          {!loading && tab === "connections" && can("VIEW_DATASOURCES") && <ConnectionsView />}
          {!loading && tab === "explorer" && can("READ_METADATA") && <ExplorerView />}
          {!loading && tab === "editor" && can("RUN_QUERY") && <EditorView />}
          {!loading && tab === "content" && can("READ_DATA") && <ContentView />}
          {!loading && tab === "builder" && can("BUILD_QUERY") && <QueryBuilderView />}
        </main>
      </div>
    </WorkbenchCtx.Provider>
  );
}
