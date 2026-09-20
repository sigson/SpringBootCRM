import { useEffect, useMemo, useState } from "react";
import { useSpringBootCrm } from "../proxy/WorkbenchContext";
import { useWorkbench } from "./Workbench";
import type { ResultSetDto, TableInfo, TableMetadata } from "../types";
import { Modal, type EditorEnv } from "./builderUi";
import { StatementEditor } from "./StatementEditor";
import {
  buildExecutableSql, dataTypeFromSql, emptyPackage, extractPackageFromText, newEntry, newParam,
  packageToSql, paramLeftColumn, promoteReferenceParams, resolveFieldAliases, sqlWithAst, syncParams, tempColumns, tempTablesBefore,
  DATA_TYPES, type DataType, type EntryType, type Param, type ParamType, type QueryPackage,
} from "../querymodel/builderModel";
import { deriveSourceSchema } from "../querymodel/referenceModel";
import { useReferenceGraph } from "./ReferenceModeUi";
import { UnionRefField } from "../../../components/UnionRefField";
import { ResultGrid, type RefColumnSpec, type ResolvedRefLite } from "./ResultGrid";
// Мост к host'у (одностороннее направление app.modules → app): batch-резолв
// ссылок (typeId,id)→display и открытие объекта в стеке окон.
import { referencesApi } from "../../../api/endpoints";
import { useOpenTypeEditor } from "../../../editors/openTypeEditor";

const PARAM_TYPES: { value: ParamType; label: string }[] = [
  { value: "value", label: "Value" },
  { value: "list", label: "Value list" },
  { value: "table", label: "Value table" },
];

const TYPE_BADGE: Record<EntryType, string> = { select: "", createTemp: "TT↑", dropTemp: "TT✕" };

export function QueryBuilderView() {
  const { client, can } = useSpringBootCrm();
  const { activeDs } = useWorkbench();

  const [tables, setTables] = useState<TableInfo[]>([]);
  const [metaCache, setMetaCache] = useState<Record<string, TableMetadata>>({});
  const [pkg, setPkg] = useState<QueryPackage>(emptyPackage());
  const [activeId, setActiveId] = useState<string>(() => pkg.entries[0].id);

  const [data, setData] = useState<ResultSetDto | null>(null);
  const [page, setPage] = useState(0);
  // Максимум записей на страницу результата.
  const [pageSize, setPageSize] = useState(100);
  const [pageSizeText, setPageSizeText] = useState("100");   // буфер ручного ввода
  // Ссылочные колонки текущего результата (схлопываются в одну) + заресолвленные
  // значения (typeId:id → {display, accessible}). Снимаются на момент запуска,
  // чтобы соответствовать показанным данным, а не последующим правкам пакета.
  const [refColumns, setRefColumns] = useState<RefColumnSpec[]>([]);
  const [resolvedRefs, setResolvedRefs] = useState<Map<string, ResolvedRefLite>>(new Map());
  const openTypeEditor = useOpenTypeEditor();
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [modal, setModal] = useState<null | "sql" | "params" | "io">(null);
  const [ioText, setIoText] = useState("");
  const [ioMode, setIoMode] = useState<"export" | "import">("export");
  // Граф ссылочных типов — фоллбэк-список допустимых типов для пикера.
  const { graph: refGraph } = useReferenceGraph();
  const allRefTypeIds = useMemo(
    () => (refGraph?.types ?? []).filter((t) => t.isReference).map((t) => t.typeId),
    [refGraph]);
  // Параметр в ссылочном сравнении автоматически становится ссылочным (раздвоение
  // тип+id под капотом). Идемпотентно: promote не зацикливается.
  useEffect(() => {
    const promoted = promoteReferenceParams(pkg);
    if (promoted !== pkg) setPkg(promoted);
  }, [pkg]);

  // Режим окна параметров: ссылочный (тип+id одним полем) или raw (два под-параметра).
  const [paramView, setParamView] = useState<"reference" | "raw">("reference");

  useEffect(() => {
    setTables([]); setMetaCache({}); setData(null); setErr(null);
    const fresh = emptyPackage();
    setPkg(fresh); setActiveId(fresh.entries[0].id);
    if (!activeDs) return;
    client.tables(activeDs).then(setTables).catch((e) => setErr(e.message));
  }, [activeDs, client]);

  const activeEntry = pkg.entries.find((e) => e.id === activeId) ?? pkg.entries[0];

  const loadMeta = (name: string, schema?: string) => {
    if (!activeDs || metaCache[name]) return;
    client.table(activeDs, name, undefined, schema)
      .then((m) => setMetaCache((p) => ({ ...p, [name]: m })))
      .catch(() => {});
  };

  // окружение редактора: реальные таблицы + ВТ, доступные до текущего оператора
  const env: EditorEnv = useMemo(() => {
    const temps = tempTablesBefore(pkg, activeId);
    const candidates = [
      ...tables.map((t) => ({ schema: t.schema ?? undefined, name: t.name, kind: "table" as const })),
      ...temps.map((name) => ({ name, kind: "temp" as const })),
    ];
    return {
      candidates,
      columnsOf: (name, kind) =>
        kind === "temp"
          ? tempColumns(pkg, name)
          : (metaCache[name]?.columns ?? []).map((c) => c.name),
      ensureMeta: (name, schema) => loadMeta(name, schema),
      tempSchema: (name) => {
        const e = pkg.entries.find((x) => x.type === "createTemp" && x.tempName === name);
        const fields = e?.statement.unions[0]?.query.fields;
        return fields && fields.length ? deriveSourceSchema(fields) : null;
      },
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tables, metaCache, pkg, activeId]);

  const updateEntry = (id: string, patch: Partial<typeof activeEntry>) =>
    setPkg((p) => ({ ...p, entries: p.entries.map((e) => (e.id === id ? { ...e, ...patch } : e)) }));
  const addEntry = (type: EntryType) => {
    const n = pkg.entries.length + 1;
    const e = newEntry(type === "dropTemp" ? `Drop TT ${n}` : `Query ${n}`, type);
    if (type === "dropTemp") e.tempName = tempTablesBefore({ ...pkg, entries: [...pkg.entries, e] }, e.id)[0] ?? "TempTable1";
    setPkg((p) => ({ ...p, entries: [...p.entries, e] }));
    setActiveId(e.id);
  };
  const removeEntry = (id: string) => {
    if (pkg.entries.length <= 1) return;
    const remaining = pkg.entries.filter((e) => e.id !== id);
    setPkg((p) => ({ ...p, entries: remaining }));
    setActiveId((cur) => (cur === id ? remaining[0].id : cur));
  };

  const sqlText = useMemo(() => packageToSql(pkg), [pkg]);
  const setParams = (params: Param[]) => setPkg((p) => ({ ...p, params }));
  const inferDataType = (name: string): DataType | undefined => {
    const col = paramLeftColumn(sqlText, name);
    if (!col) return undefined;
    for (const meta of Object.values(metaCache)) {
      const c = meta.columns.find((cc) => cc.name.toLowerCase() === col.toLowerCase());
      if (c) return dataTypeFromSql(c.typeName);
    }
    return undefined;
  };
  const extractParams = () => setParams(syncParams(pkg.params, sqlText, inferDataType));
  const patchParam = (i: number, patch: Partial<Param>) =>
    setParams(pkg.params.map((p, k) => (k === i ? { ...p, ...patch } : p)));
  const addParam = () => setParams([...pkg.params, newParam(`Parameter${pkg.params.length + 1}`)]);
  const removeParam = (i: number) => setParams(pkg.params.filter((_, k) => k !== i));
  // Переключение типа данных параметра; для «reference» закрепляем постфикс
  // (для скрытых под-параметров) и кардинальность value.
  const randomSuffix = () => Math.random().toString(36).slice(2, 8);
  const setParamDataType = (i: number, dt: DataType) => {
    if (dt === "reference") {
      const p = pkg.params[i];
      patchParam(i, { dataType: "reference", type: "value", refSuffix: p.refSuffix || randomSuffix() });
    } else {
      patchParam(i, { dataType: dt });
    }
  };

  const run = async (p = 0, size = pageSize) => {
    if (!activeDs) { setErr("No data source selected"); return; }
    setBusy(true); setErr(null);
    try {
      const sql = buildExecutableSql(pkg, activeId);
      const r = await client.query(activeDs, { sql, page: p, pageSize: size });
      setData(r); setPage(p);

      // Неагрегированная ссылка выводится двумя колонками <alias>_type_id/<alias>_id —
      // схлопываем их в одну.
      const fields = activeEntry.type === "dropTemp"
        ? [] : (activeEntry.statement.unions[0]?.query.fields ?? []);
      const aliases = resolveFieldAliases(fields);
      const specs: RefColumnSpec[] = [];
      for (const f of fields) {
        if (!f.ref || f.agg !== "") continue;     // агрегированная ссылка — один скаляр, не пара
        const a = aliases.get(f.id) || "ref";
        specs.push({ idCol: `${a}_id`, typeCol: `${a}_type_id`, label: f.refPathLabel ?? a });
      }
      setRefColumns(specs);

      // Батч-резолв (typeId,id) → display через host-эндпоинт.
      const colIdx = new Map<string, number>();
      r.columns.forEach((c, i) => colIdx.set(c.toLowerCase(), i));
      const keys = new Map<string, { typeId: number; id: string }>();
      for (const spec of specs) {
        const ti = colIdx.get(spec.typeCol.toLowerCase());
        const ii = colIdx.get(spec.idCol.toLowerCase());
        if (ti == null || ii == null) continue;
        for (const row of r.rows) {
          const tv = row[ti], iv = row[ii];
          if (tv == null || iv == null) continue;
          const typeId = Number(tv); const id = String(iv);
          if (!Number.isFinite(typeId)) continue;
          keys.set(`${typeId}:${id}`, { typeId, id });
        }
      }
      if (keys.size) {
        try {
          const resolved = await referencesApi.resolve([...keys.values()]);
          const m = new Map<string, ResolvedRefLite>();
          for (const rr of resolved) m.set(`${rr.typeId}:${rr.id}`, { display: rr.display, accessible: rr.accessible });
          setResolvedRefs(m);
        } catch { setResolvedRefs(new Map()); }   // резолв не критичен — покажем typeId:id
      } else {
        setResolvedRefs(new Map());
      }
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  };

  // Применить введённый вручную размер страницы: парсим, ограничиваем снизу 1
  // (целое), нормализуем буфер и перечитываем с первой страницы.
  const applyPageSize = (raw: string) => {
    const n = Math.floor(Number(raw));
    const size = Number.isFinite(n) && n >= 1 ? n : pageSize;
    setPageSize(size);
    setPageSizeText(String(size));
    if (data) void run(0, size);
  };

  // Экспорт = SQL пакета + встроенный AST-комментарий. Импорт принимает такой SQL
  // (или «голый» JSON AST) и восстанавливает пакет.
  const openExport = () => { setIoMode("export"); setIoText(sqlWithAst(sqlText, pkg)); setModal("io"); };
  const openImport = () => { setIoMode("import"); setIoText(""); setModal("io"); };
  const doImport = () => {
    const next = extractPackageFromText(ioText);
    if (!next) {
      setErr("Import failed: no embedded AST-block (-- BEGIN SpringBootCRM QUERY-BUILDER AST) and the text is not JSON-as a tree");
      return;
    }
    setPkg(next); setActiveId(next.entries[0].id); setModal(null); setData(null); setErr(null);
  };
  const downloadExport = () => {
    const blob = new Blob([ioText], { type: "text/plain;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob); a.download = "query.sql"; a.click();
    URL.revokeObjectURL(a.href);
  };
  const importFromFile = (file?: File) => {
    if (!file) return;
    file.text().then((t) => setIoText(t)).catch(() => {});
  };

  if (!activeDs) return <div className="rdr-empty">Select a data source above.</div>;

  return (
    <div className="rdr-builder">
      {}
      <div className="rdr-toolbar">
        <button className="btn" onClick={() => setModal("sql")}>📄 Query</button>
        {can("RUN_QUERY") && (
          <button className="btn btn--primary" disabled={busy} onClick={() => run(0)}>▶ Run</button>
        )}
        <button className="btn" onClick={() => setModal("params")}>⚙ Parameters{pkg.params.length ? ` (${pkg.params.length})` : ""}</button>
        <button className="btn" onClick={openExport}>⭱ Export</button>
        <button className="btn" onClick={openImport}>⭳ Import</button>
        <span className="hint">only SELECT</span>
        <span className="rdr-spacer" />
        <div className="rdr-pager">
          <label className="hint" style={{ display: "flex", alignItems: "center", gap: 4 }} title="Maximum records on a single result page (Enter — apply)">
            rows/p.
            <input className="rdr-input mono" type="number" min={1} step={1} style={{ width: 76 }}
                   value={pageSizeText}
                   disabled={busy}
                   onChange={(e) => setPageSizeText(e.target.value)}
                   onBlur={(e) => applyPageSize(e.target.value)}
                   onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); applyPageSize((e.target as HTMLInputElement).value); } }} />
          </label>
          <button className="btn btn--small" disabled={page === 0 || busy} onClick={() => run(page - 1)}>◀</button>
          <span>p. {page + 1}</span>
          <button className="btn btn--small" disabled={!data?.hasMore || busy} onClick={() => run(page + 1)}>▶</button>
        </div>
      </div>

      {}
      <div className="rdr-stmt-area">
        <div className="rdr-stmt-main">
          {activeEntry.type === "dropTemp" ? (
            <div className="rdr-fieldset" style={{ padding: 12 }}>
              <div className="rdr-fieldset__title">Drop the temp table (DROP)</div>
              <div className="rdr-row" style={{ background: "transparent", border: "none", padding: 0 }}>
                <span className="hint" style={{ minWidth: 96 }}>TT name</span>
                <select className="rdr-select grow" value={activeEntry.tempName ?? ""} onChange={(e) => updateEntry(activeEntry.id, { tempName: e.target.value })}>
                  <option value="">— choose a TT —</option>
                  {tempTablesBefore(pkg, activeEntry.id).map((n) => <option key={n} value={n}>{n}</option>)}
                </select>
              </div>
              <div className="rdr-note">The statement returns no data. The TT is dropped (in the executed query temp tables are CTE, so the drop is ignored on execution).</div>
            </div>
          ) : (
            <StatementEditor
              value={activeEntry.statement}
              onChange={(st) => updateEntry(activeEntry.id, { statement: st })}
              env={env}
              packageExtras={{
                entryType: activeEntry.type,
                setEntryType: (t) => updateEntry(activeEntry.id, { type: t, tempName: t === "createTemp" ? (activeEntry.tempName ?? "TempTable1") : activeEntry.tempName }),
                tempName: activeEntry.tempName,
                setTempName: (s) => updateEntry(activeEntry.id, { tempName: s }),
                entryName: activeEntry.name,
                setEntryName: (s) => updateEntry(activeEntry.id, { name: s }),
              }}
            />
          )}
        </div>

        {}
        <div className="rdr-comb rdr-comb--package">
          <div className="rdr-comb__title">Query packages</div>
          {pkg.entries.map((e) => (
            <div key={e.id} className={`rdr-comb__tabwrap ${e.id === activeId ? "is-active" : ""}`}>
              <button className={`rdr-comb__tab ${e.id === activeId ? "is-active" : ""}`} onClick={() => setActiveId(e.id)}>
                {e.name}{TYPE_BADGE[e.type] ? ` · ${TYPE_BADGE[e.type]}` : ""}
              </button>
              {e.id === activeId && pkg.entries.length > 1 && (
                <button className="rdr-comb__del" title="delete statement" onClick={() => removeEntry(e.id)}>✕</button>
              )}
            </div>
          ))}
          <button className="rdr-comb__add" title="add a query (Select)" onClick={() => addEntry("select")}>＋</button>
          <button className="rdr-comb__add" title="add a «Drop TT» statement»" onClick={() => addEntry("dropTemp")}>⊘</button>
        </div>
      </div>

      {err && <div className="alert alert--error rdr-alert">{err}</div>}
      {data && (
        <ResultGrid
          data={data}
          fill={false}
          onClose={() => setData(null)}
          refColumns={refColumns}
          resolveRef={(typeId, id) => resolvedRefs.get(`${typeId}:${id}`)}
          onOpenRef={(typeId, id) => openTypeEditor({ typeId, id })}
        />
      )}

      {}
      {modal === "sql" && (
        <Modal title="Query · SQL with embedded AST" width="wide" onClose={() => setModal(null)}
               footer={<><button className="btn" onClick={() => { void navigator.clipboard?.writeText(sqlWithAst(sqlText, pkg)); }}>Copy</button><button className="btn btn--primary" onClick={() => setModal(null)}>Close</button></>}>
          <pre className="rdr-sql-preview" style={{ maxHeight: "55vh" }}>{sqlWithAst(sqlText, pkg)}</pre>
          <p className="hint">Under the query — AST-block (<span className="mono">-- BEGIN SpringBootCRM QUERY-BUILDER AST</span>): the builder tree in base64. The query travels together with its «builder» representation - on import of such SQL the builder restores the package. On execution the block is dropped and the package is rewritten into one <span className="mono">WITH … SELECT …</span>, parameters are substituted inline.</p>
        </Modal>
      )}

      {}
      {modal === "params" && (
        <Modal title="Query parameters" width="wide" onClose={() => setModal(null)} zIndex={95}
               footer={<button className="btn btn--primary" onClick={() => setModal(null)}>Close</button>}>
          <div className="rdr-toolbar" style={{ marginBottom: 10 }}>
            <button className="btn" onClick={extractParams}>⟳ Extract parameters from the query</button>
            <button className="btn btn--small" onClick={addParam}>+ parameter</button>
            <span style={{ flex: 1 }} />
            <span className="hint" style={{ alignSelf: "center" }}>mode:</span>
            <button className={`btn btn--small ${paramView === "reference" ? "btn--primary" : ""}`}
                    onClick={() => setParamView("reference")} title="reference as a single field (type+id)">Reference</button>
            <button className={`btn btn--small ${paramView === "raw" ? "btn--primary" : ""}`}
                    onClick={() => setParamView("raw")} title="two sub-parameters with the suffixes">Raw</button>
          </div>
          <div className="hint" style={{ marginBottom: 8 }}>
            are recognized <span className="mono">&amp;Name</span> and <span className="mono">:name</span>; the data type sets the literal format.
            Type <b>«Reference»</b> — this is a pair (type+id): in the final query splits into hidden
            <span className="mono"> &amp;P__t…</span> and <span className="mono">&amp;P__i…</span>, and the comparison becomes double (type AND id).
          </div>
          {pkg.params.length === 0 && <div className="off">no parameters found - press «Extract parameters»</div>}
          <div className="rdr-rows">
            {pkg.params.map((p, i) => {
              const isRef = p.dataType === "reference";
              const sfx = p.refSuffix ?? "";
              return (
              <div key={p.name + i} className="rdr-row" style={{ alignItems: "flex-start", flexWrap: "wrap" }}>
                <span className="mono" style={{ minWidth: 130 }}>&amp;{p.name}</span>
                <select className="rdr-select" title="cardinality" value={p.type} onChange={(e) => patchParam(i, { type: e.target.value as ParamType })}>
                  {PARAM_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select>
                {p.type !== "table" && (
                  <select className="rdr-select" title="data type" value={p.dataType} onChange={(e) => setParamDataType(i, e.target.value as DataType)}>
                    {DATA_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                    {p.type === "value" && <option value="reference">Reference (type+id)</option>}
                  </select>
                )}
                {p.type === "value" && !isRef && (
                  <TypedInput dataType={p.dataType} value={p.value} onChange={(v) => patchParam(i, { value: v })} />
                )}
                {/* ссылочный параметр — СТАНДАРТНЫЙ union-пикер (UnionRefField),
                    как поле «Зв'язаний об'єкт» в карточке календаря: выбор типа →
                    автокомплит-пикер значения по репозиторию типа. */}
                {p.type === "value" && isRef && paramView === "reference" && (
                  <div className="grow" style={{ minWidth: 280, display: "flex", flexDirection: "column", gap: 4 }}>
                    <UnionRefField
                      label=""
                      value={{ typeId: p.refTypeId ? Number(p.refTypeId) : null, id: p.refId ?? null }}
                      refTypeIds={(p.refTypeIds && p.refTypeIds.length) ? p.refTypeIds : allRefTypeIds}
                      onChange={(v) => patchParam(i, {
                        refTypeId: v?.typeId != null ? String(v.typeId) : undefined,
                        refId: v?.id ?? undefined,
                      })}
                    />
                    <span className="hint mono" title="hidden sub-parameters">→ &amp;{p.name}__t{sfx}, &amp;{p.name}__i{sfx}</span>
                  </div>
                )}
                {/* тот же параметр в raw — два под-параметра с постфиксами */}
                {p.type === "value" && isRef && paramView === "raw" && (
                  <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
                    <div className="rdr-actions" style={{ gap: 6 }}>
                      <span className="mono" style={{ minWidth: 160 }}>&amp;{p.name}__t{sfx}</span>
                      <span className="hint">type (number)</span>
                      <input className="rdr-input mono" type="number" style={{ width: 120 }}
                             value={p.refTypeId ?? ""} onChange={(e) => patchParam(i, { refTypeId: e.target.value })} />
                    </div>
                    <div className="rdr-actions" style={{ gap: 6 }}>
                      <span className="mono" style={{ minWidth: 160 }}>&amp;{p.name}__i{sfx}</span>
                      <span className="hint">id (string)</span>
                      <input className="rdr-input mono" style={{ width: 240 }}
                             value={p.refId ?? ""} onChange={(e) => patchParam(i, { refId: e.target.value })} />
                    </div>
                  </div>
                )}
                {p.type === "list" && (
                  <input className="rdr-input mono grow" placeholder="comma-separated values: 1, 2, 3"
                         value={p.value} onChange={(e) => patchParam(i, { value: e.target.value })} />
                )}
                {p.type === "table" && (
                  <ParamTable param={p} onChange={(patch) => patchParam(i, patch)} />
                )}
                <button className="btn btn--small btn--danger" onClick={() => removeParam(i)}>✕</button>
              </div>
              );
            })}
          </div>
        </Modal>
      )}

      {}
      {modal === "io" && (
        <Modal title={ioMode === "export" ? "Export query (SQL + embedded AST)" : "Import query (SQL with embedded AST)"} width="wide" onClose={() => setModal(null)}
               footer={ioMode === "export"
                 ? <><button className="btn" onClick={() => { void navigator.clipboard?.writeText(ioText); }}>Copy</button><button className="btn" onClick={downloadExport}>Download .sql</button><button className="btn btn--primary" onClick={() => setModal(null)}>Close</button></>
                 : <><button className="btn btn--primary" onClick={doImport}>Import</button><button className="btn" onClick={() => setModal(null)}>Cancel</button></>}>
          {ioMode === "import" && (
            <div className="rdr-toolbar" style={{ marginBottom: 8 }}>
              <label className="btn btn--small">Load from file<input type="file" accept=".sql,.json,text/plain,application/json" style={{ display: "none" }} onChange={(e) => importFromFile((e.target as unknown as { files?: File[] }).files?.[0])} /></label>
              <span className="hint">paste SQL with embedded AST-as a block (or «bare» JSON-a tree in the old format)</span>
            </div>
          )}
          <textarea className="rdr-sql-editor" style={{ minHeight: "50vh", width: "100%" }} value={ioText} spellCheck={false}
                    readOnly={ioMode === "export"} onChange={(e) => setIoText(e.target.value)} />
        </Modal>
      )}
    </div>
  );
}

/** Типизированное поле ввода значения по типу данных. */
function TypedInput({ dataType, value, onChange, small }: { dataType: DataType; value: string; onChange: (v: string) => void; small?: boolean }) {
  const cls = small ? "cell" : "rdr-input mono grow";
  if (dataType === "number")
    return <input className={cls} type="number" step="any" placeholder="number" value={value} onChange={(e) => onChange(e.target.value)} />;
  if (dataType === "date")
    return <input className={cls} type="date" value={value} onChange={(e) => onChange(e.target.value)} />;
  if (dataType === "datetime")
    return <input className={cls} type="datetime-local" value={value} onChange={(e) => onChange(e.target.value)} />;
  if (dataType === "boolean")
    return (
      <select className={small ? "cell" : "rdr-select"} value={value || "true"} onChange={(e) => onChange(e.target.value)}>
        <option value="true">True</option><option value="false">False</option>
      </select>
    );
  return <input className={cls} type="text" placeholder={dataType === "raw" ? "SQL as is" : "string"} value={value} onChange={(e) => onChange(e.target.value)} />;
}

/** Мини-редактор таблицы значений для параметра типа «Таблица» (с типами колонок). */
function ParamTable({ param, onChange }: { param: Param; onChange: (patch: Partial<Param>) => void }) {
  const cols = param.columns;
  const types = param.columnTypes;
  const rows = param.rows;
  const colType = (ci: number): DataType => types[ci] ?? "text";
  const addColumn = () =>
    onChange({ columns: [...cols, `Column${cols.length + 1}`], columnTypes: [...types, "text"], rows: rows.map((r) => [...r, ""]) });
  const addRow = () => onChange({ rows: [...rows, cols.map(() => "")] });
  const setColName = (ci: number, v: string) => onChange({ columns: cols.map((x, k) => (k === ci ? v : x)) });
  const setColType = (ci: number, v: DataType) => onChange({ columnTypes: cols.map((_, k) => (k === ci ? v : colType(k))) });
  const setCell = (ri: number, ci: number, v: string) =>
    onChange({ rows: rows.map((rr, k) => (k === ri ? rr.map((vv, kk) => (kk === ci ? v : vv)) : rr)) });
  const removeRow = (ri: number) => onChange({ rows: rows.filter((_, k) => k !== ri) });
  return (
    <div className="grow" style={{ border: "1px solid var(--border)", borderRadius: "var(--radius-sm)", padding: 6 }}>
      <div className="rdr-actions" style={{ marginBottom: 4 }}>
        <button className="btn btn--small" onClick={addColumn}>+ column</button>
        <button className="btn btn--small" onClick={addRow}>+ string</button>
      </div>
      <table className="rdr-grid">
        <thead>
          <tr>
            {cols.map((c, ci) => (
              <th key={ci}>
                <input className="rdr-input" style={{ width: 100 }} value={c} onChange={(e) => setColName(ci, e.target.value)} />
                <select className="cell" value={colType(ci)} onChange={(e) => setColType(ci, e.target.value as DataType)}>
                  {DATA_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select>
              </th>
            ))}
            <th></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, ri) => (
            <tr key={ri}>
              {cols.map((_, ci) => (
                <td key={ci}><TypedInput small dataType={colType(ci)} value={r[ci] ?? ""} onChange={(v) => setCell(ri, ci, v)} /></td>
              ))}
              <td><button className="btn btn--small btn--danger" onClick={() => removeRow(ri)}>✕</button></td>
            </tr>
          ))}
          {rows.length === 0 && <tr><td className="empty" colSpan={cols.length + 1}>no rows</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
