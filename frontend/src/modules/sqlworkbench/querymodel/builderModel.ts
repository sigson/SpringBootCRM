// AST конструктора запросов (1С-подобный), целиком на фронтенде.
// Иерархия: QueryPackage → PackageEntry[] → Statement → UnionMember[] → Query → Source[].
// SQL генерируется обходом дерева здесь же; исполнение — через сырой SELECT-эндпоинт
// (пакет с временными таблицами переписывается в один WITH … SELECT …).

let _seq = 0;
const uid = (p: string) => `${p}${(_seq = (_seq + 1) % 1e9)}_${Date.now() % 1e6}`;

export type AggFn = "" | "SUM" | "COUNT" | "COUNT_DISTINCT" | "MIN" | "MAX" | "AVG";

export const AGG_OPTIONS: { value: AggFn; label: string }[] = [
  { value: "", label: "— (group)" },
  { value: "SUM", label: "Sum" },
  { value: "COUNT", label: "Count" },
  { value: "COUNT_DISTINCT", label: "Distinct count" },
  { value: "MIN", label: "Minimum" },
  { value: "MAX", label: "Maximum" },
  { value: "AVG", label: "Average" },
];

export function aggSql(fn: AggFn, expr: string): string {
  switch (fn) {
    case "SUM": return `SUM(${expr})`;
    case "COUNT": return `COUNT(${expr})`;
    case "COUNT_DISTINCT": return `COUNT(DISTINCT ${expr})`;
    case "MIN": return `MIN(${expr})`;
    case "MAX": return `MAX(${expr})`;
    case "AVG": return `AVG(${expr})`;
    default: return expr;
  }
}

export type CondOp =
  | "=" | "<>" | ">" | ">=" | "<" | "<="
  | "BETWEEN" | "IN" | "LIKE" | "IS NULL" | "IS NOT NULL";

export const COND_OPS: { value: CondOp; label: string }[] = [
  { value: "=", label: "= (equal)" },
  { value: "<>", label: "<> (not equal)" },
  { value: ">", label: "> (greater)" },
  { value: ">=", label: ">= (greater or equal)" },
  { value: "<", label: "< (less)" },
  { value: "<=", label: "<= (less or equal)" },
  { value: "BETWEEN", label: "BETWEEN" },
  { value: "IN", label: "In list (IN)" },
  { value: "LIKE", label: "LIKE (LIKE)" },
  { value: "IS NULL", label: "IS NULL" },
  { value: "IS NOT NULL", label: "IS NOT NULL" },
];

export const opNeedsValue = (op: CondOp) => op !== "IS NULL" && op !== "IS NOT NULL";
export const opNeedsTwoValues = (op: CondOp) => op === "BETWEEN";

export type SourceKind = "table" | "temp" | "subquery";

export interface Source {
  id: string;
  kind: SourceKind;
  schema?: string;
  /** имя таблицы/ВТ; для подзапроса — служебное (не используется). */
  name: string;
  alias?: string;
  /** только для kind==="subquery". */
  statement?: Statement;
  /** Ссылочный режим: typeId бизнес-объекта, к которому привязан источник. */
  typeId?: number;
  /** Ссылочный режим: источник создан автоматически при разворачивании реквизита (read-only в UI). */
  derived?: boolean;
}

export interface Field {
  id: string;
  table?: string;
  column: string;
  expression: boolean;
  alias?: string;
  agg: AggFn;
  /** Ссылочный режим: человекочитаемый путь реквизита («Власник.Роль.Найменування»). */
  refPathLabel?: string;
  /**
   * Ссылочный реквизит (1С-«Ссылка»): в SELECT разворачивается в две колонки
   * <alias>_type_id и <alias>_id (тип берётся из typeIdExpr или константы constTypeId).
   */
  ref?: {
    /** Колонка-дискриминатор типа; null — если тип фиксирован. */
    typeIdExpr: string | null;
    idExpr: string;
    /** Фиксированный typeId (моно/после cast). */
    constTypeId: number | null;
    /** Допустимые типы (1 = моно, ≥2 = union). */
    typeIds: number[];
    /** true — ссылка на любой тип (маркер AnyReference): тип-иерархия без общих реквизитов. */
    anyReference?: boolean;
  };
}

/** Дескриптор «реквизита по ссылке»: путь для отображения + координаты для
 * повторного открытия пикера на этом реквизите. */
export interface RefSide {
  /** Путь для отображения, напр. «Користувачі.Роль.Ссылка». */
  label: string;
  /** Корневой источник пути (какое дерево раскрывать в пикере). */
  sourceRef: string;
  /** Путь реквизита внутри источника (метки через точку). */
  pathLabel: string;
}

export interface Join {
  id: string;
  leftTable: string; leftColumn: string;
  operator: string;
  rightTable: string; rightColumn: string;
  leftAll: boolean; rightAll: boolean;
  /** Доп. условие ON (через AND): дискриминатор union-типа / составной ключ reference_lookup. */
  extraOn?: string;
  /** Связь сгенерирована автоматически при дереференсе ссылки: не редактируется
   * вручную, в ссылочном режиме скрыта из списка связей. */
  auto?: boolean;
  /** Режим произвольного ON (fx): всё условие задаётся выражением в {@link extraOn},
   * структурные стороны игнорируются. */
  custom?: boolean;
  /** Ссылочный режим: дескриптор левой/правой стороны, выбранной по ссылке. */
  leftRef?: RefSide;
  rightRef?: RefSide;
  /** Координаты ссылочной стороны (тип + id). Если ссылочны обе стороны, ON
   * сравнивает и тип, и id — иначе возможна uuid-коллизия между разными типами. */
  leftRefCols?: { typeIdExpr: string | null; idExpr: string; constTypeId: number | null };
  rightRefCols?: { typeIdExpr: string | null; idExpr: string; constTypeId: number | null };
}

export interface Cond {
  id: string;
  enabled: boolean;
  custom: boolean;
  field: string;
  op: CondOp;
  value: string;
  value2: string;
  connector: "AND" | "OR";
  agg: AggFn;
  /** Ссылочный режим: дескриптор поля, выбранного по ссылке (для отображения/пикера). */
  fieldRef?: RefSide;
  /**
   * Координаты ссылочного поля условия (typeId+id). При сравнении со ссылочным
   * параметром &P условие разворачивается в двойное (тип И id); для обычных
   * значений сравнение идёт по id.
   */
  ref?: {
    typeIdExpr: string | null;
    idExpr: string;
    constTypeId: number | null;
    typeIds: number[];
  };
}

export interface Order {
  id: string;
  /** Основное выражение сортировки. Для ССЫЛКИ — выражение id-колонки. */
  expression: string;
  ascending: boolean;
  /**
   * Для ссылочного поля сортировка идёт по двум колонкам (тип + id); здесь —
   * выражение колонки типа, либо null для константного типа (литерал в ORDER BY
   * трактуется СУБД как номер колонки). Симметрично GROUP BY в {@link queryToSql}.
   */
  refTypeExpr?: string | null;
  /** Человекочитаемая подпись выбранного поля для кнопки-пикера (UI). */
  label?: string;
}

/** Одна спецификация SELECT — участник объединения. */
export interface Query {
  id: string;
  distinct: boolean;
  sources: Source[];
  fields: Field[];
  joins: Join[];
  conditions: Cond[];
}

export interface UnionMember { id: string; all: boolean; query: Query; }

/** Цепочка объединения + порядок/ограничение для всего результата. */
export interface Statement {
  id: string;
  unions: UnionMember[];
  orderBy: Order[];
  firstN?: number;
}

export type EntryType = "select" | "createTemp" | "dropTemp";

export interface PackageEntry {
  id: string;
  name: string;
  type: EntryType;
  tempName?: string;
  statement: Statement;
}

export type ParamType = "value" | "list" | "table";
export const PARAM_TYPES: { value: ParamType; label: string }[] = [
  { value: "value", label: "Value" },
  { value: "list", label: "Value list" },
  { value: "table", label: "Value table" },
];

/** Тип данных параметра — определяет ввод и формат литерала. */
export type DataType = "text" | "number" | "date" | "datetime" | "boolean" | "raw" | "reference";
export const DATA_TYPES: { value: DataType; label: string }[] = [
  { value: "text", label: "Row" },
  { value: "number", label: "Number (double)" },
  { value: "date", label: "Date" },
  { value: "datetime", label: "Date and time" },
  { value: "boolean", label: "Boolean" },
  { value: "raw", label: "SQL-expression (as is)" },
];

export interface Param {
  name: string;
  type: ParamType;
  dataType: DataType;
  value: string;
  columns: string[];
  columnTypes: DataType[];
  rows: string[][];
  /**
   * Ссылочный параметр (dataType==="reference"): при исполнении разветвляется на
   * два скрытых под-параметра &<name>__t<suffix> (typeId) и &<name>__i<suffix> (id).
   */
  refSuffix?: string;
  refTypeId?: string;
  refId?: string;
  refTypeIds?: number[];
}

/** Сопоставить JDBC typeName типу данных параметра. */
export function dataTypeFromSql(typeName: string): DataType {
  const t = (typeName || "").toUpperCase();
  if (/BOOL|BIT/.test(t)) return "boolean";
  if (/(INT|DEC|NUMERIC|NUMBER|FLOAT|DOUBLE|REAL|MONEY|SERIAL)/.test(t)) return "number";
  if (/TIMESTAMP|DATETIME/.test(t)) return "datetime";
  if (/\bTIME\b/.test(t)) return "datetime";
  if (/DATE/.test(t)) return "date";
  return "text";
}

export interface QueryPackage {
  version: 1;
  entries: PackageEntry[];
  params: Param[];
}

export const newField = (f: Partial<Field> & { column: string }): Field =>
  ({ id: uid("fld"), expression: false, agg: "", ...f });
export const newJoin = (j: Omit<Join, "id">): Join => ({ id: uid("jn"), ...j });
export const newCond = (c?: Partial<Cond>): Cond => ({
  id: uid("cnd"), enabled: true, custom: false, field: "", op: "=",
  value: "", value2: "", connector: "AND", agg: "", ...c,
});
export const newOrder = (o?: Partial<Order>): Order =>
  ({ id: uid("ord"), expression: "", ascending: true, ...o });

export const newTableSource = (s: { schema?: string; name: string; kind?: SourceKind; alias?: string }): Source =>
  ({ id: uid("src"), kind: s.kind ?? "table", schema: s.schema, name: s.name, alias: s.alias });
export const newSubquerySource = (alias: string): Source =>
  ({ id: uid("src"), kind: "subquery", name: alias, alias, statement: newStatement() });

export const newQuery = (): Query =>
  ({ id: uid("q"), distinct: false, sources: [], fields: [], joins: [], conditions: [] });
export const newUnionMember = (all = false): UnionMember =>
  ({ id: uid("um"), all, query: newQuery() });
export function newStatement(): Statement {
  return { id: uid("st"), unions: [newUnionMember(false)], orderBy: [], firstN: undefined };
}
export const newEntry = (name: string, type: EntryType = "select"): PackageEntry =>
  ({ id: uid("pe"), name, type, tempName: type === "select" ? undefined : "TempTable1", statement: newStatement() });

export const newParam = (name: string): Param =>
  ({ name, type: "value", dataType: "text", value: "", columns: ["Column1"], columnTypes: ["text"], rows: [] });

export function emptyPackage(): QueryPackage {
  return { version: 1, entries: [newEntry("Query 1", "select")], params: [] };
}

export function identifier(s: Source): string {
  return s.schema && s.schema.trim() ? `${s.schema}.${s.name}` : s.name;
}
export function sourceRef(s: Source): string {
  if (s.alias && s.alias.trim()) return s.alias.trim();
  return s.kind === "subquery" ? (s.name || "Subquery") : identifier(s);
}
export function fieldExpr(f: Field): string {
  if (f.expression) return f.column;
  return f.table && f.table.trim() ? `${f.table}.${f.column}` : f.column;
}
export function joinTypeLabel(leftAll: boolean, rightAll: boolean): string {
  if (leftAll && rightAll) return "FULL · FULL";
  if (leftAll) return "LEFT · LEFT";
  if (rightAll) return "RIGHT · RIGHT";
  return "INNER · INNER";
}
function joinSql(leftAll: boolean, rightAll: boolean): string {
  if (leftAll && rightAll) return "FULL OUTER JOIN";
  if (leftAll) return "LEFT OUTER JOIN";
  if (rightAll) return "RIGHT OUTER JOIN";
  return "INNER JOIN";
}

const indent = (s: string, pad: string) => s.split("\n").map((l) => pad + l).join("\n");

function condRight(c: Cond): string {
  switch (c.op) {
    case "IS NULL":
    case "IS NOT NULL": return "";
    case "BETWEEN": return `${c.value} AND ${c.value2}`;
    case "IN": { const v = c.value.trim(); return v.startsWith("(") ? v : `(${v})`; }
    default: return c.value;
  }
}

/** Агрегат над ССЫЛКОЙ → один скалярный столбец (ключ ссылки). */
function refAggExpr(ref: NonNullable<Field["ref"]>, agg: AggFn): string {
  const typeExpr = ref.constTypeId != null ? String(ref.constTypeId) : (ref.typeIdExpr ?? "NULL");
  // моно-тип: достаточно id; union: ключ — пара (тип:id), чтобы COUNT_DISTINCT
  // считал именно различные ссылки, а не различные id разных типов.
  const key = ref.constTypeId != null
    ? ref.idExpr
    : `(CAST(${typeExpr} AS VARCHAR) || ':' || CAST(${ref.idExpr} AS VARCHAR))`;
  return aggSql(agg, key);
}

/** Базовое (до уникализации) имя выходной колонки поля. */
function baseAlias(f: Field): string {
  if (f.ref) return (f.alias && f.alias.trim()) ? f.alias.trim() : "ref";
  if (f.alias && f.alias.trim()) return f.alias.trim();
  return f.expression ? "" : f.column;
}

/**
 * Уникальные псевдонимы полей в пределах одного SELECT: повторяющиеся базовые
 * имена получают суффикс _2/_3… Возвращает Map<field.id, псевдоним | "">.
 */
export function resolveFieldAliases(fields: Field[]): Map<string, string> {
  const seen = new Map<string, number>();
  const res = new Map<string, string>();
  for (const f of fields) {
    const base = baseAlias(f);
    if (!base) { res.set(f.id, ""); continue; }
    const n = (seen.get(base) ?? 0) + 1;
    seen.set(base, n);
    res.set(f.id, n === 1 ? base : `${base}_${n}`);
  }
  return res;
}

function fieldSql(f: Field, alias: string): string {
  if (f.ref) {
    const a = alias || "ref";
    // Агрегат на ссылке → один скалярный столбец (иначе ссылка раскрылась бы в две колонки).
    if (f.agg !== "") return `${refAggExpr(f.ref, f.agg)} AS ${a}`;
    const typeExpr = f.ref.constTypeId != null
      ? String(f.ref.constTypeId)
      : (f.ref.typeIdExpr ?? "NULL");
    return `${typeExpr} AS ${a}_type_id, ${f.ref.idExpr} AS ${a}_id`;
  }
  const base = f.agg !== "" ? aggSql(f.agg, fieldExpr(f)) : fieldExpr(f);
  return alias ? `${base} AS ${alias}` : base;
}

function sourceSql(s: Source): string {
  if (s.kind === "subquery") {
    const inner = statementToSql(s.statement ?? newStatement());
    return `(\n${indent(inner, "  ")}\n) AS ${s.alias || s.name || "Subquery"}`;
  }
  const id = identifier(s);
  return s.alias && s.alias.trim() ? `${id} AS ${s.alias.trim()}` : id;
}

/**
 * Условие ON одной связи. Незаполненные стороны пропускаются; если заполнять
 * нечего — плейсхолдер {@code 1 = 1}, чтобы SQL оставался корректным.
 */
function joinOnSql(j: Join): string {
  // Произвольное условие соединения (fx): всё ON — это выражение в extraOn.
  if (j.custom) {
    const e = (j.extraOn ?? "").trim();
    return e || "1 = 1";
  }
  const parts: string[] = [];
  // Обе стороны — ссылки → сравниваем и тип, и id.
  if (j.leftRefCols && j.rightRefCols) {
    const op = j.operator || "=";
    const lt = j.leftRefCols, rt = j.rightRefCols;
    const ltType = lt.constTypeId != null ? String(lt.constTypeId) : (lt.typeIdExpr ?? "NULL");
    const rtType = rt.constTypeId != null ? String(rt.constTypeId) : (rt.typeIdExpr ?? "NULL");
    parts.push(`${ltType} ${op} ${rtType}`);
    parts.push(`${lt.idExpr} ${op} ${rt.idExpr}`);
    if (j.extraOn && j.extraOn.trim()) parts.push(j.extraOn.trim());
    return parts.join(" AND ");
  }
  const left = j.leftTable && j.leftColumn ? `${j.leftTable}.${j.leftColumn}` : "";
  const right = j.rightTable && j.rightColumn ? `${j.rightTable}.${j.rightColumn}` : "";
  if (left && right) parts.push(`${left} ${j.operator || "="} ${right}`);
  if (j.extraOn && j.extraOn.trim()) parts.push(j.extraOn.trim());
  return parts.length ? parts.join(" AND ") : "1 = 1";
}

function fromClause(q: Query): string {
  if (q.sources.length === 0) return "";
  // Отбрасываем осиротевшие derived-источники, на которые не ссылается ни один
  // join и ни одно поле — иначе они висят в FROM лишним кросс-джойном.
  const fieldRefs = new Set(
    q.fields.filter((f) => !f.expression && f.table && f.table.trim()).map((f) => f.table));
  const joinRefs = new Set<string>();
  for (const j of q.joins) { joinRefs.add(j.leftTable); joinRefs.add(j.rightTable); }
  const live = q.sources.filter((s) => !s.derived || fieldRefs.has(sourceRef(s)) || joinRefs.has(sourceRef(s)));
  const sources = live.length ? live : q.sources;
  const byRef = (ref: string) => sources.find((s) => sourceRef(s) === ref);
  if (q.joins.length === 0) {
    return sources.map(sourceSql).join(", ");
  }

  // Связи лежат в произвольном порядке. Источник вводит в FROM правая часть связи
  // (rightTable), левая — якорь, он должен быть введён до ссылок на него. Не вводим
  // один источник дважды (иначе дублируется alias).
  const rightRefs = new Set(q.joins.map((j) => j.rightTable));
  // Корень FROM — источник, который слева, но никогда не справа.
  const rootRef = q.joins.map((j) => j.leftTable).find((lt) => !rightRefs.has(lt)) ?? q.joins[0].leftTable;
  const root = byRef(rootRef) ?? sources[0];

  const present = new Set<string>([sourceRef(root)]);
  let chain = sourceSql(root);
  // Предикати звʼязків, чиє праве джерело вже введене іншим звʼязком: повторно
  // вводити той самий alias не можна, тож їх ON збираємо окремо й клеїмо до ланцюга.
  const extra: string[] = [];
  const emit = (j: Join) => {
    if (present.has(j.rightTable)) {
      const on = joinOnSql(j);
      if (on && on.trim() && on !== "1 = 1") extra.push(on);
      return;
    }
    const rt = byRef(j.rightTable);
    chain += `\n  ${joinSql(j.leftAll, j.rightAll)} ${rt ? sourceSql(rt) : j.rightTable}`;
    chain += ` ON ${joinOnSql(j)}`;
    present.add(j.rightTable);
  };

  // Жадно вводим связи, чей якорь уже в FROM, а правый источник — ещё нет.
  const remaining = [...q.joins];
  let progress = true;
  while (remaining.length && progress) {
    progress = false;
    for (let i = 0; i < remaining.length; i++) {
      const j = remaining[i];
      const anchored = present.has(j.leftTable) || present.has(j.rightTable);
      const introducesNew = !present.has(j.rightTable);
      if (anchored && introducesNew) {
        emit(j); remaining.splice(i, 1); progress = true; break;
      }
    }
  }
  // Остаток (несвязный граф / цикл) — как есть, чтобы ничего не потерять.
  for (const j of remaining) emit(j);

  // Предикати надлишкових звʼязків клеїмо до останнього ON ланцюга.
  if (extra.length && chain.includes(" ON ")) {
    chain += " AND " + extra.map((e) => `(${e})`).join(" AND ");
  }

  // Джерела, які не ввів жоден звʼязок, додаємо в FROM через кому перед ланцюгом
  // JOIN — інакше вони зникають із запиту. JOIN звʼязує тісніше за кому.
  const standalone = sources.filter((s) => !present.has(sourceRef(s)));
  for (const s of standalone) present.add(sourceRef(s));
  return [...standalone.map(sourceSql), chain].join(", ");
}

function conditionsSql(conds: Cond[], fields: Field[] = []): string {
  let sb = "";
  let first = true;
  for (const c of conds) {
    if (!first) sb += ` ${c.connector} `;
    first = false;
    if (c.custom) { sb += c.field; continue; }
    const refExp = refCondSql(c, fields);
    if (refExp) { sb += refExp; continue; }
    sb += `${c.field} ${c.op} ${condRight(c)}`.trimEnd();
  }
  return sb;
}

// Активный набор ссылочных параметров на время генерации SQL. Глобал, т.к.
// генерация синхронна.
let _refParams: ReadonlyMap<string, Param> = new Map();

function withRefParams<T>(params: Param[], fn: () => T): T {
  const prev = _refParams;
  _refParams = new Map(params.filter((p) => p.dataType === "reference").map((p) => [p.name, p]));
  try { return fn(); } finally { _refParams = prev; }
}

const PARAM_REF_RE = /^[&:]([A-Za-z_\u0400-\u04FF][A-Za-z0-9_\u0400-\u04FF]*)$/;

/**
 * Ссылочный дескриптор условия (тип+id). Берётся из {@code c.ref}; если не задан —
 * выводится по {@code c.field}, совпадающему с alias'ом ссылки (alias_id) или её
 * idExpr среди полей запроса.
 */
function condRefInfo(c: Cond, fields: Field[]): NonNullable<Cond["ref"]> | null {
  if (c.ref) return c.ref;
  const field = (c.field ?? "").trim();
  if (!field || !fields.length) return null;
  const aliases = resolveFieldAliases(fields);
  for (const f of fields) {
    if (!f.ref) continue;
    const a = aliases.get(f.id) ?? "";
    if ((a && field === `${a}_id`) || field === f.ref.idExpr) {
      return { typeIdExpr: f.ref.typeIdExpr, idExpr: f.ref.idExpr, constTypeId: f.ref.constTypeId, typeIds: f.ref.typeIds };
    }
  }
  return null;
}

/** Имя ссылочного параметра, с которым сравнивается условие (или null). */
function condRefParamName(c: Cond): string | null {
  const m = PARAM_REF_RE.exec((c.value ?? "").trim());
  return m ? m[1] : null;
}

/**
 * Поле условия — ссылка, а значение — ссылочный параметр &P: разворачиваем в
 * двойное условие (тип И id) по двум под-параметрам. Иначе null.
 */
function refCondSql(c: Cond, fields: Field[] = []): string | null {
  const ref = condRefInfo(c, fields);
  if (!ref) return null;
  const name = condRefParamName(c);
  if (!name) return null;
  const p = _refParams.get(name);
  if (!p || !p.refSuffix) return null;
  const typeExpr = ref.constTypeId != null ? String(ref.constTypeId) : (ref.typeIdExpr ?? "NULL");
  const tName = `&${name}__t${p.refSuffix}`;
  const iName = `&${name}__i${p.refSuffix}`;
  if (c.op === "=" || c.op === "<>") {
    const conj = c.op === "=" ? "AND" : "OR";
    return `(${typeExpr} ${c.op} ${tName} ${conj} ${ref.idExpr} ${c.op} ${iName})`;
  }
  return `${ref.idExpr} ${c.op} ${iName}`;
}

/** Имена параметров, участвующих в ссылочных сравнениях (рекурсивно по
 *  подзапросам) — кандидаты на авто-повышение до ссылочного типа. */
function collectRefParamNames(q: Query, out: Set<string>): void {
  for (const c of q.conditions) {
    if (c.custom) continue;
    if (condRefInfo(c, q.fields)) {
      const n = condRefParamName(c);
      if (n) out.add(n);
    }
  }
  for (const s of q.sources) {
    if (s.kind === "subquery" && s.statement) {
      for (const u of s.statement.unions) collectRefParamNames(u.query, out);
    }
  }
}

/**
 * Авто-повышение: параметр в ссылочном сравнении получает dataType="reference"
 * (раздвоение тип+id — под капотом). Идемпотентно: если повышать нечего, возвращает
 * тот же объект (безопасно для useEffect).
 */
export function promoteReferenceParams(pkg: QueryPackage): QueryPackage {
  const names = new Set<string>();
  for (const e of pkg.entries) {
    for (const u of e.statement?.unions ?? []) collectRefParamNames(u.query, names);
  }
  if (names.size === 0) return pkg;
  let changed = false;
  const params = pkg.params.map((p) => {
    if (names.has(p.name) && p.dataType !== "reference") {
      changed = true;
      return { ...p, dataType: "reference" as DataType, refSuffix: p.refSuffix || randomParamSuffix() };
    }
    return p;
  });
  return changed ? { ...pkg, params } : pkg;
}

function randomParamSuffix(): string { return Math.random().toString(36).slice(2, 8); }

/**
 * Понижение параметров перед inline-подстановкой: ссылочный параметр → два скаляра
 * (&P__t — typeId, &P__i — id) плюс запасной литерал для «голого» &P (берётся id).
 */
export function lowerParams(params: Param[]): Param[] {
  const out: Param[] = [];
  for (const p of params) {
    if (p.dataType === "reference") {
      const sfx = p.refSuffix ?? "";
      out.push({ ...newParam(`${p.name}__t${sfx}`), dataType: "number", value: p.refTypeId ?? "" });
      out.push({ ...newParam(`${p.name}__i${sfx}`), dataType: "text", value: p.refId ?? "" });
      out.push({ ...newParam(p.name), dataType: "text", value: p.refId ?? "" });
    } else {
      out.push(p);
    }
  }
  return out;
}

export function queryToSql(q: Query): string {
  const aggregated = q.fields.some((f) => f.agg !== "");
  const aliases = resolveFieldAliases(q.fields);
  const cols = q.fields.length ? q.fields.map((f) => fieldSql(f, aliases.get(f.id) ?? "")).join(", ") : "*";
  let sb = "SELECT " + (q.distinct ? "DISTINCT " : "") + cols;
  const from = fromClause(q);
  if (from) sb += "\nFROM " + from;

  const enabled = q.conditions.filter((c) => c.enabled && (c.custom ? c.field.trim() : c.field.trim()));
  const where = enabled.filter((c) => c.agg === "");
  const having = enabled.filter((c) => c.agg !== "");
  if (where.length) sb += "\nWHERE " + conditionsSql(where, q.fields);

  if (aggregated) {
    // Группируем по неагрегированным полям. Ссылочное поле = две колонки
    // (<alias>_type_id, <alias>_id) — группируем по обоим SELECT-псевдонимам.
    // Псевдоним обязателен для константного типа: целочисленный литерал в GROUP BY
    // СУБД трактует как номер колонки.
    const groupCols = q.fields.filter((f) => f.agg === "").flatMap((f) => {
      if (f.ref) {
        const a = aliases.get(f.id) || "ref";
        return [`${a}_type_id`, `${a}_id`];
      }
      return [fieldExpr(f)];
    });
    if (groupCols.length) sb += "\nGROUP BY " + groupCols.join(", ");
  }
  if (having.length) {
    const mapped = having.map((c) => ({ ...c, field: c.custom ? c.field : aggSql(c.agg, c.field) }));
    sb += "\nHAVING " + conditionsSql(mapped, q.fields);
  }
  return sb;
}

export function statementToSql(st: Statement): string {
  let sb = st.unions.map((u, i) =>
    (i === 0 ? "" : (u.all ? "\nUNION ALL\n" : "\nUNION\n")) + queryToSql(u.query)
  ).join("");
  if (st.orderBy.length) {
    // Ссылочное поле сортируется по паре колонок (тип, id). Константный тип в
    // ORDER BY не пишем (литерал = номер колонки). Симметрично GROUP BY.
    sb += "\nORDER BY " + st.orderBy
      .filter((o) => o.expression.trim())
      .flatMap((o) => {
        const dir = o.ascending ? "ASC" : "DESC";
        const terms: string[] = [];
        if (o.refTypeExpr && o.refTypeExpr.trim()) terms.push(`${o.refTypeExpr} ${dir}`);
        terms.push(`${o.expression} ${dir}`);
        return terms;
      })
      .join(", ");
  }
  if (st.firstN != null && st.firstN > 0) sb += `\nFETCH FIRST ${st.firstN} ROWS ONLY`;
  return sb;
}

export function entryToSql(e: PackageEntry): string {
  if (e.type === "dropTemp") return `DROP TABLE IF EXISTS ${e.tempName ?? ""}`;
  if (e.type === "createTemp") {
    return `CREATE TEMPORARY TABLE ${e.tempName ?? ""} AS\n${statementToSql(e.statement)}`;
  }
  return statementToSql(e.statement);
}

export function packageToSql(pkg: QueryPackage): string {
  if (pkg.entries.length === 0) return "";
  // Предпросмотр показывает то же раздвоение ссылочного сравнения, что уйдёт в исполнение.
  return withRefParams(pkg.params, () => pkg.entries.map(entryToSql).join(";\n\n") + ";");
}

export function buildExecutableSql(pkg: QueryPackage, entryId: string): string {
  const idx = pkg.entries.findIndex((e) => e.id === entryId);
  if (idx < 0) throw new Error("Package statement not found");
  const target = pkg.entries[idx];
  if (target.type === "dropTemp") throw new Error("Nothing to run: the «Drop TT» statement returns no data");

  return withRefParams(pkg.params, () => {
    const ctes = pkg.entries
      .slice(0, idx)
      .filter((e) => e.type === "createTemp" && e.tempName)
      .map((e) => `${e.tempName} AS (\n${indent(statementToSql(e.statement), "  ")}\n)`);

    const stmtSql = statementToSql(target.statement);
    let sql = ctes.length ? `WITH ${ctes.join(",\n")}\n${stmtSql}` : stmtSql;
    // Ссылочные параметры понижаются до двух скрытых под-параметров.
    sql = inlineParams(sql, lowerParams(pkg.params));
    return sql;
  });
}

const PARAM_RE = /[&:]([A-Za-z_\u0400-\u04FF][A-Za-z0-9_\u0400-\u04FF]*)/g;

export function extractParamNames(sql: string): string[] {
  const set = new Set<string>();
  let m: RegExpExecArray | null;
  PARAM_RE.lastIndex = 0;
  while ((m = PARAM_RE.exec(sql))) set.add(m[1]);
  return [...set];
}

export function detectParamType(sql: string, name: string): ParamType {
  const n = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  if (new RegExp(`(?:\\bIN\\b|\\bIn\\b)\\s*\\(\\s*[&:]${n}\\b`, "i").test(sql)) return "list";
  if (new RegExp(`(?:\\bFROM\\b|\\bJOIN\\b|\\bFROM\\b)\\s+[&:]${n}\\b`, "i").test(sql)) return "table";
  return "value";
}

function splitCsv(s: string): string[] {
  return s.split(",").map((x) => x.trim()).filter((x) => x !== "");
}

const sqlEscape = (s: string) => s.replace(/'/g, "''");

/** A single scalar → SQL-literal by data type. */
export function renderScalar(dt: DataType, raw: string): string {
  const v = (raw ?? "").trim();
  if (dt === "raw") return v === "" ? "NULL" : v;
  if (v === "") return "NULL";
  switch (dt) {
    case "number": { const n = Number(v.replace(",", ".")); return Number.isFinite(n) ? String(n) : "NULL"; }
    case "boolean": return /^(true|1|yes|true|t|y)$/i.test(v) ? "TRUE" : "FALSE";
    case "date": return `DATE '${sqlEscape(v)}'`;
    case "datetime": return `TIMESTAMP '${sqlEscape(v.replace("T", " "))}'`;
    case "text":
    default: return `'${sqlEscape(v)}'`;
  }
}

export function paramLiteral(p: Param): string {
  if (p.type === "list") {
    const vals = splitCsv(p.value).map((x) => renderScalar(p.dataType, x));
    return `(${vals.length ? vals.join(", ") : "NULL"})`;
  }
  if (p.type === "table") {
    if (p.rows.length === 0) return "(VALUES (NULL))";
    const body = p.rows
      .map((r) => `(${r.map((cell, ci) => renderScalar(p.columnTypes[ci] ?? "text", cell)).join(", ")})`)
      .join(", ");
    return `(VALUES ${body})`;
  }
  return renderScalar(p.dataType, p.value);
}

export function inlineParams(sql: string, params: Param[]): string {
  if (params.length === 0) return sql;
  const byName = new Map(params.map((p) => [p.name, p]));
  return sql.replace(PARAM_RE, (full, name: string) => {
    const p = byName.get(name);
    return p ? paramLiteral(p) : full;
  });
}

/** Sync the parameter table with the parameters from SQL (keeping the settings of existing ones). */
export function syncParams(existing: Param[], sql: string, inferType?: (name: string) => DataType | undefined): Param[] {
  const names = extractParamNames(sql);
  const byName = new Map(existing.map((p) => [p.name, p]));
  // The reference parameter is present in SQL as two hidden sub-parameters
  // (&P__t/&P__i). Collapse them to the base P, so that it does not split into two.
  const syntheticToBase = new Map<string, string>();
  for (const p of existing) {
    if (p.dataType === "reference" && p.refSuffix) {
      syntheticToBase.set(`${p.name}__t${p.refSuffix}`, p.name);
      syntheticToBase.set(`${p.name}__i${p.refSuffix}`, p.name);
    }
  }
  const out: Param[] = [];
  const seen = new Set<string>();
  for (const n of names) {
    const base = syntheticToBase.get(n) ?? n;
    if (seen.has(base)) continue;
    seen.add(base);
    const prev = byName.get(base);
    if (prev) { out.push(prev); continue; }
    const p = newParam(base);
    p.type = detectParamType(sql, base);
    p.dataType = inferType?.(base) ?? "text";
    if (p.type === "table") p.dataType = "text";
    out.push(p);
  }
  return out;
}

/** Extract the column name left of the parameter (to infer the type from metadata). */
export function paramLeftColumn(sql: string, name: string): string | undefined {
  const n = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const re = new RegExp(`([A-Za-z_\u0400-\u04FF][\\w\u0400-\u04FF.]*)\\s*(?:=|<>|>=|<=|>|<|LIKE|LIKE|IN|In)\\s*\\(?\\s*[&:]${n}\\b`, "i");
  const m = re.exec(sql);
  if (!m) return undefined;
  const parts = m[1].split(".");
  return parts[parts.length - 1];
}

export function tempTablesBefore(pkg: QueryPackage, entryId: string): string[] {
  const idx = pkg.entries.findIndex((e) => e.id === entryId);
  const out: string[] = [];
  for (let i = 0; i < idx; i++) {
    const e = pkg.entries[i];
    if (e.type === "createTemp" && e.tempName) out.push(e.tempName);
    if (e.type === "dropTemp" && e.tempName) {
      const j = out.indexOf(e.tempName);
      if (j >= 0) out.splice(j, 1);
    }
  }
  return out;
}

/** Output column names of the field: regular → one, a reference → two
 * (<alias>_type_id, <alias>_id). */
export function fieldOutputColumns(f: Field): string[] {
  if (f.ref) {
    const a = (f.alias && f.alias.trim()) ? f.alias.trim() : "ref";
    return [`${a}_type_id`, `${a}_id`];
  }
  const n = f.alias?.trim() || (f.expression ? "" : f.column);
  return n ? [n] : [];
}

/** Output column names from the field list (with alias de-duplication and aggregates
 * over the reference) - for the TT/subquery schema. */
export function fieldsOutputColumns(fields: Field[]): string[] {
  const aliases = resolveFieldAliases(fields);
  const out: string[] = [];
  for (const f of fields) {
    const a = aliases.get(f.id) ?? "";
    if (f.ref) {
      if (f.agg !== "") { if (a) out.push(a); }          // reference aggregate → one column
      else { const base = a || "ref"; out.push(`${base}_type_id`, `${base}_id`); }
    } else if (a) {
      out.push(a);
    }
  }
  return out;
}

/** Output columns of the temp table (from the fields of its defining statement). */
export function tempColumns(pkg: QueryPackage, tempName: string): string[] {
  const e = pkg.entries.find((x) => x.type === "createTemp" && x.tempName === tempName);
  if (!e) return [];
  const q = e.statement.unions[0]?.query;
  if (!q) return [];
  return fieldsOutputColumns(q.fields);
}

export function exportPackage(pkg: QueryPackage): string {
  return JSON.stringify(pkg, null, 2);
}

export function importPackage(json: string): QueryPackage {
  const obj = JSON.parse(json);
  if (!obj || typeof obj !== "object") throw new Error("Not an object");
  if (!Array.isArray(obj.entries) || obj.entries.length === 0) throw new Error("The package has no statements (entries)");
  const pkg: QueryPackage = {
    version: 1,
    entries: obj.entries.map((e: any) => normalizeEntry(e)),
    params: Array.isArray(obj.params) ? obj.params.map(normalizeParam) : [],
  };
  return pkg;
}

function normalizeRefSide(r: any): RefSide | undefined {
  if (!r || typeof r !== "object") return undefined;
  const label = String(r.label ?? "");
  const sourceRef = String(r.sourceRef ?? "");
  const pathLabel = String(r.pathLabel ?? "");
  if (!label && !sourceRef && !pathLabel) return undefined;
  return { label, sourceRef, pathLabel };
}

function normalizeRefCols(r: any): { typeIdExpr: string | null; idExpr: string; constTypeId: number | null } | undefined {
  if (!r || typeof r !== "object") return undefined;
  const idExpr = String(r.idExpr ?? "");
  if (!idExpr) return undefined;
  return {
    typeIdExpr: r.typeIdExpr != null ? String(r.typeIdExpr) : null,
    idExpr,
    constTypeId: typeof r.constTypeId === "number" ? r.constTypeId : null,
  };
}

function normalizeParam(p: any): Param {
  const dt = (["text", "number", "date", "datetime", "boolean", "raw", "reference"].includes(p?.dataType) ? p.dataType : "text") as DataType;
  return {
    name: String(p?.name ?? ""),
    type: (["value", "list", "table"].includes(p?.type) ? p.type : "value") as ParamType,
    dataType: dt,
    value: String(p?.value ?? ""),
    columns: Array.isArray(p?.columns) ? p.columns.map(String) : ["Column1"],
    columnTypes: Array.isArray(p?.columnTypes)
      ? p.columnTypes.map((x: any) => (["text", "number", "date", "datetime", "boolean", "raw"].includes(x) ? x : "text"))
      : (Array.isArray(p?.columns) ? p.columns.map(() => "text") : ["text"]),
    rows: Array.isArray(p?.rows) ? p.rows.map((r: any) => (Array.isArray(r) ? r.map(String) : [])) : [],
    refSuffix: p?.refSuffix ? String(p.refSuffix) : undefined,
    refTypeId: p?.refTypeId != null ? String(p.refTypeId) : undefined,
    refId: p?.refId != null ? String(p.refId) : undefined,
    refTypeIds: Array.isArray(p?.refTypeIds) ? p.refTypeIds.filter((x: any) => typeof x === "number") : undefined,
  };
}
function normalizeEntry(e: any): PackageEntry {
  return {
    id: e?.id || uid("pe"),
    name: String(e?.name ?? "Query"),
    type: (["select", "createTemp", "dropTemp"].includes(e?.type) ? e.type : "select") as EntryType,
    tempName: e?.tempName ? String(e.tempName) : undefined,
    statement: normalizeStatement(e?.statement),
  };
}
function normalizeStatement(st: any): Statement {
  const unions = Array.isArray(st?.unions) && st.unions.length
    ? st.unions.map((u: any, i: number) => ({ id: u?.id || uid("um"), all: !!u?.all && i > 0, query: normalizeQuery(u?.query) }))
    : [newUnionMember(false)];
  return {
    id: st?.id || uid("st"),
    unions,
    orderBy: Array.isArray(st?.orderBy) ? st.orderBy.map((o: any) => ({ id: o?.id || uid("ord"), expression: String(o?.expression ?? ""), ascending: o?.ascending !== false, refTypeExpr: o?.refTypeExpr != null ? String(o.refTypeExpr) : null, label: o?.label != null ? String(o.label) : undefined })) : [],
    firstN: typeof st?.firstN === "number" ? st.firstN : undefined,
  };
}
function normalizeQuery(q: any): Query {
  return {
    id: q?.id || uid("q"),
    distinct: !!q?.distinct,
    sources: Array.isArray(q?.sources) ? q.sources.map(normalizeSource) : [],
    fields: Array.isArray(q?.fields) ? q.fields.map((f: any) => ({
      id: f?.id || uid("fld"), table: f?.table, column: String(f?.column ?? ""),
      expression: !!f?.expression, alias: f?.alias, agg: (f?.agg ?? "") as AggFn,
      refPathLabel: f?.refPathLabel,
      ref: f?.ref && typeof f.ref === "object" ? {
        typeIdExpr: f.ref.typeIdExpr ?? null,
        idExpr: String(f.ref.idExpr ?? ""),
        constTypeId: typeof f.ref.constTypeId === "number" ? f.ref.constTypeId : null,
        typeIds: Array.isArray(f.ref.typeIds) ? f.ref.typeIds.filter((x: any) => typeof x === "number") : [],
        anyReference: !!f.ref.anyReference,
      } : undefined,
    })) : [],
    joins: Array.isArray(q?.joins) ? q.joins.map((j: any) => ({
      id: j?.id || uid("jn"), leftTable: String(j?.leftTable ?? ""), leftColumn: String(j?.leftColumn ?? ""),
      operator: String(j?.operator ?? "="), rightTable: String(j?.rightTable ?? ""), rightColumn: String(j?.rightColumn ?? ""),
      leftAll: !!j?.leftAll, rightAll: !!j?.rightAll,
      extraOn: j?.extraOn ? String(j.extraOn) : undefined,
      auto: !!j?.auto,
      custom: !!j?.custom,
      leftRef: normalizeRefSide(j?.leftRef),
      rightRef: normalizeRefSide(j?.rightRef),
      leftRefCols: normalizeRefCols(j?.leftRefCols),
      rightRefCols: normalizeRefCols(j?.rightRefCols),
    })) : [],
    conditions: Array.isArray(q?.conditions) ? q.conditions.map((c: any) => ({
      id: c?.id || uid("cnd"), enabled: c?.enabled !== false, custom: !!c?.custom, field: String(c?.field ?? ""),
      op: (c?.op ?? "=") as CondOp, value: String(c?.value ?? ""), value2: String(c?.value2 ?? ""),
      connector: (c?.connector === "OR" ? "OR" : "AND"), agg: (c?.agg ?? "") as AggFn,
      fieldRef: normalizeRefSide(c?.fieldRef),
      ref: c?.ref && typeof c.ref === "object" ? {
        typeIdExpr: c.ref.typeIdExpr ?? null,
        idExpr: String(c.ref.idExpr ?? ""),
        constTypeId: typeof c.ref.constTypeId === "number" ? c.ref.constTypeId : null,
        typeIds: Array.isArray(c.ref.typeIds) ? c.ref.typeIds.filter((x: any) => typeof x === "number") : [],
      } : undefined,
    })) : [],
  };
}
function normalizeSource(s: any): Source {
  const kind: SourceKind = ["table", "temp", "subquery"].includes(s?.kind) ? s.kind : "table";
  return {
    id: s?.id || uid("src"), kind, schema: s?.schema, name: String(s?.name ?? ""),
    alias: s?.alias, statement: kind === "subquery" ? normalizeStatement(s?.statement) : undefined,
    typeId: typeof s?.typeId === "number" ? s.typeId : undefined,
    derived: !!s?.derived,
  };
}

// Embedded AST-comment: QueryPackage is packed into base64 and is baked into
// multiline `--`-comment under SQL. Any copied from workbench'and
// запрос несёт своё дерево; при вставке обратно конструктор его восстанавливает.

const AST_BEGIN = "-- ==== BEGIN SpringBootCRM QUERY-BUILDER AST ====";
const AST_END = "-- ==== END SpringBootCRM QUERY-BUILDER AST ====";
const AST_BLOCK_RE = /-- ==== BEGIN SpringBootCRM QUERY-BUILDER AST ====\s*([\s\S]*?)-- ==== END SpringBootCRM QUERY-BUILDER AST ====/;

/** UTF-8-safe base64 (для кириллицы в названиях полей/параметров). */
function utf8ToB64(str: string): string {
  const bytes = new TextEncoder().encode(str);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}
function b64ToUtf8(b64: string): string {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

const chunk = (s: string, n: number): string[] => {
  const out: string[] = [];
  for (let i = 0; i < s.length; i += n) out.push(s.slice(i, i + n));
  return out.length ? out : [""];
};

/** Сформировать многострочный `--`-комментарий с упакованным AST пакета. */
export function packageAstComment(pkg: QueryPackage): string {
  const b64 = utf8ToB64(JSON.stringify(pkg));
  const body = chunk(b64, 64).map((l) => "-- " + l).join("\n");
  return `${AST_BEGIN}\n${body}\n${AST_END}`;
}

/** Есть ли в тексте встроенный AST-блок. */
export function hasAstComment(sql: string): boolean {
  return AST_BLOCK_RE.test(sql);
}

/** Удалить AST-блок из SQL (например, перед исполнением). */
export function stripAstComment(sql: string): string {
  return sql.replace(AST_BLOCK_RE, "").replace(/\n{3,}$/g, "\n").trimEnd();
}

/** SQL + встроенный AST-блок под ним (готово к копированию/сохранению). */
export function sqlWithAst(sql: string, pkg: QueryPackage): string {
  return `${sql.trimEnd()}\n\n${packageAstComment(pkg)}\n`;
}

/**
 * Извлечь пакет из текста: SQL со встроенным AST или «голый» JSON AST. Возвращает
 * null, если ничего не распознано.
 */
export function extractPackageFromText(text: string): QueryPackage | null {
  const m = AST_BLOCK_RE.exec(text);
  if (m) {
    const b64 = m[1]
      .split("\n")
      .map((l) => l.replace(/^\s*--\s?/, "").trim())
      .join("");
    try {
      return importPackage(b64ToUtf8(b64));
    } catch {
      return null;
    }
  }
  // Фоллбэк: «голый» JSON AST.
  const trimmed = text.trim();
  if (trimmed.startsWith("{")) {
    try { return importPackage(trimmed); } catch { return null; }
  }
  return null;
}
