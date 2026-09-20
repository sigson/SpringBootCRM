import { useState, type ReactNode } from "react";
import { Modal, type EditorEnv } from "./builderUi";
import {
  AGG_OPTIONS, COND_OPS, joinTypeLabel, newCond, newField, newJoin, newOrder,
  newSubquerySource, newTableSource, opNeedsTwoValues, opNeedsValue, sourceRef,
  fieldsOutputColumns,
  type AggFn, type Cond, type EntryType, type Field, type Join, type Order,
  type Query, type RefSide, type Source, type Statement,
} from "../querymodel/builderModel";
import { addRequisite, resolveExpression, deriveSourceSchema, type GraphType, type SourceSchema } from "../querymodel/referenceModel";
import {
  useReferenceGraph, RequisiteTree, DerivedRequisiteTree, RequisitePicker, type RequisitePick,
  type ForestSource, type SelectedFieldRef,
} from "./ReferenceModeUi";
import { FxEditor } from "./FxEditor";

type BuilderMode = "raw" | "reference";

const JOIN_OPERATORS = ["=", "<>", "<", "<=", ">", ">="];

/** Разбить «alias.column» на [alias, column]; без точки — ["", expr]. */
function splitExpr(expr: string): [string, string] {
  const i = expr.lastIndexOf(".");
  return i < 0 ? ["", expr] : [expr.slice(0, i), expr.slice(i + 1)];
}

/**
 * Поле выбора реквизита по ссылке (ссылочный режим): показывает путь реквизита
 * от источника («Користувачі.Роль.Ссылка») или плейсхолдер; по клику открывает
 * пикер (если задан target — развёрнутым на текущем реквизите). Заменяет
 * бесполезное «— поле —» в окнах связей/условий.
 */
function RefFieldButton({ label, placeholder, onClick }: {
  label?: string; placeholder?: string; onClick: () => void;
}) {
  const has = !!(label && label.trim());
  return (
    <button type="button" className={`rdr-reffield grow ${has ? "" : "is-empty"}`}
            onClick={onClick} title={has ? label : (placeholder ?? "pick an attribute through a reference")}>
      <span className="rdr-reffield__ico">🔗</span>
      <span className="rdr-reffield__text">{has ? label : (placeholder ?? "— attribute —")}</span>
      <span className="rdr-reffield__caret">▾</span>
    </button>
  );
}

/**
 * Кнопка вибору реального поля (raw) — аналог {@link RefFieldButton} без
 * ссилковості: показує SQL-імʼя {@code джерело.колонка} або плейсхолдер, по кліку
 * відкриває raw-пікер.
 */
function RawFieldButton({ label, placeholder, onClick }: {
  label?: string; placeholder?: string; onClick: () => void;
}) {
  const has = !!(label && label.trim());
  return (
    <button type="button" className={`rdr-reffield mono grow ${has ? "" : "is-empty"}`}
            onClick={onClick} title={has ? label : (placeholder ?? "pick a field")}>
      <span className="rdr-reffield__ico">▦</span>
      <span className="rdr-reffield__text">{has ? label : (placeholder ?? "— field —")}</span>
      <span className="rdr-reffield__caret">▾</span>
    </button>
  );
}

/**
 * Модальний пікер реальних полів (raw) — дерево «джерело → SQL-колонки». Аналог
 * пікера реквізиту без ссилковості: повертає пару (джерело, колонка).
 */
function RawColumnPicker({ sources, onPick, onClose }: {
  sources: { ref: string; label: string; columns: string[] }[];
  onPick: (ref: string, col: string) => void;
  onClose: () => void;
}) {
  const [open, setOpen] = useState<Record<string, boolean>>(
    () => Object.fromEntries(sources.map((s) => [s.ref, true])));
  const [filter, setFilter] = useState("");
  const lc = filter.toLowerCase();
  return (
    <Modal title="Pick a field (real columns)" width="wide" onClose={onClose}
           footer={<button className="btn" onClick={onClose}>Close</button>}>
      <input className="rdr-input" placeholder="field filter…" value={filter}
             onChange={(e) => setFilter(e.target.value)} style={{ marginBottom: 8, width: "100%" }} />
      <div style={{ maxHeight: "55vh", overflow: "auto", border: "1px solid var(--border,#ddd)", borderRadius: 6, padding: 4 }}>
        {sources.map((s) => {
          const cols = s.columns.filter((c) => !lc || c.toLowerCase().includes(lc) || `${s.ref}.${c}`.toLowerCase().includes(lc));
          return (
            <div key={s.ref}>
              <button type="button" className="rdr-pick" style={{ textAlign: "left", fontWeight: 600 }}
                      onClick={() => setOpen((o) => ({ ...o, [s.ref]: !o[s.ref] }))}>
                <span className="rdr-pick__ico">{open[s.ref] ? "▾" : "▸"}</span>📂 {s.label}
                <span className="hint">&nbsp;· {s.ref}</span>
              </button>
              {open[s.ref] && (
                <div style={{ marginLeft: 14, paddingLeft: 6, borderLeft: "1px solid var(--border,#d4d4d8)" }}>
                  {cols.map((c) => (
                    <button key={c} type="button" className="rdr-pick" style={{ textAlign: "left", display: "block", width: "100%" }}
                            onClick={() => { onPick(s.ref, c); onClose(); }} title={`${s.ref}.${c}`}>
                      <span className="rdr-pick__ico">＋</span><span className="mono">{c}</span>
                    </button>
                  ))}
                  {cols.length === 0 && <div className="off" style={{ paddingLeft: 8 }}>no known columns - use «fx»</div>}
                </div>
              )}
            </div>
          );
        })}
        {sources.length === 0 && <div className="off">no data sources</div>}
      </div>
    </Modal>
  );
}

export interface StatementExtras {
  orderBy: Order[];
  setOrderBy: (o: Order[]) => void;
  firstN?: number;
  setFirstN: (n?: number) => void;
  allowPackageType: boolean;
  entryType?: EntryType;
  setEntryType?: (t: EntryType) => void;
  tempName?: string;
  setTempName?: (s: string) => void;
  entryName?: string;
  setEntryName?: (s: string) => void;
}

type WizardTab = "fields" | "joins" | "grouping" | "conditions" | "order" | "extra";

export function QueryEditor({
  value, onChange, env, renderStatement, extras,
}: {
  value: Query;
  onChange: (q: Query) => void;
  env: EditorEnv;
  renderStatement: (st: Statement, onChange: (st: Statement) => void) => ReactNode;
  extras?: StatementExtras;
}) {
  const [tab, setTab] = useState<WizardTab>("fields");
  const [filter, setFilter] = useState("");
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [editingSub, setEditingSub] = useState<string | null>(null);
  const [fx, setFx] = useState<{ kind: "field" | "cond" | "join"; id: string } | null>(null);
  const [fxText, setFxText] = useState("");

  // Ссылочный режим: граф бизнес-объектов + переключатель.
  const [mode, setMode] = useState<BuilderMode>("raw");
  const { graph } = useReferenceGraph();
  // Куда применить выбор реквизита из модального picker'а (условия/связи).
  // target — уже выбранный реквизит стороны/поля: пикер откроется развёрнутым на нём.
  const [reqPicker, setReqPicker] = useState<
    | { kind: "cond"; condId: string; target?: RefSide }
    | { kind: "joinLeft"; joinId: string; target?: RefSide }
    | { kind: "joinRight"; joinId: string; target?: RefSide }
    | { kind: "order"; orderId: string; target?: RefSide }
    | { kind: "groupField"; fieldId: string; target?: RefSide }
    | null>(null);

  // Пікер реального поля (raw): справжні SQL-імена колонок джерел. Інтегрований
  // у «Звʼязки» та «Умови».
  const [rawPicker, setRawPicker] = useState<
    | { kind: "cond"; condId: string }
    | { kind: "joinLeft"; joinId: string }
    | { kind: "joinRight"; joinId: string }
    | { kind: "order"; orderId: string }
    | { kind: "groupField"; fieldId: string }
    | null>(null);

  const q = value;
  const set = (patch: Partial<Query>) => onChange({ ...q, ...patch });

  const refs = q.sources.map(sourceRef);

  // Уникальный sourceRef для дубля таблицы/объекта: базовое имя + порядковый номер
  // (users, users1, users2 … users10 … users20). Первый экземпляр — без суффикса.
  const uniqueRef = (base: string): string => {
    const existing = new Set(q.sources.map(sourceRef));
    if (!existing.has(base)) return base;
    let n = 1;
    while (existing.has(`${base}${n}`)) n++;
    return `${base}${n}`;
  };

  const addBusinessObject = (t: GraphType) => {
    const ref = uniqueRef(t.table);
    const src: Source = { id: `src_${Date.now().toString(36)}_${q.sources.length}`,
      kind: "table", name: t.table, typeId: t.typeId, alias: ref === t.table ? undefined : ref };
    // Бизнес-объект — обычная таблица; подгружаем колонки, чтобы они были известны в raw-режиме.
    env.ensureMeta(t.table);
    set({ sources: [...q.sources, src] });
  };

  /** Человекочитаемая метка источника по его ref (для пути реквизита). */
  const sourceLabelOfRef = (ref: string): string => {
    const s = q.sources.find((x) => sourceRef(x) === ref);
    if (s?.typeId != null) return graph?.type(s.typeId)?.singularLabel ?? s.name;
    return s ? sourceRef(s) : ref;
  };
  /** Дескриптор RefSide из выбора пикера: путь «Источник.Реквизит.…» + координаты. */
  const makeRefSide = (pick: RequisitePick): RefSide => ({
    label: `${sourceLabelOfRef(pick.path.rootRef)}.${pick.label}`,
    sourceRef: pick.path.rootRef,
    pathLabel: pick.label,
  });

  /** Применить выбор реквизита из дерева к текущей цели (поле/условие/связь). */
  const applyRequisitePick = (pick: RequisitePick, target: typeof reqPicker | { kind: "field" }) => {
    if (!target) return;
    if (target.kind === "field") {
      onChange(addRequisite(q, pick.path, pick.leaf, pick.label, graph ?? undefined));
      return;
    }
    // Группировка: перевыбор результирующего поля. addRequisite материализует
    // sources/joins и строит поле; переносим его на id целевого, сохраняя agg/alias.
    if (target.kind === "groupField") {
      const withAdded = addRequisite(q, pick.path, pick.leaf, pick.label, graph ?? undefined);
      const built = withAdded.fields[withAdded.fields.length - 1];
      const fields = withAdded.fields.slice(0, -1).map((f) =>
        f.id === target.fieldId ? { ...built, id: f.id, agg: f.agg, alias: f.alias } : f);
      onChange({ ...withAdded, fields });
      return;
    }
    // условие/связь/порядок — нужна материализация join'ов + выражение.
    const { query, expression, ref } = resolveExpression(q, pick.path, pick.leaf, graph ?? undefined);
    const refSide = makeRefSide(pick);
    if (target.kind === "order") {
      // Сортировка по реквизиту. Ссылка → пара (тип, id); скаляр → одно выражение.
      // Материализованные join'ы применяем к запросу.
      onChange(query);
      const orderExpr = ref ? ref.idExpr : expression;
      const refTypeExpr = ref ? (ref.constTypeId != null ? null : (ref.typeIdExpr ?? null)) : null;
      extras?.setOrderBy((extras?.orderBy ?? []).map((o) =>
        o.id === target.orderId ? { ...o, expression: orderExpr, refTypeExpr, label: refSide.label } : o));
      return;
    }
    if (target.kind === "cond") {
      // Реквизит как поле условия. Полиморфный скаляр — CASE-выражение, но это
      // по-прежнему значение (custom=false). Ссылочный реквизит сохраняет дескриптор
      // ref, чтобы сопоставление со ссылочным параметром стало двойным (тип+id).
      onChange({
        ...query,
        conditions: query.conditions.map((c) => c.id === target.condId
          ? { ...c, field: expression, custom: false, fieldRef: refSide, ref: ref ?? undefined } : c),
      });
    } else if (target.kind === "joinLeft" || target.kind === "joinRight") {
      if (pick.leaf.kind === "mergedScalar") {
        // Полиморфный union-скаляр (CASE) нельзя положить в структурную сторону —
        // переводим связь в режим произвольного ON.
        onChange({ ...query, joins: query.joins.map((j) => j.id === target.joinId
          ? { ...j, custom: true, extraOn: expression } : j) });
        return;
      }
      const [t, c] = splitExpr(expression);
      // Для ссылочной стороны храним обе координаты (тип+id) — если обе стороны
      // ссылочные, ON сравнит и тип, и id.
      const refCols = ref ? { typeIdExpr: ref.typeIdExpr, idExpr: ref.idExpr, constTypeId: ref.constTypeId } : undefined;
      const side = target.kind === "joinLeft"
        ? { leftTable: t, leftColumn: c, leftRef: refSide, leftRefCols: refCols }
        : { rightTable: t, rightColumn: c, rightRef: refSide, rightRefCols: refCols };
      onChange({ ...query, joins: query.joins.map((j) => j.id === target.joinId ? { ...j, ...side } : j) });
    }
  };
  /** Застосувати вибір уже відібраного результуючого поля (розділ «Поля запиту»
   * в пікері) до поточної цілі — умови або сторони звʼязку. */
  const applySelectedFieldPick = (f: SelectedFieldRef, target: typeof reqPicker) => {
    if (!target) return;
    const realF = q.fields.find((qf) => (qf.alias?.trim() || qf.column) === f.alias);
    if (target.kind === "order") {
      const rr = realF?.ref;
      const orderExpr = rr ? rr.idExpr : f.expression;
      const refTypeExpr = rr ? (rr.constTypeId != null ? null : (rr.typeIdExpr ?? null)) : null;
      extras?.setOrderBy((extras?.orderBy ?? []).map((o) =>
        o.id === target.orderId ? { ...o, expression: orderExpr, refTypeExpr, label: f.label } : o));
      return;
    }
    if (target.kind === "groupField") {
      if (realF) set({ fields: q.fields.map((fld) => fld.id === target.fieldId
        ? { ...fld, table: realF.table, column: realF.column, expression: realF.expression, ref: realF.ref, refPathLabel: realF.refPathLabel } : fld) });
      return;
    }
    const refSide: RefSide = { label: `Query field · ${f.label}`, sourceRef: "", pathLabel: "" };
    // Для ссылки берём реальные колонки (type/id), а не выходной alias —
    // иначе сравнение шло бы по alias'у, недоступному в WHERE/ON.
    const real = q.fields.find((qf) => (qf.alias?.trim() || qf.column) === f.alias);
    const realRef = real?.ref;
    const exprReal = realRef ? realRef.idExpr : f.expression;
    if (target.kind === "cond") {
      set({ conditions: q.conditions.map((c) => c.id === target.condId
        ? { ...c, field: exprReal, custom: false, fieldRef: refSide,
            ref: realRef ? { typeIdExpr: realRef.typeIdExpr, idExpr: realRef.idExpr, constTypeId: realRef.constTypeId, typeIds: realRef.typeIds } : undefined } : c) });
    } else {
      const [t, c] = splitExpr(exprReal);
      const refCols = realRef ? { typeIdExpr: realRef.typeIdExpr, idExpr: realRef.idExpr, constTypeId: realRef.constTypeId } : undefined;
      const side = target.kind === "joinLeft"
        ? { leftTable: t, leftColumn: c, leftRef: refSide, leftRefCols: refCols }
        : { rightTable: t, rightColumn: c, rightRef: refSide, rightRefCols: refCols };
      set({ joins: q.joins.map((j) => j.id === target.joinId ? { ...j, ...side } : j) });
    }
  };
  const columnsOfRef = (ref: string): string[] => {
    const s = q.sources.find((x) => sourceRef(x) === ref);
    if (!s) return [];
    if (s.kind === "subquery") {
      // Те же уникальные имена, что и в сгенерированном SQL подзапроса.
      return fieldsOutputColumns(s.statement?.unions[0]?.query.fields ?? []);
    }
    return env.columnsOf(s.name, s.kind);
  };
  /** Производная ссылочная схема источника-подзапроса/ВТ (для ссылочного режима). */
  const derivedSchemaOf = (s: Source): SourceSchema | null => {
    if (s.kind === "subquery") {
      const fields = s.statement?.unions[0]?.query.fields ?? [];
      return fields.length ? deriveSourceSchema(fields) : { requisites: [] };
    }
    if (s.kind === "temp") {
      return env.tempSchema?.(s.name) ?? null;
    }
    return null;
  };

  // Полный «лес источников» для пикера условий/связей и fx-панели: каждый
  // источник как дерево (object → граф, subquery/temp → производная схема).
  // Технологические (derived) сюда не попадают — скрыты в ссылочном режиме.
  const forestSources: ForestSource[] = q.sources.map((s) => {
    if (s.typeId != null && !s.derived) {
      return { ref: sourceRef(s), label: graph?.type(s.typeId)?.singularLabel ?? s.name,
               kind: "object" as const, typeId: s.typeId, derived: s.derived };
    }
    const schema = (s.kind === "subquery" || s.kind === "temp") ? derivedSchemaOf(s) ?? undefined : undefined;
    return { ref: sourceRef(s), label: s.name, kind: "derived" as const, schema, derived: s.derived };
  });

  // Уже выбранные результирующие поля — для раздела «Поля запиту» в пикере/fx.
  const selectedFieldRefs: SelectedFieldRef[] = q.fields
    .filter((f) => (f.alias && f.alias.trim()) || (!f.expression && f.column))
    .map((f) => {
      const alias = f.alias?.trim() || f.column;
      const expr = f.ref ? `${alias}_id` : (f.expression ? f.column : (f.table ? `${f.table}.${f.column}` : f.column));
      return { alias, label: f.refPathLabel ?? alias, expression: expr, isRef: !!f.ref };
    });

  // Джерела для raw-пікера полів (реальні SQL-колонки кожного джерела).
  const rawSources = q.sources.map((s) => ({
    ref: sourceRef(s),
    label: s.kind === "subquery" ? (s.name || "( subquery )") : s.name,
    columns: columnsOfRef(sourceRef(s)),
  }));
  const applyRawPick = (ref: string, col: string) => {
    const p = rawPicker;
    if (!p) return;
    if (p.kind === "cond") {
      set({ conditions: q.conditions.map((c) => c.id === p.condId
        ? { ...c, field: `${ref}.${col}`, custom: false, fieldRef: undefined, ref: undefined } : c) });
    } else if (p.kind === "order") {
      extras?.setOrderBy((extras?.orderBy ?? []).map((o) => o.id === p.orderId
        ? { ...o, expression: `${ref}.${col}`, refTypeExpr: null, label: `${ref}.${col}` } : o));
    } else if (p.kind === "groupField") {
      set({ fields: q.fields.map((f) => f.id === p.fieldId
        ? { ...f, table: ref, column: col, expression: false, ref: undefined, refPathLabel: undefined } : f) });
    } else if (p.kind === "joinLeft") {
      set({ joins: q.joins.map((j) => j.id === p.joinId
        ? { ...j, leftTable: ref, leftColumn: col, leftRef: undefined, leftRefCols: undefined } : j) });
    } else {
      set({ joins: q.joins.map((j) => j.id === p.joinId
        ? { ...j, rightTable: ref, rightColumn: col, rightRef: undefined, rightRefCols: undefined } : j) });
    }
    setRawPicker(null);
  };

  const addCandidate = (c: { schema?: string; name: string; kind: "table" | "temp" }) => {
    env.ensureMeta(c.name, c.schema);
    const baseRef = c.schema ? `${c.schema}.${c.name}` : c.name;
    // Дубликат той же таблицы → авто-псевдоним по схеме name1/name2/…
    const alias = q.sources.some((s) => sourceRef(s) === baseRef) ? uniqueRef(c.name) : undefined;
    const src = newTableSource({ schema: c.schema, name: c.name, kind: c.kind, alias });
    set({ sources: [...q.sources, src] });
    if (!alias) setExpanded((e) => ({ ...e, [sourceRef(src)]: true }));
  };
  const addSubquery = () => {
    const n = q.sources.filter((s) => s.kind === "subquery").length + 1;
    const src = newSubquerySource(`Subquery${n}`);
    set({ sources: [...q.sources, src] });
    setEditingSub(src.id);
  };
  const patchSource = (id: string, patch: Partial<Source>) =>
    set({ sources: q.sources.map((s) => (s.id === id ? { ...s, ...patch } : s)) });
  const removeSource = (s: Source) => {
    const ref = sourceRef(s);
    set({
      sources: q.sources.filter((x) => x.id !== s.id),
      fields: q.fields.filter((f) => f.expression || f.table !== ref),
      joins: q.joins.filter((j) => j.leftTable !== ref && j.rightTable !== ref),
    });
  };

  const isColSelected = (ref: string, col: string) =>
    q.fields.some((f) => !f.expression && f.table === ref && f.column === col);
  const colUseCount = (ref: string, col: string) =>
    q.fields.filter((f) => !f.expression && f.table === ref && f.column === col).length;
  const toggleColumn = (ref: string, col: string) => {
    const exists = q.fields.some((f) => !f.expression && f.table === ref && f.column === col);
    set({
      fields: exists
        ? q.fields.filter((f) => !(!f.expression && f.table === ref && f.column === col))
        : [...q.fields, newField({ table: ref, column: col })],
    });
  };
  // В raw-режиме поле добавляется по клику (можно добавить одну колонку несколько
  // раз); уникальные псевдонимы генерируются автоматически. Удаление — из панели «Поля».
  const addColumn = (ref: string, col: string) =>
    set({ fields: [...q.fields, newField({ table: ref, column: col })] });
  const rawColumnList = (ref: string, emptyMsg: string) => {
    const cols = columnsOfRef(ref);
    if (cols.length === 0) return <span className="off">{emptyMsg}</span>;
    return (
      <>
        {cols.map((c) => {
          const n = colUseCount(ref, c);
          return (
            <button key={c} type="button" className={`rdr-pick ${n > 0 ? "is-sel" : ""}`}
                    style={{ textAlign: "left", display: "block", width: "100%" }}
                    onClick={() => addColumn(ref, c)} title={`add ${ref}.${c}`}>
              <span className="rdr-pick__ico">＋</span><span className="mono">{c}</span>
              {n > 0 && <span className="hint">&nbsp;· {n}×</span>}
            </button>
          );
        })}
      </>
    );
  };
  const addExprField = () => set({ fields: [...q.fields, newField({ column: "", expression: true })] });
  const patchField = (id: string, patch: Partial<Field>) =>
    set({ fields: q.fields.map((f) => (f.id === id ? { ...f, ...patch } : f)) });
  const removeField = (id: string) => set({ fields: q.fields.filter((f) => f.id !== id) });
  const moveField = (id: string, dir: -1 | 1) => {
    const i = q.fields.findIndex((f) => f.id === id); const j = i + dir;
    if (i < 0 || j < 0 || j >= q.fields.length) return;
    const arr = q.fields.slice(); [arr[i], arr[j]] = [arr[j], arr[i]]; set({ fields: arr });
  };

  const addJoin = () => set({
    joins: [...q.joins, newJoin({
      leftTable: refs[0] ?? "", leftColumn: "", operator: "=",
      rightTable: refs[1] ?? "", rightColumn: "", leftAll: false, rightAll: false,
    })],
  });
  const patchJoin = (id: string, patch: Partial<Join>) =>
    set({ joins: q.joins.map((j) => (j.id === id ? { ...j, ...patch } : j)) });
  const removeJoin = (id: string) => set({ joins: q.joins.filter((j) => j.id !== id) });

  const addCond = (custom = false) => set({ conditions: [...q.conditions, newCond({ custom })] });
  const patchCond = (id: string, patch: Partial<Cond>) =>
    set({ conditions: q.conditions.map((c) => (c.id === id ? { ...c, ...patch } : c)) });
  const removeCond = (id: string) => set({ conditions: q.conditions.filter((c) => c.id !== id) });

  const orderBy = extras?.orderBy ?? [];
  const addOrder = () => extras?.setOrderBy([...orderBy, newOrder()]);
  const patchOrder = (id: string, patch: Partial<Order>) =>
    extras?.setOrderBy(orderBy.map((o) => (o.id === id ? { ...o, ...patch } : o)));
  const removeOrder = (id: string) => extras?.setOrderBy(orderBy.filter((o) => o.id !== id));
  const moveOrder = (id: string, dir: -1 | 1) => {
    const i = orderBy.findIndex((o) => o.id === id); const j = i + dir;
    if (i < 0 || j < 0 || j >= orderBy.length) return;
    const arr = orderBy.slice(); [arr[i], arr[j]] = [arr[j], arr[i]]; extras?.setOrderBy(arr);
  };

  const openFxField = (f: Field) => { setFx({ kind: "field", id: f.id }); setFxText(f.expression ? f.column : (f.table ? `${f.table}.${f.column}` : f.column)); };
  const openFxCond = (c: Cond) => { setFx({ kind: "cond", id: c.id }); setFxText(c.custom ? c.field : (c.field || "")); };
  // Стартовый текст ON для fx-связи — из её структурных частей (если заданы).
  const joinOnText = (j: Join): string => {
    if (j.custom) return j.extraOn ?? "";
    const l = j.leftTable && j.leftColumn ? `${j.leftTable}.${j.leftColumn}` : "";
    const r = j.rightTable && j.rightColumn ? `${j.rightTable}.${j.rightColumn}` : "";
    const base = l && r ? `${l} ${j.operator || "="} ${r}` : "";
    return [base, j.extraOn].filter((x) => x && x.trim()).join(" AND ");
  };
  const openFxJoin = (j: Join) => { setFx({ kind: "join", id: j.id }); setFxText(joinOnText(j)); };
  const applyFxWith = (txt: string) => {
    if (!fx) return;
    if (fx.kind === "field") patchField(fx.id, { expression: true, column: txt, table: undefined, ref: undefined });
    else if (fx.kind === "cond") patchCond(fx.id, { custom: true, field: txt, fieldRef: undefined, ref: undefined });
    else patchJoin(fx.id, { custom: true, extraOn: txt, leftColumn: "", rightColumn: "", leftRef: undefined, rightRef: undefined });
    setFx(null);
  };
  // Связь не завершена: в структурном режиме не задана колонка/extraOn; в fx — пустой ON.
  const joinIncomplete = (j: Join): boolean =>
    j.custom ? (!j.rightTable || !(j.extraOn && j.extraOn.trim()))
             : ((!j.leftColumn || !j.rightColumn) && !(j.extraOn && j.extraOn.trim()));

  const allTabs: { id: WizardTab; label: string; show: boolean }[] = [
    { id: "fields", label: "Tables and fields", show: true },
    { id: "joins", label: "Links", show: q.sources.length >= 2 },
    { id: "grouping", label: "Grouping", show: true },
    { id: "conditions", label: "Conditions", show: true },
    { id: "order", label: "Order", show: !!extras },
    { id: "extra", label: "Advanced", show: !!extras },
  ];
  const tabs = allTabs.filter((t) => t.show);
  const activeTab = tabs.some((t) => t.id === tab) ? tab : "fields";

  const candidates = env.candidates.filter((c) => c.name.toLowerCase().includes(filter.toLowerCase()));
  const subSource = editingSub ? q.sources.find((s) => s.id === editingSub) : null;

  return (
    <div className="rdr-builder" style={{ flex: 1, minHeight: 0 }}>
      <div className="rdr-wizard-tabs">
        {tabs.map((t) => (
          <button key={t.id} className={`rdr-wizard-tab ${activeTab === t.id ? "is-active" : ""}`} onClick={() => setTab(t.id)}>
            {t.label}
          </button>
        ))}
        {}
        <span style={{ flex: 1 }} />
        <span className="hint" style={{ alignSelf: "center", marginRight: 6 }}>mode:</span>
        <button className={`rdr-wizard-tab ${mode === "raw" ? "is-active" : ""}`}
                onClick={() => setMode("raw")} title="Raw database tables and columns">Raw</button>
        <button className={`rdr-wizard-tab ${mode === "reference" ? "is-active" : ""}`}
                disabled={!graph}
                onClick={() => setMode("reference")}
                title={graph ? "Business objects and attributes through references (1With-style)" : "the business object graph is unavailable"}>
          Reference
        </button>
      </div>

      <div className="rdr-wizard-body">
        {}
        {activeTab === "fields" && (
          <div className="rdr-tf">
            {}
            <div className="rdr-pane">
              <div className="rdr-pane__head">{mode === "reference" ? "Business objects" : "Database"}</div>
              <div className="rdr-tree__head">
                <input className="rdr-input" placeholder={mode === "reference" ? "object filter…" : "table filter…"}
                       value={filter} onChange={(e) => setFilter(e.target.value)} />
              </div>
              <div className="rdr-pane__body">
                {mode === "reference" && graph ? (
                  <>
                    {graph.types
                      .filter((t) => !filter || t.pluralLabel.toLowerCase().includes(filter.toLowerCase()))
                      .map((t) => (
                        <div key={t.typeId}
                             className={`rdr-pick ${q.sources.some((s) => s.typeId === t.typeId && !s.derived) ? "is-sel" : ""}`}
                             title={`${t.table} · typeId=${t.typeId}`}
                             onClick={() => addBusinessObject(t)}>
                          <span className="rdr-pick__ico">＋</span>{t.pluralLabel}
                          {t.isReference && <span className="hint">&nbsp;· catalog</span>}
                        </div>
                      ))}
                    {graph.types.length === 0 && <div className="off" style={{ padding: 8 }}>the graph is empty</div>}
                    {/* Временные таблицы (из предыдущих пакетов) и сырые таблицы — доступны и
                        в ссылочном режиме: их колонки выбираются как обычно. */}
                    {candidates.filter((c) => c.kind === "temp" && (!filter || c.name.toLowerCase().includes(filter.toLowerCase()))).length > 0 && (
                      <div className="hint" style={{ padding: "6px 8px 2px", fontStyle: "italic" }}>temp tables:</div>
                    )}
                    {candidates
                      .filter((c) => c.kind === "temp" && (!filter || c.name.toLowerCase().includes(filter.toLowerCase())))
                      .map((c) => (
                        <div key={"t-" + c.name}
                             className={`rdr-pick ${q.sources.some((s) => s.kind === c.kind && s.name === c.name && !s.alias) ? "is-sel" : ""}`}
                             onClick={() => addCandidate(c)}>
                          <span className="rdr-pick__ico">▤</span>{c.name}<span className="hint">&nbsp;· TT</span>
                        </div>
                      ))}
                    {candidates.filter((c) => c.kind === "table" && (!filter || c.name.toLowerCase().includes(filter.toLowerCase()))).length > 0 && (
                      <div className="hint" style={{ padding: "6px 8px 2px", fontStyle: "italic" }}>database tables (without attributes):</div>
                    )}
                    {candidates
                      .filter((c) => c.kind === "table" && (!filter || c.name.toLowerCase().includes(filter.toLowerCase())))
                      .map((c) => (
                        <div key={"r-" + (c.schema ?? "") + c.name}
                             className={`rdr-pick ${q.sources.some((s) => s.kind === c.kind && s.name === c.name && !s.alias) ? "is-sel" : ""}`}
                             onClick={() => addCandidate(c)}>
                          <span className="rdr-pick__ico">＋</span>{c.name}
                        </div>
                      ))}
                  </>
                ) : (
                  <>
                    {candidates.map((c) => (
                      <div key={c.kind + (c.schema ?? "") + c.name}
                           className={`rdr-pick ${q.sources.some((s) => s.kind === c.kind && s.name === c.name && !s.alias) ? "is-sel" : ""}`}
                           onClick={() => addCandidate(c)}>
                        <span className="rdr-pick__ico">{c.kind === "temp" ? "▤" : "＋"}</span>
                        {c.name}{c.kind === "temp" && <span className="hint">&nbsp;· TT</span>}
                      </div>
                    ))}
                    {candidates.length === 0 && <div className="off" style={{ padding: 8 }}>no tables</div>}
                  </>
                )}
              </div>
            </div>

            {}
            <div className="rdr-pane">
              <div className="rdr-pane__head">
                Tables
                <button className="btn btn--small" onClick={addSubquery}>+ subquery</button>
              </div>
              <div className="rdr-pane__body">
                {q.sources.length === 0 && <div className="off" style={{ padding: 8 }}>add tables on the left</div>}
                {q.sources
                  // В ссылочном режиме технологические (авто-связь) источники скрыты,
                  // чтобы не путать пользователя; в сыром режиме видно всё.
                  .filter((s) => !(mode === "reference" && s.derived))
                  .map((s) => {
                  const ref = sourceRef(s);
                  const open = expanded[ref] ?? true;
                  return (
                    <div key={s.id} className="rdr-item" style={{ flexDirection: "column", alignItems: "stretch", gap: 4 }}>
                      <div className="rdr-actions" style={{ width: "100%" }}>
                        <button className="btn btn--small" onClick={() => setExpanded((e) => ({ ...e, [ref]: !open }))}>{open ? "▾" : "▸"}</button>
                        <span className="rdr-item__main">
                          <b>{s.kind === "subquery" ? (s.name || "( subquery )") : (mode === "reference" && s.typeId != null ? (graph?.type(s.typeId)?.singularLabel ?? s.name) : s.name)}</b>
                          {s.kind === "subquery" && <span className="hint"> · subquery</span>}
                          {s.kind === "temp" && <span className="hint"> · TT</span>}
                          {s.derived && <span className="hint" title="the source was created by the attribute auto link"> · auto link</span>}
                          {mode === "reference" && s.typeId != null && !s.derived && <span className="hint"> · object</span>}
                        </span>
                        {s.kind === "subquery" && <button className="btn btn--small btn--primary" onClick={() => setEditingSub(s.id)}>✎ open</button>}
                        <button className="btn btn--small btn--danger" onClick={() => removeSource(s)}>✕</button>
                      </div>
                      <div className="rdr-actions" style={{ width: "100%" }}>
                        <span className="hint">alias</span>
                        <input className="rdr-input grow" placeholder="(as in the source)" value={s.alias ?? ""}
                               onChange={(e) => patchSource(s.id, { alias: e.target.value })} />
                      </div>
                      {open && (() => {
                        const derivedSchema = derivedSchemaOf(s);
                        const refDerived = mode === "reference" && graph && (s.kind === "subquery" || s.kind === "temp");
                        // Ссылочный режим: подзапрос/ВТ дают то же дерево реквизитов,
                        // что и таблицы — со ссылками и проваливанием вглубь.
                        if (refDerived && derivedSchema) {
                          return (
                            <div style={{ paddingLeft: 2 }}>
                              <div className="hint" style={{ padding: "2px 6px" }}>
                                attributes {s.kind === "subquery" ? "of the subquery" : "of the temp table"} (including references):
                              </div>
                              <DerivedRequisiteTree graph={graph!} rootRef={ref} schema={derivedSchema}
                                                    onPick={(pick) => applyRequisitePick(pick, { kind: "field" })} />
                            </div>
                          );
                        }
                        if (s.kind === "subquery") {
                          return (
                            <div style={{ paddingLeft: 6, display: "flex", flexDirection: "column", gap: 2 }}>
                              <div className="hint" style={{ padding: "2px 0" }}>attributes of the subquery:</div>
                              {rawColumnList(ref, "the subquery has no aliased fields - open it and set fields/aliases")}
                            </div>
                          );
                        }
                        if (mode === "reference" && s.typeId != null && graph && !s.derived) {
                          return (
                            <div style={{ paddingLeft: 2 }}>
                              <div className="hint" style={{ padding: "2px 6px" }}>attributes (expand references deeper):</div>
                              <RequisiteTree graph={graph} rootTypeId={s.typeId} rootRef={ref}
                                             onPick={(pick) => applyRequisitePick(pick, { kind: "field" })} />
                            </div>
                          );
                        }
                        if (s.derived) {
                          return <div className="off" style={{ paddingLeft: 8 }}>added automatically when picking an attribute through a reference</div>;
                        }
                        return (
                          <div style={{ paddingLeft: 6, display: "flex", flexDirection: "column", gap: 2 }}>
                            {rawColumnList(ref, "columns are unknown - use «fx»")}
                          </div>
                        );
                      })()}
                    </div>
                  );
                })}
              </div>
            </div>

            {}
            <div className="rdr-pane">
              <div className="rdr-pane__head">
                Fields
                <button className="btn btn--small" onClick={addExprField}>+ expression</button>
              </div>
              <div className="rdr-pane__body">
                {q.fields.length === 0 && <div className="rdr-note">No fields selected → <b>SELECT *</b>.</div>}
                {q.fields.map((f) => (
                  <div key={f.id} className="rdr-item" style={{ flexWrap: "wrap" }}>
                    <div className="rdr-actions">
                      <button className="btn btn--small" onClick={() => moveField(f.id, -1)}>↑</button>
                      <button className="btn btn--small" onClick={() => moveField(f.id, 1)}>↓</button>
                    </div>
                    {f.ref ? (
                      mode === "raw" ? (
                        // У сирому режимі ссилкове поле показано тим, чим воно є —
                        // дві реальні колонки (type_id + id), як при прямому виборі в raw.
                        <span className="rdr-item__main mono" style={{ display: "flex", flexDirection: "column", gap: 2 }}
                              title="reference field → two columns (type_id + id)">
                          <span>{f.ref.constTypeId != null ? String(f.ref.constTypeId) : (f.ref.typeIdExpr ?? "NULL")} AS {(f.alias?.trim() || "ref")}_type_id</span>
                          <span>{f.ref.idExpr} AS {(f.alias?.trim() || "ref")}_id</span>
                        </span>
                      ) : (
                        <span className="rdr-item__main" title={`${f.alias || "ref"}_type_id, ${f.alias || "ref"}_id`}>
                          🔗 {f.refPathLabel ?? "Reference"}
                          <span className="tag" style={{ marginLeft: 6 }}>
                            {f.ref.anyReference ? "any type" : f.ref.typeIds.length > 1 ? `union(${f.ref.typeIds.length})` : "reference"}
                          </span>
                          <span className="hint mono">&nbsp;· {f.alias || "ref"}_type_id + {f.alias || "ref"}_id</span>
                        </span>
                      )
                    ) : f.expression ? (
                      <input className="rdr-input mono grow" placeholder="expression" value={f.column} onChange={(e) => patchField(f.id, { column: e.target.value })} />
                    ) : f.refPathLabel ? (
                      <span className="rdr-item__main" title={`${f.table}.${f.column}`}>
                        🔗 {f.refPathLabel}<span className="hint mono">&nbsp;· {f.table}.{f.column}</span>
                      </span>
                    ) : (
                      <span className="rdr-item__main mono">{f.table}.{f.column}</span>
                    )}
                    {!f.ref && <button className="btn btn--small" title="custom SQL" onClick={() => openFxField(f)}>fx</button>}
                    {!f.ref && (
                      <select className="rdr-select" value={f.agg} onChange={(e) => patchField(f.id, { agg: e.target.value as AggFn })}>
                        {AGG_OPTIONS.map((a) => <option key={a.value} value={a.value}>{a.label}</option>)}
                      </select>
                    )}
                    <input className="rdr-input" style={{ minWidth: 80, maxWidth: 120 }}
                           placeholder={f.ref ? "column prefix" : "alias"}
                           value={f.alias ?? ""} onChange={(e) => patchField(f.id, { alias: e.target.value })} />
                    <button className="btn btn--small btn--danger" onClick={() => removeField(f.id)}>✕</button>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}

        {}
        {activeTab === "joins" && (
          <div className="rdr-fieldset">
            <div className="rdr-fieldset__title">Table links <button className="btn btn--small" onClick={addJoin}>+ link</button></div>
            <div className="rdr-note">«All» flags: no → INNER (INNER); on the left → LEFT (LEFT); on the right → RIGHT (RIGHT); both → FULL (FULL).</div>
            <div className="rdr-rows">
              {q.joins
                // У ссилковому режимі ховаємо лише авто-звʼязки (матеріалізовані
                // дереференсом). Ручний звʼязок видно завжди, навіть якщо його сторона
                // сіла на derived-alias — інакше його не можна виправити чи видалити.
                .filter((j) => mode !== "reference" || !j.auto)
                .map((j) => (
                <div key={j.id} className="rdr-row" style={{ flexWrap: "wrap" }}>
                  <label className="rdr-check"><input type="checkbox" checked={j.leftAll} onChange={(e) => patchJoin(j.id, { leftAll: e.target.checked })} /> All</label>

                  {j.custom ? (
                    // fx-режим: соединяемая таблица + всё ON одним выражением
                    <>
                      <span className="op">join</span>
                      <select className="rdr-select" value={j.rightTable} onChange={(e) => patchJoin(j.id, { rightTable: e.target.value })}>
                        <option value="">— table —</option>
                        {refs.map((r) => <option key={r} value={r}>{r}</option>)}
                      </select>
                      <span className="op">ON</span>
                      <input className="rdr-input mono grow" placeholder="custom join condition (fx)"
                             value={j.extraOn ?? ""} onChange={(e) => patchJoin(j.id, { extraOn: e.target.value })} />
                      <button className="btn btn--small" title="back to the link builder"
                              onClick={() => patchJoin(j.id, { custom: false })}>↩ builder</button>
                    </>
                  ) : mode === "reference" ? (
                    // ссылочный режим: стороны — поля выбора реквизита по ссылке
                    <>
                      <RefFieldButton
                        label={j.leftRef?.label ?? (j.leftColumn ? `${j.leftTable}.${j.leftColumn}` : "")}
                        placeholder="— left side —"
                        onClick={() => setReqPicker({ kind: "joinLeft", joinId: j.id, target: j.leftRef })} />
                      <select className="rdr-select" style={{ minWidth: 60 }} value={j.operator} onChange={(e) => patchJoin(j.id, { operator: e.target.value })}>
                        {JOIN_OPERATORS.map((o) => <option key={o} value={o}>{o}</option>)}
                      </select>
                      <RefFieldButton
                        label={j.rightRef?.label ?? (j.rightColumn ? `${j.rightTable}.${j.rightColumn}` : "")}
                        placeholder="— right side —"
                        onClick={() => setReqPicker({ kind: "joinRight", joinId: j.id, target: j.rightRef })} />
                    </>
                  ) : (
                    // сирий режим: пікер реальних полів (джерело.колонка) —
                    // клікабельний, аналог ссилкового, але без ссилковості.
                    <>
                      <RawFieldButton
                        label={j.leftColumn ? `${j.leftTable}.${j.leftColumn}` : ""}
                        placeholder="— left field —"
                        onClick={() => setRawPicker({ kind: "joinLeft", joinId: j.id })} />
                      <select className="rdr-select" style={{ minWidth: 60 }} value={j.operator} onChange={(e) => patchJoin(j.id, { operator: e.target.value })}>
                        {JOIN_OPERATORS.map((o) => <option key={o} value={o}>{o}</option>)}
                      </select>
                      <RawFieldButton
                        label={j.rightColumn ? `${j.rightTable}.${j.rightColumn}` : ""}
                        placeholder="— right field —"
                        onClick={() => setRawPicker({ kind: "joinRight", joinId: j.id })} />
                    </>
                  )}

                  <label className="rdr-check"><input type="checkbox" checked={j.rightAll} onChange={(e) => patchJoin(j.id, { rightAll: e.target.checked })} /> All</label>
                  <span className="tag">{joinTypeLabel(j.leftAll, j.rightAll)}</span>
                  {!j.custom && j.extraOn && <span className="tag" title={j.extraOn}>+ON</span>}
                  {joinIncomplete(j) && (
                    <span className="tag rdr-tag--warn" title="The link is incomplete: set both sides (or a condition ON)">⚠ not finished</span>
                  )}
                  <button className="btn btn--small" title="custom link condition (fx)" onClick={() => openFxJoin(j)}>fx</button>
                  <button className="btn btn--small btn--danger" onClick={() => removeJoin(j.id)}>✕</button>
                </div>
              ))}
              {q.joins.length === 0 && <div className="off">no links set - comma join</div>}
            </div>
            {mode === "reference" && (
              <div className="rdr-note">Link sides are set by attributes through references (click a field for the picker). Dereferencing materializes the needed JOIN'automatically. The button <b>fx</b> — custom condition ON.</div>
            )}
          </div>
        )}

        {}
        {activeTab === "grouping" && (
          <div className="rdr-fieldset">
            <div className="rdr-fieldset__title">Grouping and aggregate functions</div>
            <div className="rdr-note">When an aggregate function is present, all non-aggregated fields automatically go into GROUP BY.{mode === "reference" && " Click a field to re-pick the attribute (the reference groups by the pair type+id)."}</div>
            {q.fields.length === 0 && <div className="off">add fields first</div>}
            <div className="rdr-rows">
              {q.fields.map((f) => (
                <div key={f.id} className="rdr-row">
                  {mode === "reference" ? (
                    <RefFieldButton
                      label={f.ref
                        ? (f.refPathLabel ?? "Reference")
                        : (f.refPathLabel ?? (f.expression ? (f.column || "expression") : `${f.table}.${f.column}`))}
                      placeholder="— attribute —"
                      onClick={() => setReqPicker({ kind: "groupField", fieldId: f.id })} />
                  ) : (
                    <RawFieldButton
                      label={f.expression ? (f.column || "expression") : `${f.table}.${f.column}`}
                      placeholder="— field —"
                      onClick={() => setRawPicker({ kind: "groupField", fieldId: f.id })} />
                  )}
                  <span className="op">{f.agg === "" ? "GROUP BY" : "AGGREGATE"}</span>
                  <select className="rdr-select" value={f.agg} onChange={(e) => patchField(f.id, { agg: e.target.value as AggFn })}>
                    {AGG_OPTIONS.map((a) => <option key={a.value} value={a.value}>{a.label}</option>)}
                  </select>
                </div>
              ))}
            </div>
          </div>
        )}

        {}
        {activeTab === "conditions" && (
          <div className="rdr-fieldset">
            <div className="rdr-fieldset__title">
              Filter conditions
              <button className="btn btn--small" onClick={() => addCond(false)}>+ condition</button>
              <button className="btn btn--small" onClick={() => addCond(true)}>+ custom</button>
            </div>
            <div className="rdr-note">The checkbox enables/disables the condition. With NULL — only «IS NULL». Aggregate → HAVING (HAVING).{mode === "reference" && " Click a field to pick an attribute through a reference."}</div>
            <div className="rdr-rows">
              {q.conditions.map((c, i) => (
                <div key={c.id} className={`rdr-row ${c.enabled ? "" : "is-disabled"}`}>
                  <label className="rdr-check"><input type="checkbox" checked={c.enabled} onChange={(e) => patchCond(c.id, { enabled: e.target.checked })} /></label>
                  {i === 0 ? <span className="op">WHERE</span> : (
                    <select className="rdr-select" style={{ minWidth: 70 }} value={c.connector} onChange={(e) => patchCond(c.id, { connector: e.target.value as "AND" | "OR" })}>
                      <option value="AND">AND</option><option value="OR">OR</option>
                    </select>
                  )}
                  {c.custom ? (
                    <input className="rdr-input mono grow" placeholder="custom expression" value={c.field} onChange={(e) => patchCond(c.id, { field: e.target.value })} />
                  ) : (
                    <>
                      {mode === "reference" ? (
                        <RefFieldButton
                          label={c.fieldRef?.label ?? c.field}
                          placeholder="— attribute —"
                          onClick={() => setReqPicker({ kind: "cond", condId: c.id, target: c.fieldRef })} />
                      ) : (
                        <RawFieldButton
                          label={c.field}
                          placeholder="— field —"
                          onClick={() => setRawPicker({ kind: "cond", condId: c.id })} />
                      )}
                      <select className="rdr-select" value={c.agg} title="aggregate → HAVING" onChange={(e) => patchCond(c.id, { agg: e.target.value as AggFn })}>
                        {AGG_OPTIONS.map((a) => <option key={a.value} value={a.value}>{a.value === "" ? "without aggregate" : a.label}</option>)}
                      </select>
                      <select className="rdr-select" value={c.op} onChange={(e) => patchCond(c.id, { op: e.target.value as Cond["op"] })}>
                        {COND_OPS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                      </select>
                      {opNeedsValue(c.op) && (
                        <input className="rdr-input mono" placeholder={c.op === "IN" ? "1, 2, 3 / &param" : "value / &param"} value={c.value} onChange={(e) => patchCond(c.id, { value: e.target.value })} />
                      )}
                      {opNeedsTwoValues(c.op) && (<><span className="op">AND</span><input className="rdr-input mono" placeholder="value 2" value={c.value2} onChange={(e) => patchCond(c.id, { value2: e.target.value })} /></>)}
                    </>
                  )}
                  <button className="btn btn--small" title="custom SQL" onClick={() => openFxCond(c)}>fx</button>
                  <button className="btn btn--small btn--danger" onClick={() => removeCond(c.id)}>✕</button>
                </div>
              ))}
              {q.conditions.length === 0 && <div className="off">no conditions set</div>}
            </div>
          </div>
        )}

        {}
        {activeTab === "order" && extras && (
          <div className="rdr-fieldset">
            <div className="rdr-fieldset__title">Order (sorting) <button className="btn btn--small" onClick={addOrder}>+ sorting</button></div>
            <div className="rdr-note">Applies to the whole union result. Priority follows the row order.{mode === "reference" && " Click a field to pick an attribute (the reference sorts by the pair type+id)."}</div>
            <div className="rdr-rows">
              {orderBy.map((o) => (
                <div key={o.id} className="rdr-row">
                  <div className="rdr-actions">
                    <button className="btn btn--small" onClick={() => moveOrder(o.id, -1)}>↑</button>
                    <button className="btn btn--small" onClick={() => moveOrder(o.id, 1)}>↓</button>
                  </div>
                  {mode === "reference" ? (
                    <RefFieldButton
                      label={o.label ?? o.expression}
                      placeholder="— attribute —"
                      onClick={() => setReqPicker({ kind: "order", orderId: o.id })} />
                  ) : (
                    <RawFieldButton
                      label={o.label ?? o.expression}
                      placeholder="— field —"
                      onClick={() => setRawPicker({ kind: "order", orderId: o.id })} />
                  )}
                  <select className="rdr-select" value={o.ascending ? "ASC" : "DESC"} onChange={(e) => patchOrder(o.id, { ascending: e.target.value === "ASC" })}>
                    <option value="ASC">Asc. (ASC)</option><option value="DESC">Desc. (DESC)</option>
                  </select>
                  <button className="btn btn--small btn--danger" onClick={() => removeOrder(o.id)}>✕</button>
                </div>
              ))}
              {orderBy.length === 0 && <div className="off">no sorting set</div>}
            </div>
          </div>
        )}

        {}
        {activeTab === "extra" && extras && (
          <div className="rdr-fieldset">
            <div className="rdr-fieldset__title">Advanced</div>
            {extras.allowPackageType && (
              <>
                <div className="rdr-row" style={{ background: "transparent", border: "none", padding: 0 }}>
                  <span className="hint" style={{ minWidth: 96 }}>Statement name</span>
                  <input className="rdr-input grow" value={extras.entryName ?? ""} onChange={(e) => extras.setEntryName?.(e.target.value)} />
                </div>
                <div className="rdr-row" style={{ background: "transparent", border: "none", padding: 0 }}>
                  <span className="hint" style={{ minWidth: 96 }}>Query type</span>
                  <select className="rdr-select" value={extras.entryType ?? "select"} onChange={(e) => extras.setEntryType?.(e.target.value as EntryType)}>
                    <option value="select">Data selection</option>
                    <option value="createTemp">Create TT (INTO)</option>
                  </select>
                  {extras.entryType === "createTemp" && (
                    <input className="rdr-input mono" placeholder="TT name" value={extras.tempName ?? ""} onChange={(e) => extras.setTempName?.(e.target.value)} />
                  )}
                </div>
              </>
            )}
            <div className="rdr-row" style={{ background: "transparent", border: "none", padding: 0 }}>
              <label className="rdr-check"><input type="checkbox" checked={q.distinct} onChange={(e) => set({ distinct: e.target.checked })} /> Without duplicates (DISTINCT / DISTINCT)</label>
            </div>
            <div className="rdr-row" style={{ background: "transparent", border: "none", padding: 0 }}>
              <span className="op">TOP N</span>
              <input className="rdr-input mono" type="number" min={0} style={{ minWidth: 100 }} placeholder="— no —"
                     value={extras.firstN ?? ""} onChange={(e) => extras.setFirstN(e.target.value === "" ? undefined : Number(e.target.value))} />
              <span className="hint">→ FETCH FIRST n ROWS ONLY</span>
            </div>
          </div>
        )}
      </div>

      {}
      {reqPicker && graph && (
        <RequisitePicker
          graph={graph}
          sources={forestSources}
          selectedFields={selectedFieldRefs}
          target={reqPicker.target ? { sourceRef: reqPicker.target.sourceRef, pathLabel: reqPicker.target.pathLabel } : undefined}
          onPick={(pick) => applyRequisitePick(pick, reqPicker)}
          onPickSelected={(f) => applySelectedFieldPick(f, reqPicker)}
          onClose={() => setReqPicker(null)}
        />
      )}

      {}
      {rawPicker && (
        <RawColumnPicker
          sources={rawSources}
          onPick={applyRawPick}
          onClose={() => setRawPicker(null)}
        />
      )}

      {}
      {subSource && subSource.statement && (
        <Modal title={`Subquery · ${subSource.alias || subSource.name}`} width="full" onClose={() => setEditingSub(null)}
               footer={<button className="btn btn--primary" onClick={() => setEditingSub(null)}>Done</button>}>
          <div style={{ height: "70vh", display: "flex" }}>
            {renderStatement(subSource.statement, (st) => patchSource(subSource.id, { statement: st }))}
          </div>
        </Modal>
      )}

      {}
      {fx && (
        <FxEditor
          title={fx.kind === "join" ? "Custom condition withʼjoin (ON, fx)" : "Custom expression (SQL)"}
          initialText={fxText}
          graph={mode === "reference" ? graph : null}
          sources={forestSources}
          selectedFields={selectedFieldRefs}
          onApply={(t) => { setFxText(t); applyFxWith(t); }}
          onClose={() => setFx(null)}
        />
      )}
    </div>
  );
}
