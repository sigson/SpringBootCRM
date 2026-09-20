import { useState } from "react";
import { useSpringBootCrm } from "../proxy/WorkbenchContext";
import { useWorkbench } from "./Workbench";
import type { ResultSetDto } from "../types";
import { ResultGrid } from "./ResultGrid";

/** SQL-редактор с выполнением и пагинацией. Заполняет всю площадь раздела. */
export function EditorView() {
  const { client } = useSpringBootCrm();
  const { activeDs } = useWorkbench();
  const [sql, setSql] = useState("SELECT * FROM equipment");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<ResultSetDto | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const run = async (p = 0) => {
    if (!activeDs) { setErr("No data source selected"); return; }
    setBusy(true); setErr(null);
    try {
      const r = await client.query(activeDs, { sql, page: p, pageSize: 100 });
      setData(r); setPage(p);
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  };

  return (
    <div className="rdr-panel">
      <div className="rdr-toolbar">
        <button className="btn btn--primary" disabled={busy} onClick={() => run(0)}>▶ Run (Ctrl+Enter)</button>
        <span className="hint">only SELECT</span>
        <span className="rdr-spacer" />
        <div className="rdr-pager">
          <button className="btn btn--small" disabled={page === 0 || busy} onClick={() => run(page - 1)}>◀</button>
          <span>p. {page + 1}</span>
          <button className="btn btn--small" disabled={!data?.hasMore || busy} onClick={() => run(page + 1)}>▶</button>
        </div>
      </div>

      <textarea
        className="rdr-sql-editor"
        value={sql} spellCheck={false}
        onChange={(e) => setSql(e.target.value)}
        onKeyDown={(e) => { if (e.ctrlKey && e.key === "Enter") run(0); }}
        style={{ flex: data ? "1 1 35%" : "1 1 auto" }}
      />

      {err && <div className="alert alert--error rdr-alert">{err}</div>}
      {data && <ResultGrid data={data} onClose={() => setData(null)} />}
    </div>
  );
}
