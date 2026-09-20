import { useState } from "react";
import type { EditorEnv } from "./builderUi";
import { QueryEditor, type StatementExtras } from "./QueryEditor";
import {
  newField, newUnionMember, sourceRef,
  type EntryType, type Field, type Query, type Statement, type UnionMember,
} from "../querymodel/builderModel";

export interface PackageExtras {
  entryType: EntryType;
  setEntryType: (t: EntryType) => void;
  tempName?: string;
  setTempName: (s: string) => void;
  entryName: string;
  setEntryName: (s: string) => void;
}

const memberLabel = (i: number) => (i === 0 ? "Query 1" : `Union ${i}`);

/**
 * Редактор оператора пакета: цепочка ОБЪЕДИНИТЬ (UNION/UNION ALL) + правая
 * вертикальная панель «гребешков» с участниками объединения. Два режима:
 *  • «Конструктор» — мастер активного участника (QueryEditor);
 *  • «Объединение/Псевдонимы» — матрица соответствия полей между всеми
 *    участниками (строка = выходная колонка с псевдонимом; столбцы = поля из
 *    Запрос 1, Запрос 2, … ЗапросN), позволяющая сопоставлять поля с разными
 *    именами по позиции.
 * Используется и на верхнем уровне (пакет), и рекурсивно во вложенных запросах.
 */
export function StatementEditor({
  value, onChange, env, packageExtras,
}: {
  value: Statement;
  onChange: (st: Statement) => void;
  env: EditorEnv;
  packageExtras?: PackageExtras;
}) {
  const [active, setActive] = useState(0);
  const [view, setView] = useState<"build" | "union">("build");
  const idx = Math.min(active, value.unions.length - 1);
  const member = value.unions[idx];
  const multi = value.unions.length > 1;

  const setMembers = (unions: UnionMember[]) => onChange({ ...value, unions });
  const patchMember = (id: string, patch: Partial<UnionMember>) =>
    setMembers(value.unions.map((u) => (u.id === id ? { ...u, ...patch } : u)));
  const setMemberQuery = (id: string, q: Query) => patchMember(id, { query: q });
  const addMember = () => {
    const u = newUnionMember(false);
    setMembers([...value.unions, u]);
    setActive(value.unions.length);
    setView("build");
  };
  const removeMember = (id: string) => {
    if (value.unions.length <= 1) return;
    setMembers(value.unions.filter((u) => u.id !== id));
    setActive((a) => Math.max(0, Math.min(a, value.unions.length - 2)));
  };

  const extras: StatementExtras = {
    orderBy: value.orderBy,
    setOrderBy: (o) => onChange({ ...value, orderBy: o }),
    firstN: value.firstN,
    setFirstN: (n) => onChange({ ...value, firstN: n }),
    allowPackageType: !!packageExtras,
    entryType: packageExtras?.entryType,
    setEntryType: packageExtras?.setEntryType,
    tempName: packageExtras?.tempName,
    setTempName: packageExtras?.setTempName,
    entryName: packageExtras?.entryName,
    setEntryName: packageExtras?.setEntryName,
  };

  const renderStatement = (st: Statement, oc: (st: Statement) => void) => (
    <StatementEditor value={st} onChange={oc} env={env} />
  );

  const showUnion = multi && view === "union";

  return (
    <div className="rdr-stmt">
      <div className="rdr-stmt__editor">
        {}
        {multi && (
          <div className="rdr-stmt-tabs">
            <button className={`rdr-stmt-tab ${view === "build" ? "is-active" : ""}`} onClick={() => setView("build")}>Builder</button>
            <button className={`rdr-stmt-tab ${view === "union" ? "is-active" : ""}`} onClick={() => setView("union")}>Union / Aliases</button>
          </div>
        )}

        {showUnion ? (
          <UnionMatrix value={value} onChange={onChange} env={env} />
        ) : (
          <>
            {multi && (
              <div className="rdr-union-bar">
                <span className="op">{idx === 0 ? "QUERY 1 (base)" : `UNION ${idx}`}</span>
                {idx > 0 && (
                  <label className="rdr-check" title="UNION (no duplicates) ↔ UNION ALL">
                    <input type="checkbox" checked={!member.all} onChange={(e) => patchMember(member.id, { all: !e.target.checked })} />
                    No duplicates (UNION)
                  </label>
                )}
                <span className="rdr-spacer" />
                {idx > 0 && <button className="btn btn--small btn--danger" onClick={() => removeMember(member.id)}>✕ delete union</button>}
              </div>
            )}
            <QueryEditor
              value={member.query}
              onChange={(q) => setMemberQuery(member.id, q)}
              env={env}
              renderStatement={renderStatement}
              extras={extras}
            />
          </>
        )}
      </div>

      {}
      <div className="rdr-comb rdr-comb--union">
        <div className="rdr-comb__title">Unions</div>
        {value.unions.map((u, i) => (
          <button key={u.id} className={`rdr-comb__tab ${!showUnion && i === idx ? "is-active" : ""}`}
                  onClick={() => { setActive(i); setView("build"); }}>
            {memberLabel(i)}
          </button>
        ))}
        <button className="rdr-comb__add" title="add a union" onClick={addMember}>＋</button>
      </div>
    </div>
  );
}

function exprsOf(query: Query, env: EditorEnv): string[] {
  const out: string[] = [];
  for (const s of query.sources) {
    const ref = sourceRef(s);
    const cols = s.kind === "subquery"
      ? (s.statement?.unions[0]?.query.fields ?? []).map((f) => f.alias?.trim() || (f.expression ? "" : f.column)).filter((x) => x !== "")
      : env.columnsOf(s.name, s.kind);
    for (const c of cols) out.push(`${ref}.${c}`);
  }
  return out;
}

function withFieldAt(query: Query, i: number, mut: (f: Field) => Field): Query {
  const fields = query.fields.slice();
  while (fields.length <= i) fields.push(newField({ column: "", expression: true }));
  fields[i] = mut(fields[i]);
  return { ...query, fields };
}

function UnionMatrix({ value, onChange, env }: { value: Statement; onChange: (s: Statement) => void; env: EditorEnv }) {
  const members = value.unions;
  const maxLen = Math.max(1, ...members.map((m) => m.query.fields.length));
  const rows = Array.from({ length: maxLen }, (_, i) => i);

  const patchQuery = (mid: string, q: Query) =>
    onChange({ ...value, unions: members.map((m) => (m.id === mid ? { ...m, query: q } : m)) });
  const setCol = (mid: string, i: number, val: string) => {
    const m = members.find((x) => x.id === mid)!;
    if (val === "") { patchQuery(mid, withFieldAt(m.query, i, (f) => ({ ...f, expression: false, table: undefined, column: "" }))); return; }
    if (val === "__expr__") { patchQuery(mid, withFieldAt(m.query, i, (f) => ({ ...f, expression: true, table: undefined, column: f.expression ? f.column : "" }))); return; }
    const dot = val.lastIndexOf(".");
    patchQuery(mid, withFieldAt(m.query, i, (f) => ({ ...f, expression: false, table: val.slice(0, dot), column: val.slice(dot + 1) })));
  };
  const setExpr = (mid: string, i: number, text: string) => {
    const m = members.find((x) => x.id === mid)!;
    patchQuery(mid, withFieldAt(m.query, i, (f) => ({ ...f, expression: true, table: undefined, column: text })));
  };
  const setAlias = (i: number, alias: string) =>
    onChange({ ...value, unions: members.map((m) => ({ ...m, query: withFieldAt(m.query, i, (f) => ({ ...f, alias })) })) });
  const addRow = () =>
    onChange({ ...value, unions: members.map((m) => ({ ...m, query: { ...m.query, fields: [...m.query.fields, newField({ column: "", expression: true })] } })) });
  const removeRow = (i: number) =>
    onChange({ ...value, unions: members.map((m) => ({ ...m, query: { ...m.query, fields: m.query.fields.filter((_, k) => k !== i) } })) });

  const aliasOf = (i: number) => {
    for (const m of members) { const f = m.query.fields[i]; if (f?.alias?.trim()) return f.alias; }
    const f0 = members[0].query.fields[i];
    return f0 && !f0.expression ? f0.column : "";
  };
  const exprCache = members.map((m) => exprsOf(m.query, env));

  return (
    <div className="rdr-fieldset" style={{ minHeight: 0, flex: 1, display: "flex", flexDirection: "column" }}>
      <div className="rdr-fieldset__title">
        Union field mapping
        <button className="btn btn--small" onClick={addRow}>+ result column</button>
      </div>
      <div className="rdr-note">
        Row = output column (its alias). Columns = fields of each union query by position —
        fields with different names can be mapped. The resulting column names come from the first query.
      </div>
      <div className="rdr-grid-wrap" style={{ flex: 1, minHeight: 0 }}>
        <table className="rdr-grid">
          <thead>
            <tr>
              <th style={{ minWidth: 150 }}>Alias (result)</th>
              {members.map((m, j) => (
                <th key={m.id} style={{ minWidth: 200 }}>
                  {memberLabel(j)}{j > 0 && <span className="hint"> · {m.all ? "UNION ALL" : "UNION"}</span>}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((i) => (
              <tr key={i}>
                <td>
                  <input className="rdr-input" style={{ width: "100%" }} placeholder={`Field${i + 1}`} value={aliasOf(i)} onChange={(e) => setAlias(i, e.target.value)} />
                </td>
                {members.map((m, j) => {
                  const f = m.query.fields[i];
                  const isExpr = !!f?.expression;
                  const curVal = f && !f.expression && f.column ? `${f.table}.${f.column}` : "";
                  return (
                    <td key={m.id}>
                      {isExpr ? (
                        <div className="rdr-actions" style={{ flexWrap: "nowrap" }}>
                          <input className="cell mono" style={{ flex: 1 }} placeholder="expression SQL" value={f?.column ?? ""} onChange={(e) => setExpr(m.id, i, e.target.value)} />
                          <button className="btn btn--small" title="pick a field from the list" onClick={() => setCol(m.id, i, "")}>↩</button>
                        </div>
                      ) : (
                        <select className="cell" style={{ width: "100%" }} value={curVal} onChange={(e) => setCol(m.id, i, e.target.value)}>
                          <option value="">— field —</option>
                          {exprCache[j].map((x) => <option key={x} value={x}>{x}</option>)}
                          {curVal && !exprCache[j].includes(curVal) && <option value={curVal}>{curVal}</option>}
                          <option value="__expr__">— expression… —</option>
                        </select>
                      )}
                    </td>
                  );
                })}
                <td style={{ width: 1 }}>
                  <button className="btn btn--small btn--danger" onClick={() => removeRow(i)}>✕</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
