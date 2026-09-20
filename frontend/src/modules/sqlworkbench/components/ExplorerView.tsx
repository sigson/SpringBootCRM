import { useEffect, useState } from "react";
import { useSpringBootCrm } from "../proxy/WorkbenchContext";
import { useWorkbench } from "./Workbench";
import type { TableInfo, TableMetadata } from "../types";

/** Обозреватель схемы: список таблиц (слева) + детали (справа). */
export function ExplorerView() {
  const { client } = useSpringBootCrm();
  const { activeDs, openInContent } = useWorkbench();
  const [tables, setTables] = useState<TableInfo[]>([]);
  const [meta, setMeta] = useState<TableMetadata | null>(null);
  const [filter, setFilter] = useState("");
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    setTables([]); setMeta(null); setErr(null);
    if (!activeDs) return;
    client.tables(activeDs).then(setTables).catch((e) => setErr(e.message));
  }, [activeDs, client]);

  const select = (t: TableInfo) => {
    if (!activeDs) return;
    client.table(activeDs, t.name, t.catalog ?? undefined, t.schema ?? undefined)
      .then(setMeta).catch((e) => setErr(e.message));
  };

  if (!activeDs) return <div className="rdr-empty">Select a data source above.</div>;
  const shown = tables.filter((t) => t.name.toLowerCase().includes(filter.toLowerCase()));

  return (
    <div className="rdr-panel">
      <div className="rdr-split">
        <aside className="rdr-tree">
          <div className="rdr-tree__head">
            <input className="rdr-input" placeholder="table filter…" value={filter} onChange={(e) => setFilter(e.target.value)} />
          </div>
          <ul className="rdr-tree__list">
            {shown.map((t) => (
              <li key={(t.schema ?? "") + t.name} onClick={() => select(t)}
                  className={`rdr-tree__item ${meta?.table.name === t.name ? "is-sel" : ""}`}>
                <span className="rdr-tree__ico">{t.type === "VIEW" ? "▤" : "▦"}</span>{t.name}
              </li>
            ))}
            {shown.length === 0 && <li className="rdr-tree__item off">no tables</li>}
          </ul>
          {err && <div className="alert alert--error rdr-alert" style={{ margin: 8 }}>{err}</div>}
        </aside>

        <section className="rdr-detail">
          {!meta && <div className="rdr-empty">Select a table on the left.</div>}
          {meta && (
            <>
              <div className="rdr-detail-head">
                <h3>{meta.table.name}</h3>
                <button className="btn btn--small btn--primary" onClick={() => openInContent(meta.table)}>Open data →</button>
              </div>

              <h4>Columns</h4>
              <div className="rdr-grid-wrap" style={{ flex: "none", maxHeight: "50%" }}>
                <table className="rdr-grid">
                  <thead><tr><th>PK</th><th>Name</th><th>Type</th><th>Size</th><th>NULL</th></tr></thead>
                  <tbody>
                    {meta.columns.map((c) => (
                      <tr key={c.name}>
                        <td>{c.primaryKey ? "🔑" : ""}</td><td>{c.name}</td>
                        <td className="mono">{c.typeName}</td><td>{c.size}</td><td>{c.nullable ? "✓" : ""}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {meta.foreignKeys.length > 0 && (
                <>
                  <h4>Foreign keys</h4>
                  <div className="rdr-grid-wrap" style={{ flex: "none" }}>
                    <table className="rdr-grid">
                      <thead><tr><th>Column</th><th>→ Table</th><th>→ Column</th></tr></thead>
                      <tbody>
                        {meta.foreignKeys.map((f, i) => (
                          <tr key={i}><td>{f.fkColumn}</td><td>{f.pkTable}</td><td>{f.pkColumn}</td></tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </>
              )}
            </>
          )}
        </section>
      </div>
    </div>
  );
}
