// Ссылочный режим конструктора запросов (1С-«Ссылка»).
// Бэкенд отдаёт граф бизнес-объектов (/api/metadata/graph); здесь выбор реквизита
// по цепочке ссылок материализует LEFT JOIN'ы, авто-алиасы и поле-выражение в
// модель Query — сырой генератор SQL при этом не меняется.
// Само-реквизит «Ссылка» — это идентичность, он НЕ порождает join (это просто
// колонки самой ссылки в текущей строке). JOIN материализуется только при
// дереференсе ЗА «Ссылка». Навигация сведена к двум примитивам:
//   • field — взять указатель ссылочного реквизита (без join);
//   • cast  — спустить указатель в конкретную таблицу (один join).

import type { Field, Join, Query, Source } from "./builderModel";
import { resolveFieldAliases } from "./builderModel";

export interface GraphField {
  name: string;
  label: string;
  kind: "SCALAR" | "REF";
  column: string | null;
  refTypeIds: number[];
  refTypeIdColumn: string | null;
  refIdColumn: string | null;
  unionViewName: string | null;
  /**
   * true — реквизит помечен маркером AnyReference: ссылка на любой тип. refTypeIds
   * раскрыт в полную тип-иерархию (совместимые по id-типу типы), unionViewName=null.
   * Конструктор разворачивает её только в выбор конкретного типа (без общих реквизитов).
   */
  anyReference?: boolean;
}

export interface GraphType {
    iconHint: any;
  typeId: number;
  slug: string;
  table: string;
  idColumn: string;
  singularLabel: string;
  pluralLabel: string;
  displayPattern: string;
  isReference: boolean;
  fields: GraphField[];
}

export interface GraphResponse { types: GraphType[]; }

/** Индекс типов по typeId для быстрых переходов по графу. */
export class ReferenceGraph {
  private byId = new Map<number, GraphType>();
  constructor(public readonly types: GraphType[]) {
    for (const t of types) this.byId.set(t.typeId, t);
  }
  type(typeId: number): GraphType | undefined { return this.byId.get(typeId); }
  field(typeId: number, name: string): GraphField | undefined {
    return this.byId.get(typeId)?.fields.find((f) => f.name === name);
  }

  /**
   * Объединённый набор реквизитов union-ссылки: берутся реквизиты всех участников
   * и сворачиваются по имени. Присутствующий во всех типах — универсален; в части —
   * частичный (при материализации недостающие типы дают NULL). Одноимённые ссылки
   * сливаются с объединением целевых typeId; конфликт вида разрешается в пользу REF.
   */
  mergedUnionRequisites(typeIds: number[]): MergedRequisite[] {
    const order: string[] = [];
    const acc = new Map<string, MergedRequisite>();
    const members = typeIds.map((t) => this.byId.get(t)).filter((t): t is GraphType => !!t);
    const total = members.length;

    for (const t of members) {
      for (const f of t.fields) {
        const key = f.name;
        let m = acc.get(key);
        if (!m) {
          m = {
            name: f.name, label: f.label,
            kind: f.kind, presentIn: [],
            scalarColumnByType: {},
            // для ссылки — объединение целевых typeId + физ.колонки по типам
            refTypeIds: [], refColsByType: {},
          };
          acc.set(key, m);
          order.push(key);
        }
        m.presentIn.push(t.typeId);
        if (f.kind === "SCALAR") {
          m.scalarColumnByType[t.typeId] = f.column ?? f.name;
        } else {
          for (const tid of f.refTypeIds) if (!m.refTypeIds.includes(tid)) m.refTypeIds.push(tid);
          m.refColsByType[t.typeId] = {
            typeIdColumn: f.refTypeIdColumn, idColumn: f.refIdColumn ?? "id",
            unionViewName: f.unionViewName,
          };
        }
        // конфликт вида: пометим (kind станет 'MIXED' — показываем как ссылку, если
        // хоть один тип ссылочный, иначе скаляр)
        if (m.kind !== f.kind) m.kind = m.kind === "REF" || f.kind === "REF" ? "REF" : "SCALAR";
      }
    }

    return order.map((k) => {
      const m = acc.get(k)!;
      m.universal = m.presentIn.length === total;
      return m;
    });
  }
}

/** Свёрнутый по имени реквизит union-набора. */
export interface MergedRequisite {
  name: string;
  label: string;
  kind: "SCALAR" | "REF";
  presentIn: number[];
  universal?: boolean;
  // SCALAR: колонка по каждому типу (для полиморфного выбора значения)
  scalarColumnByType: Record<number, string>;
  // REF: объединение целевых typeId + физ.колонки ссылки по каждому типу
  refTypeIds: number[];
  refColsByType: Record<number, { typeIdColumn: string | null; idColumn: string; unionViewName: string | null }>;
}

// «Стандартные» реквизиты, доступные для union-ссылки через reference_lookup.
export const STANDARD_LOOKUP_COLUMNS: Array<{ column: "code" | "name" | "display"; label: string }> = [
  { column: "code", label: "Code" },
  { column: "name", label: "Name" },
  { column: "display", label: "Display" },
];

// Спец-имя само-реквизита (1С-style).
export const SSYLKA_LABEL = "Reference";

/**
 * Физическое описание ссылочного реквизита для материализации (из поля графа или
 * из выхода подзапроса/ВТ): колонки дискриминатора/id, допустимые типы и (для
 * union) имя reference_lookup-view.
 */
export interface RefFieldRef {
  name: string;
  label: string;
  refTypeIds: number[];
  refTypeIdColumn: string | null;
  refIdColumn: string;
  unionViewName: string | null;
  /** true — ссылка на любой тип (маркер AnyReference): разворот только в тип-иерархию. */
  anyReference?: boolean;
}

/** Построить {@link RefFieldRef} из поля графа. */
export function refFromGraphField(f: GraphField): RefFieldRef {
  return {
    name: f.name, label: f.label, refTypeIds: f.refTypeIds,
    refTypeIdColumn: f.refTypeIdColumn, refIdColumn: f.refIdColumn ?? "id",
    unionViewName: f.unionViewName, anyReference: !!f.anyReference,
  };
}

export type RefStep =
  | { kind: "field"; field: RefFieldRef }
  | { kind: "cast"; typeId: number; table: string; idColumn: string };

export interface RefPath {
  rootRef: string;
  rootTypeId: number;
  rootIdColumn: string;
  steps: RefStep[];
}

/** Лист пути: что именно читаем/выводим на конце. */
export type RefLeaf =
  | { kind: "scalar"; column: string; label: string }
  | { kind: "lookupCol"; column: string; label: string; viewName: string }
  | { kind: "reference"; label: string }                                    // удержать ссылку (Ссылка) — пара колонок
  // Полиморфный скаляр union-ссылки: значение из того репозитория, куда ведёт
  // ссылка (CASE по дискриминатору + LEFT JOIN по каждому участвующему типу).
  | { kind: "mergedScalar"; merged: MergedRequisite; label: string };

export function extendPath(path: RefPath, step: RefStep): RefPath {
  return { ...path, steps: [...path.steps, step] };
}

// Подзапрос/ВТ описываются выходной схемой (часть реквизитов — ссылочные), чтобы
// над ними работало то же дерево реквизитов, что и над таблицами. Ссылочный
// реквизит физически — пара колонок <alias>_type_id + <alias>_id.

/** Один выходной реквизит производного источника. */
export type OutputRequisite =
  | { kind: "scalar"; name: string; label: string }
  | {
      kind: "ref"; name: string; label: string;
      refTypeIds: number[];
      typeIdColumn: string;
      idColumn: string;
      unionViewName: string | null;
      anyReference?: boolean;
    };

export interface SourceSchema {
  requisites: OutputRequisite[];
}

/**
 * Вывести схему по полям SELECT'а подзапроса/определения ВТ. Ссылочное поле
 * (Field.ref) → ссылочный реквизит с парой колонок <alias>_type_id/<alias>_id и
 * сохранением typeIds (union остаётся union'ом). Обычное поле → скаляр.
 */
export function deriveSourceSchema(fields: Field[]): SourceSchema {
  const aliases = resolveFieldAliases(fields);
  const reqs: OutputRequisite[] = [];
  for (const f of fields) {
    const a = aliases.get(f.id) ?? "";
    if (!a) continue;
    // Агрегат над ссылкой даёт ОДИН скалярный столбец (не ссылку) — см. fieldSql.
    if (f.ref && f.agg === "") {
      const anyRef = !!f.ref.anyReference;
      // Any-reference: общего union-view нет — разворачивается только в тип-иерархию.
      const unionView = !anyRef && f.ref.typeIds.length >= 2 ? unionViewNameFor(f.ref.typeIds) : null;
      reqs.push({
        kind: "ref", name: a, label: f.refPathLabel ?? a,
        refTypeIds: f.ref.typeIds,
        typeIdColumn: `${a}_type_id`,
        idColumn: `${a}_id`,
        unionViewName: unionView,
        anyReference: anyRef,
      });
    } else {
      reqs.push({ kind: "scalar", name: a, label: f.refPathLabel ?? a });
    }
  }
  return { requisites: reqs };
}

/** Имя узкой reference_lookup-view по набору типов (зеркало backend unionViewName). */
export function unionViewNameFor(typeIds: number[]): string {
  const ids = [...new Set(typeIds)].sort((x, y) => x - y).join("_");
  return `reference_lookup_u_${ids}`;
}

/** {@link RefFieldRef} из ссылочного реквизита производной схемы. */
export function refFromOutputRequisite(r: Extract<OutputRequisite, { kind: "ref" }>): RefFieldRef {
  return {
    name: r.name, label: r.label, refTypeIds: r.refTypeIds,
    refTypeIdColumn: r.typeIdColumn, refIdColumn: r.idColumn,
    unionViewName: r.unionViewName, anyReference: !!r.anyReference,
  };
}

function sanitize(s: string): string {
  const cleaned = s.replace(/[^A-Za-z0-9_]/g, "_").replace(/_+/g, "_").replace(/^_+|_+$/g, "");
  return /^[A-Za-z_]/.test(cleaned) ? cleaned : "_" + cleaned;
}

let _seq = 0;
const localId = (p: string) => `${p}_${Date.now().toString(36)}_${(_seq++).toString(36)}`;

function srcRef(s: Source): string {
  if (s.alias && s.alias.trim()) return s.alias.trim();
  return s.schema && s.schema.trim() ? `${s.schema}.${s.name}` : s.name;
}

/** Удерживаемый указатель ссылки: где физически лежат её колонки. */
interface Pointer {
  anchorAlias: string;
  typeIdColumn: string | null;// колонка-дискриминатор (null → константный тип, self-ref)
  idColumn: string;
  constType: number | null;   // для self-ref концертного объекта — его typeId
  typeIds: number[];
  unionViewName: string | null;
  anyReference: boolean;      // удерживаемая ссылка — any-reference (любой тип)
}

interface SimState {
  sources: Source[];
  joins: Join[];
  pointer: Pointer;           // ссылка «в руке» (для Ссылка/деректа)
  alias: string | null;       // материализованный концертный алиас (null — держим указатель, тип не уточнён)
  typeId: number | null;
  idColumn: string | null;
  logicalKey: string;
  graph?: ReferenceGraph;     // для полиморфного pull'а union-скаляров (mergedScalar)
}

function hasSource(s: Source[], ref: string): boolean { return s.some((x) => srcRef(x) === ref); }
function hasJoinTo(j: Join[], ref: string): boolean { return j.some((x) => x.rightTable === ref); }

/**
 * Прогоняет путь по запросу: материализует источники/джойны для cast-шагов
 * (field — без join), отслеживая удерживаемый указатель. Возвращает итоговое
 * состояние (включая указатель — для терминального листа).
 */
function simulate(q: Query, path: RefPath, graph?: ReferenceGraph): SimState {
  let sources = [...q.sources];
  let joins = [...q.joins];

  let logicalKey = path.rootRef;
  let alias: string | null = path.rootRef;
  let typeId: number | null = path.rootTypeId;
  let idColumn: string | null = path.rootIdColumn;
  let pointer: Pointer = {
    anchorAlias: path.rootRef, typeIdColumn: null, idColumn: path.rootIdColumn,
    constType: path.rootTypeId, typeIds: [path.rootTypeId], unionViewName: null,
    anyReference: false,
  };

  for (const step of path.steps) {
    if (step.kind === "field") {
      const f = step.field;
      const anchor = alias ?? pointer.anchorAlias;
      pointer = {
        anchorAlias: anchor,
        typeIdColumn: f.refTypeIdColumn,
        idColumn: f.refIdColumn ?? "id",
        constType: null,
        typeIds: f.refTypeIds,
        unionViewName: f.unionViewName,
        anyReference: !!f.anyReference,
      };
      logicalKey = `${logicalKey}.${f.name}`;
      alias = null;
      typeId = f.refTypeIds.length === 1 ? f.refTypeIds[0] : null;
      idColumn = null;
    } else {
      // cast: спускаем указатель в конкретную таблицу (один join, дедуп по алиасу).
      const childAlias = sanitize(
        `${logicalKey}${pointer.typeIds.length > 1 ? "_t" + step.typeId : ""}`);
      if (!hasSource(sources, childAlias)) {
        sources = [...sources, {
          id: localId("src"), kind: "table", name: step.table, alias: childAlias,
          derived: true, typeId: step.typeId,
        }];
      }
      if (!hasJoinTo(joins, childAlias)) {
        const conds: string[] = [];
        if (pointer.typeIdColumn) {
          conds.push(`${pointer.anchorAlias}.${pointer.typeIdColumn} = ${step.typeId}`);
        }
        if (pointer.constType != null && pointer.constType !== step.typeId) {
          conds.push("1 = 0"); // несовместимый каст моно self-ref → пусто
        }
        joins = [...joins, {
          id: localId("jn"),
          leftTable: pointer.anchorAlias, leftColumn: pointer.idColumn,
          operator: "=", rightTable: childAlias, rightColumn: step.idColumn,
          leftAll: true, rightAll: false,
          extraOn: conds.length ? conds.join(" AND ") : undefined,
          auto: true,
        }];
      }
      logicalKey = childAlias;
      alias = childAlias;
      typeId = step.typeId;
      idColumn = step.idColumn;
      pointer = {
        anchorAlias: childAlias, typeIdColumn: null, idColumn: step.idColumn,
        constType: step.typeId, typeIds: [step.typeId], unionViewName: null,
        anyReference: false,
      };
    }
  }

  return { sources, joins, pointer, alias, typeId, idColumn, logicalKey, graph };
}

interface Resolved {
  sources: Source[];
  joins: Join[];
  expression: string;
  isExpression: boolean;
  table?: string;
  column?: string;
  alias: string;
  /** Для удержанной ссылки — дескриптор двухколоночного ссылочного поля. */
  ref?: {
    typeIdExpr: string | null;
    idExpr: string;
    constTypeId: number | null;
    typeIds: number[];
    anyReference?: boolean;
  };
}

function resolveLeaf(sim: SimState, leaf: RefLeaf): Resolved {
  let sources = sim.sources;
  let joins = sim.joins;
  const key = sim.logicalKey;

  if (leaf.kind === "scalar") {
    const alias = sim.alias ?? sim.pointer.anchorAlias;
    return {
      sources, joins, isExpression: false, table: alias, column: leaf.column,
      expression: `${alias}.${leaf.column}`, alias: sanitize(`${key}_${leaf.column}`),
    };
  }

  if (leaf.kind === "mergedScalar") {
    // Полиморфный pull скаляра union-ссылки: LEFT JOIN таблицы каждого типа по
    // дискриминатору, значение — CASE по type_id. Один реквизит → один столбец.
    const p = sim.pointer;
    const whenParts: string[] = [];
    for (const tid of leaf.merged.presentIn) {
      const col = leaf.merged.scalarColumnByType[tid];
      if (!col) continue;
      const t = sim.graph?.type(tid);
      if (!t) continue;
      const childAlias = sanitize(`${key}_t${tid}`);
      if (!hasSource(sources, childAlias)) {
        sources = [...sources, { id: localId("src"), kind: "table", name: t.table, alias: childAlias, derived: true, typeId: tid }];
      }
      if (!hasJoinTo(joins, childAlias)) {
        const conds: string[] = [];
        if (p.typeIdColumn) conds.push(`${p.anchorAlias}.${p.typeIdColumn} = ${tid}`);
        joins = [...joins, {
          id: localId("jn"), leftTable: p.anchorAlias, leftColumn: p.idColumn,
          operator: "=", rightTable: childAlias, rightColumn: t.idColumn,
          leftAll: true, rightAll: false, extraOn: conds.length ? conds.join(" AND ") : undefined,
          auto: true,
        }];
      }
      const disc = p.typeIdColumn ? `${p.anchorAlias}.${p.typeIdColumn}` : null;
      if (disc) whenParts.push(`WHEN ${tid} THEN ${childAlias}.${col}`);
      else whenParts.push(`/*const*/ ${childAlias}.${col}`); // self-ref const-type: один вариант
    }
    const disc = p.typeIdColumn ? `${p.anchorAlias}.${p.typeIdColumn}` : null;
    const expr = disc
      ? `CASE ${disc} ${whenParts.join(" ")} ELSE NULL END`
      // const-type указатель: ровно один участник — берём прямо его колонку.
      : (whenParts[0]?.replace("/*const*/ ", "") ?? "NULL");
    return {
      sources, joins, isExpression: true, expression: expr,
      alias: sanitize(`${key}_${leaf.merged.name}`),
    };
  }

  if (leaf.kind === "lookupCol") {
    // union-стандартный реквизит: join к reference_lookup_u_* по (type_id, id).
    const p = sim.pointer;
    const lkAlias = sanitize(`${key}_lk`);
    if (!hasSource(sources, lkAlias)) {
      sources = [...sources, {
        id: localId("src"), kind: "table", name: leaf.viewName, alias: lkAlias, derived: true,
      }];
    }
    if (!hasJoinTo(joins, lkAlias)) {
      joins = [...joins, {
        id: localId("jn"),
        leftTable: p.anchorAlias, leftColumn: p.typeIdColumn ?? "type_id",
        operator: "=", rightTable: lkAlias, rightColumn: "type_id",
        leftAll: true, rightAll: false,
        extraOn: `${lkAlias}.id = CAST(${p.anchorAlias}.${p.idColumn} AS VARCHAR)`,
        auto: true,
      }];
    }
    return {
      sources, joins, isExpression: false, table: lkAlias, column: leaf.column,
      expression: `${lkAlias}.${leaf.column}`, alias: sanitize(`${key}_${leaf.column}`),
    };
  }

  // reference — удержать ссылку как двухколоночный реквизит (typeId + id), без
  // конкатенации в строку. Разворачивается в <alias>_type_id, <alias>_id.
  const p = sim.pointer;
  const idExpr = `${p.anchorAlias}.${p.idColumn}`;
  const typeIdExpr = p.constType != null ? null : `${p.anchorAlias}.${p.typeIdColumn}`;
  // Суффикс «_ref» добавляем, только если его ещё нет (чтобы не плодить _ref_ref).
  const refAlias = /_ref$/.test(key) ? sanitize(key) : sanitize(`${key}_ref`);
  // expression — id-колонка (точечная), чтобы splitExpr разобрал «alias.column».
  // Сравнение по типу задаётся отдельной парой.
  return {
    sources, joins, isExpression: false, table: p.anchorAlias, column: p.idColumn,
    expression: idExpr, alias: refAlias,
    ref: {
      typeIdExpr,
      idExpr,
      constTypeId: p.constType,
      typeIds: p.typeIds,
      anyReference: p.anyReference,
    },
  };
}

/**
 * Применяет путь к запросу: материализует джойны (cast → LEFT JOIN, lookupCol →
 * join к reference_lookup) и добавляет поле-результат. Возвращает новый Query.
 */
export function addRequisite(q: Query, path: RefPath, leaf: RefLeaf, pathLabel: string, graph?: ReferenceGraph): Query {
  const sim = simulate(q, path, graph);
  const r = resolveLeaf(sim, leaf);
  let field: Field;
  if (r.ref) {
    // Ссылочный реквизит: одно виртуальное поле, разворачивается в две колонки.
    field = {
      id: localId("fld"), table: r.table, column: r.column!, expression: false,
      agg: "", alias: r.alias, refPathLabel: pathLabel, ref: r.ref,
    };
  } else if (r.isExpression) {
    field = { id: localId("fld"), column: r.expression, expression: true, agg: "", alias: r.alias, refPathLabel: pathLabel };
  } else {
    field = { id: localId("fld"), table: r.table, column: r.column!, expression: false, agg: "", alias: r.alias, refPathLabel: pathLabel };
  }
  return { ...q, sources: r.sources, joins: r.joins, fields: [...q.fields, field] };
}

/**
 * Выражение реквизита + Query с материализованными джойнами — для условий/связей.
 * Для удержанной ссылки выражение — точечная id-колонка; сравнение по типу
 * задаётся отдельным условием по дискриминатору.
 */
export function resolveExpression(q: Query, path: RefPath, leaf: RefLeaf, graph?: ReferenceGraph):
    { query: Query; expression: string;
      ref?: { typeIdExpr: string | null; idExpr: string; constTypeId: number | null; typeIds: number[] } } {
  const sim = simulate(q, path, graph);
  const r = resolveLeaf(sim, leaf);
  return { query: { ...q, sources: r.sources, joins: r.joins }, expression: r.expression, ref: r.ref };
}
