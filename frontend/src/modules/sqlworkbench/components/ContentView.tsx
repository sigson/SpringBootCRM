import { useCallback, useEffect, useMemo, useState } from "react";
import { useSpringBootCrm } from "../proxy/WorkbenchContext";
import { useWorkbench } from "./Workbench";
import type { ResultSetDto, TableMetadata } from "../types";

/**
 * CRUD-грид содержимого таблицы.
 *
 * Постраничное чтение, редактирование ячеек, вставка и удаление строк.
 * Грид и его горизонтальный скролл живут ВНУТРИ .rdr-grid-wrap (flex:1), поэтому
 * очень широкая таблица скроллится внутри области, а не растягивает окно раздела.
 */
export function ContentView() {
  const { client, can } = useSpringBootCrm();
  const { activeDs, openTable, datasources } = useWorkbench();

  const [meta, setMeta] = useState<TableMetadata | null>(null);
  const [data, setData] = useState<ResultSetDto | null>(null);
  const [page, setPage] = useState(0);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const [edits, setEdits] = useState<Record<number, Record<string, string>>>({});
  const [draft, setDraft] = useState<Record<string, string> | null>(null);

  const table = openTable;
  const dsInfo = datasources.find((d) => d.id === activeDs);
  const readOnlyDs = dsInfo?.readOnly ?? false;

  const canInsert = can("INSERT_DATA") && !readOnlyDs;
  const canUpdate = can("UPDATE_DATA") && !readOnlyDs;
  const canDelete = can("DELETE_DATA") && !readOnlyDs;

  const pkCols = useMemo(
    () => (meta?.columns ?? []).filter((c) => c.primaryKey).map((c) => c.name),
    [meta],
  );

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2500); };

  const load = useCallback(async (p = 0) => {
    if (!activeDs || !table) return;
    setBusy(true); setErr(null);
    try {
      const m = meta && meta.table.name === table.name
        ? meta
        : await client.table(activeDs, table.name, table.catalog ?? undefined, table.schema ?? undefined);
      if (!meta || meta.table.name !== table.name) setMeta(m);
      const r = await client.readRows(activeDs, table.name, {
        catalog: table.catalog ?? undefined,
        schema: table.schema ?? undefined,
        page: p, pageSize: 100,
        orderBy: m.columns.find((c) => c.primaryKey)?.name,
      });
      setData(r); setPage(p); setEdits({});
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeDs, table, client]);

  useEffect(() => { setMeta(null); setData(null); setEdits({}); setDraft(null); load(0); }, [load]);

  if (!activeDs) return <div className="rdr-empty">Select a data source above.</div>;
  if (!table) return <div className="rdr-empty">Open a table from the «Explorer» with the «Open data» button».</div>;

  const cols = data?.columns ?? [];
  const colIndex = (name: string) => cols.indexOf(name);

  const original = (rowIdx: number, colName: string): unknown => {
    const ci = colIndex(colName);
    return ci < 0 ? null : data!.rows[rowIdx][ci];
  };

  const display = (rowIdx: number, colName: string): string => {
    const e = edits[rowIdx]?.[colName];
    if (e !== undefined) return e;
    const v = original(rowIdx, colName);
    return v == null ? "" : String(v);
  };

  const editCell = (rowIdx: number, colName: string, value: string) => {
    setEdits((prev) => ({ ...prev, [rowIdx]: { ...(prev[rowIdx] ?? {}), [colName]: value } }));
  };

  const coerce = (s: string): unknown => (s === "" ? null : s);

  const keyForRow = (rowIdx: number): Record<string, unknown> => {
    const key: Record<string, unknown> = {};
    for (const pk of pkCols) key[pk] = original(rowIdx, pk);
    return key;
  };

  const saveRow = async (rowIdx: number) => {
    if (!activeDs || !table) return;
    const changed = edits[rowIdx];
    if (!changed || Object.keys(changed).length === 0) return;
    if (pkCols.length === 0) { setErr("No primary key - update is impossible"); return; }
    const values: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(changed)) values[k] = coerce(v);
    setBusy(true); setErr(null);
    try {
      const r = await client.updateRow(activeDs, table.name,
        { values, key: keyForRow(rowIdx) },
        table.schema ?? undefined, table.catalog ?? undefined);
      flash(`Rows updated: ${r.affected}`);
      await load(page);
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  };

  const deleteRow = async (rowIdx: number) => {
    if (!activeDs || !table) return;
    if (pkCols.length === 0) { setErr("No primary key - delete is impossible"); return; }
    if (!confirm("Delete row?")) return;
    setBusy(true); setErr(null);
    try {
      const r = await client.deleteRow(activeDs, table.name,
        { key: keyForRow(rowIdx) },
        table.schema ?? undefined, table.catalog ?? undefined);
      flash(`Rows deleted: ${r.affected}`);
      await load(page);
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  };

  const insertDraft = async () => {
    if (!activeDs || !table || !draft) return;
    const values: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(draft)) if (v !== "") values[k] = coerce(v);
    if (Object.keys(values).length === 0) { setErr("Fill in at least one field"); return; }
    setBusy(true); setErr(null);
    try {
      const r = await client.insertRow(activeDs, table.name, { values },
        table.schema ?? undefined, table.catalog ?? undefined);
      const keys = r.generatedKeys && Object.keys(r.generatedKeys).length
        ? ` (key: ${Object.values(r.generatedKeys).join(", ")})` : "";
      flash(`Rows added: ${r.affected}${keys}`);
      setDraft(null);
      await load(page);
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  };

  const rowDirty = (rowIdx: number) => !!edits[rowIdx] && Object.keys(edits[rowIdx]).length > 0;
  const hasActions = canUpdate || canDelete;

  return (
    <div className="rdr-panel">
      <div className="rdr-detail-head">
        <h3>{table.name}{readOnlyDs && <span className="hint"> · read-only</span>}</h3>
        <div className="rdr-actions">
          {canInsert && !draft && (
            <button className="btn btn--small" onClick={() => setDraft(Object.fromEntries((meta?.columns ?? []).map((c) => [c.name, ""])))}>
              + New row
            </button>
          )}
          <button className="btn btn--small" disabled={busy} onClick={() => load(page)}>⟳ Refresh</button>
          <div className="rdr-pager">
            <button className="btn btn--small" disabled={page === 0 || busy} onClick={() => load(page - 1)}>◀</button>
            <span>p. {page + 1}</span>
            <button className="btn btn--small" disabled={!data?.hasMore || busy} onClick={() => load(page + 1)}>▶</button>
          </div>
        </div>
      </div>

      {err && <div className="alert alert--error rdr-alert">{err}</div>}

      <div className="rdr-grid-wrap" style={data ? { borderRadius: "var(--radius) var(--radius) 0 0" } : undefined}>
        <table className="rdr-grid">
          <thead>
            <tr>
              <th className="rownum">#</th>
              {cols.map((c, i) => {
                const isPk = pkCols.includes(c);
                return <th key={i}>{isPk ? "🔑 " : ""}{c}<span className="coltype">{data!.columnTypes[i]}</span></th>;
              })}
              {hasActions && <th>Actions</th>}
            </tr>
          </thead>
          <tbody>
            {draft && (
              <tr className="draft">
                <td className="rownum">＋</td>
                {cols.map((c, i) => (
                  <td key={i}>
                    <input className="cell" value={draft[c] ?? ""}
                           placeholder={pkCols.includes(c) ? "(auto/PK)" : ""}
                           onChange={(e) => setDraft({ ...draft, [c]: e.target.value })} />
                  </td>
                ))}
                {hasActions && (
                  <td>
                    <div className="rdr-actions">
                      <button className="btn btn--small btn--primary" disabled={busy} onClick={insertDraft}>Save</button>
                      <button className="btn btn--small" onClick={() => setDraft(null)}>Cancel</button>
                    </div>
                  </td>
                )}
              </tr>
            )}

            {data?.rows.map((_, ri) => (
              <tr key={ri} className={rowDirty(ri) ? "dirty" : ""}>
                <td className="rownum">{page * (data?.pageSize ?? 100) + ri + 1}</td>
                {cols.map((c, ci) => {
                  const isPk = pkCols.includes(c);
                  const editable = canUpdate && !isPk;
                  const val = display(ri, c);
                  return (
                    <td key={ci} className={val === "" ? "null" : ""}>
                      {editable ? (
                        <input className="cell" value={val}
                               onChange={(e) => editCell(ri, c, e.target.value)} />
                      ) : (
                        <span className="mono">{val === "" ? "NULL" : val}</span>
                      )}
                    </td>
                  );
                })}
                {hasActions && (
                  <td>
                    <div className="rdr-actions">
                      {canUpdate && <button className="btn btn--small btn--primary" disabled={busy || !rowDirty(ri)} onClick={() => saveRow(ri)}>💾</button>}
                      {canDelete && <button className="btn btn--small btn--danger" disabled={busy} onClick={() => deleteRow(ri)}>🗑</button>}
                    </div>
                  </td>
                )}
              </tr>
            ))}

            {data && data.rows.length === 0 && !draft && (
              <tr><td className="empty" colSpan={cols.length + 1 + (hasActions ? 1 : 0)}>No rows</td></tr>
            )}
          </tbody>
        </table>
      </div>
      {data && (
        <div className="rdr-grid-foot" style={{ borderRadius: "0 0 var(--radius) var(--radius)" }}>
          Rows: {data.rows.length} · page {page + 1}{data.hasMore ? " (more available)" : ""} · {data.elapsedMs} ms
          {pkCols.length === 0 && <span className="hint"> · no PK: edit/delete unavailable</span>}
        </div>
      )}

      {toast && <div className="rdr-toast">{toast}</div>}
    </div>
  );
}
