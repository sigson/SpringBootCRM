/**
 * Типи для системи фільтрів ListView. Виділено в окремий модуль, щоб уникнути
 * циклічних залежностей між ListView, FilterDialog, ReferencePicker та ін.
 */

import type { ReactNode } from "react";
import type { RowQuery } from "./rowSource";

/** Тип значення у колонці — визначає вид input'у в FilterDialog та логіку порівняння. */
export type ColumnFilterType =
  | "string"      // довільний рядок — substring/contains за замовчуванням
  | "number"      // числове — порівняння <, ≤, >, ≥
  | "date"        // дата — picker дат, ДД.ММ.РРРР format у raw, ISO у input[type=date]
  | "boolean"     // true/false — dropdown
  | "enum"        // фіксований список варіантів — dropdown
  | "reference"   // ссилка на агрегат — picker з власною ListView (рекурсивно)
  | "unionReference"  // union-ссилка (кілька типів) — спершу [Т] вибір типу, потім picker
;

// Union-reference: значення — пара (typeId, id), кодується як "<typeId>:<uuid>";
// бекенд за нею додає дискримінатор <col>_type_id поряд з <col>_id.

/** Зібрати raw-значення union-ссилки з пари (typeId, id). */
export function encodeUnionRef(typeId: number | null | undefined, id: string | null | undefined): string {
  if (typeId == null || !id) return "";
  return `${typeId}:${id}`;
}

/** Розібрати raw-значення union-ссилки → {typeId, id} (або null, якщо порожньо/невалідно). */
export function decodeUnionRef(raw: string | null | undefined): { typeId: number; id: string } | null {
  if (!raw) return null;
  const i = raw.indexOf(":");
  if (i <= 0) return null;
  const typeId = Number(raw.slice(0, i));
  const id = raw.slice(i + 1);
  if (!Number.isFinite(typeId) || !id) return null;
  return { typeId, id };
}

/** Оператори порівняння. */
export type FilterOp =
  | "eq" | "neq"
  | "gt" | "gte" | "lt" | "lte"
  | "contains" | "ncontains"
  | "startsWith" | "endsWith"
  | "in" | "nin"
  | "empty" | "notEmpty"
;

/** Одне значення у фільтрі (для скалярних operator'ів та елемент списку in/nin). */
export interface FilterValue {
  /** Канонічне значення: для ref — UUID, для union-ref — "<typeId>:<uuid>", для enum — code, інакше — текст. */
  raw: string;
  /** Людиночитабельне відображення (для UI: для ref — name, інакше = raw). */
  display: string;
  /**
   * Для union-ref — обраний варіант union'а (typeId). Дублює префікс у {@link raw},
   * але зберігається окремо, щоб UI міг відновити стан picker'а без парсингу.
   */
  typeId?: number;
}

/** Запис фільтра. */
export interface AdvancedFilter {
  uid: string;
  columnId: string;
  op: FilterOp;
  /** Для скалярних op'ів. Не використовується для empty/notEmpty/in/nin. */
  value?: FilterValue;
  /** Для in/nin — список значень (логічне OR всередині списку). */
  values?: FilterValue[];
}

/** Варіант для filterType="enum". */
export interface EnumOption {
  value: string;
  label: string;
}

/**
 * Колонка ReferencePicker'а. Може мати власний filterType (включно з "reference"),
 * що дає рекурсивну глибину: ref-picker може мати ref-колонку, яка відкриє ще
 * один ref-picker.
 */
export interface PickerColumn<I> {
  id: string;
  header: string;
  textOf: (item: I) => string;
  idOf?: (item: I) => string;
  render?: (item: I) => ReactNode;
  width?: string;
  align?: "left" | "right" | "center";
  filterType?: ColumnFilterType;
  enumOptions?: EnumOption[];
  refSource?: ReferenceSource<any>;
}

/**
 * Конфіг для filterType="reference" — описує, звідки брати ref-значення
 * та як їх відображати в picker'і.
 */
export interface ReferenceSource<I = any> {
  /** Завантажити всі значення (типово — API-виклик). */
  fetchAll: () => Promise<I[]>;
  /**
   * Серверна пагінація: picker/autocomplete вантажать лише потрібну сторінку/збіги
   * (фільтри/пошук/сортування транслюються у SQL), а не весь довідник через
   * {@link fetchAll}. Потрібна для великих довідників; інакше — in-memory поверх fetchAll.
   *
   * @param opts  {@code withCount=false} → не рахувати {@code total} (для гортання)
   */
  fetchPage?: (page: number, size: number, query: RowQuery,
               opts?: { withCount?: boolean; afterValue?: string | null; afterId?: string | null }) =>
    Promise<{ content: I[]; total: number }>;
  /** ID запису — те, що зберігається у FilterValue.raw. */
  getId: (item: I) => string;
  /** Display-текст (для FilterValue.display та у списках). */
  getDisplay: (item: I) => string;
  /** Статичні колонки picker'а (будуються один раз). Для колонок, що залежать від
   * {@link useDisplayResolver}, використовуйте {@link buildPickerColumns}. */
  pickerColumns: PickerColumn<I>[];
  /**
   * «Ліниві» колонки, що отримують {@code resolveRef} у момент рендеру picker'а —
   * дозволяє ref-колонкам показувати читабельний текст замість UUID.
   */
  buildPickerColumns?: (ctx: PickerColumnContext) => PickerColumn<I>[];
  /** Заголовок picker-вікна. */
  pickerTitle?: string;
  /** Persist-ключ для збереження конфіга picker'а у localStorage. */
  persistKey?: string;
  /**
   * Маршрут розділу (наприклад, "/users", "/coefficients"), з якого виконується
   * пікінг. Якщо заданий — picker показує кнопку «Відкрити розділ», що відкриває
   * цей розділ у новій вкладці браузера.
   */
  sectionPath?: string;
}

/**
 * Контекст, який передається у {@link ReferenceSource#buildPickerColumns}.
 * Дає доступ до runtime-резолверів (зокрема {@link useDisplayResolver}) — щоб
 * ref-комірки picker'а показували display-текст, а не UUID.
 */
export interface PickerColumnContext {
  /** Резолвить display ссилки; {@code null}, якщо відповідь ще не готова (resolver
   * довантажить її наступним batch'ем і перерендерить picker). */
  resolveRef: (refTypeId: number, id: string) => string | null;
}

/** Мінімальний інтерфейс колонки, потрібний для logіki фільтрування. */
export interface FilterableColumn<T> {
  id: string;
  textOf: (row: T) => string;
  idOf?: (row: T) => string;
  filterType?: ColumnFilterType;
}

export const OP_LABELS: Record<FilterOp, string> = {
  eq:         "=",
  neq:        "≠",
  gt:         ">",
  gte:        "≥",
  lt:         "<",
  lte:        "≤",
  contains:   "contains",
  ncontains:  "does not contain",
  startsWith: "starts with",
  endsWith:   "ends with",
  in:         "in list",
  nin:        "not in list",
  empty:      "empty",
  notEmpty:   "not empty",
};

/** Перелік операторів, які мають сенс для конкретного типу колонки. */
export function opsForType(t: ColumnFilterType | undefined): FilterOp[] {
  switch (t) {
    case "number":
      return ["eq","neq","gt","gte","lt","lte","in","nin","empty","notEmpty"];
    case "date":
      return ["eq","neq","gt","gte","lt","lte","empty","notEmpty"];
    case "boolean":
      return ["eq","empty","notEmpty"];
    case "enum":
      return ["eq","neq","in","nin","empty","notEmpty"];
    case "reference":
    case "unionReference":
      return ["eq","neq","in","nin","empty","notEmpty"];
    case "string":
    default:
      return ["contains","ncontains","eq","neq","startsWith","endsWith",
              "in","nin","empty","notEmpty"];
  }
}

export function defaultOpForType(t: ColumnFilterType | undefined): FilterOp {
  return opsForType(t)[0]!;
}

export function needsValue(op: FilterOp): boolean {
  return op !== "empty" && op !== "notEmpty";
}

export function isMultiValueOp(op: FilterOp): boolean {
  return op === "in" || op === "nin";
}

/**
 * Перевірка, чи рядок проходить фільтр. Логіка залежить від filterType колонки:
 * number/date — типізоване порівняння, reference/enum — за ID/code (idOf),
 * union-ref — за UUID-частиною, string — substring/contains по тексту.
 */
export function applyFilter<T>(
  col: FilterableColumn<T>, row: T, f: AdvancedFilter,
): boolean {
  const text = col.textOf(row) ?? "";
  const valueForCompare = col.idOf ? col.idOf(row) : text;
  const ftype: ColumnFilterType = col.filterType ?? "string";

  if (f.op === "empty")    return !text || text.trim() === "";
  if (f.op === "notEmpty") return !!text && text.trim() !== "";

  if (isMultiValueOp(f.op)) {
    const set = new Set((f.values ?? []).map(v => v.raw.trim()));
    if (set.size === 0) return true;
    const target = valueForCompare.trim();
    if (ftype === "string") {
      const lcSet = new Set(Array.from(set).map(s => s.toLowerCase()));
      const lcTarget = target.toLowerCase();
      return f.op === "in" ? lcSet.has(lcTarget) : !lcSet.has(lcTarget);
    }
    return f.op === "in" ? set.has(target) : !set.has(target);
  }

  const v = f.value?.raw ?? "";
  if (v.trim() === "") return true;

  if (ftype === "number") {
    const num = Number(valueForCompare.replace(",", "."));
    const fnum = Number(v.replace(",", "."));
    if (!Number.isNaN(num) && !Number.isNaN(fnum)) {
      switch (f.op) {
        case "eq":  return num === fnum;
        case "neq": return num !== fnum;
        case "gt":  return num >  fnum;
        case "gte": return num >= fnum;
        case "lt":  return num <  fnum;
        case "lte": return num <= fnum;
      }
    }
  }

  if (ftype === "date") {
    const cd = parseDate(valueForCompare);
    const fd = parseDate(v);
    if (cd && fd) {
      const cn = cd.getTime();
      const fn = fd.getTime();
      switch (f.op) {
        case "eq":  return sameDay(cd, fd);
        case "neq": return !sameDay(cd, fd);
        case "gt":  return cn >  fn;
        case "gte": return cn >= fn;
        case "lt":  return cn <  fn;
        case "lte": return cn <= fn;
      }
    }
  }

  if (ftype === "boolean") {
    const cb = parseBool(valueForCompare);
    const fb = parseBool(v);
    if (f.op === "eq")  return cb === fb;
    if (f.op === "neq") return cb !== fb;
  }

  if (ftype === "reference" || ftype === "enum") {
    if (f.op === "eq")  return valueForCompare === v;
    if (f.op === "neq") return valueForCompare !== v;
  }

  // unionReference: фільтр кодований як "<typeId>:<uuid>". idOf зазвичай дає лише
  // UUID, тож порівнюємо по UUID-частині (тип — додатковий фільтр на сервері).
  if (ftype === "unionReference") {
    const decoded = decodeUnionRef(v);
    const targetId = decoded ? decoded.id : v;
    const rowComposite = decodeUnionRef(valueForCompare);
    const rowId = rowComposite ? rowComposite.id : valueForCompare;
    const rowType = rowComposite ? rowComposite.typeId : null;
    const idMatch = rowId === targetId;
    const typeMatch = !decoded || rowType == null || rowType === decoded.typeId;
    const match = idMatch && typeMatch;
    if (f.op === "eq")  return match;
    if (f.op === "neq") return !match;
  }

  const lc = text.toLowerCase();
  const lcv = v.trim().toLowerCase();
  switch (f.op) {
    case "eq":         return text === v;
    case "neq":        return text !== v;
    case "gt":         return text >  v;
    case "gte":        return text >= v;
    case "lt":         return text <  v;
    case "lte":        return text <= v;
    case "contains":   return lc.includes(lcv);
    case "ncontains":  return !lc.includes(lcv);
    case "startsWith": return lc.startsWith(lcv);
    case "endsWith":   return lc.endsWith(lcv);
  }
  return true;
}

// Date helpers (українські формати)

/** "ДД.ММ.РРРР" або ISO → Date. */
export function parseDate(s: string): Date | null {
  if (!s) return null;
  const t = s.trim();
  const m = t.match(/^(\d{1,2})\.(\d{1,2})\.(\d{4})$/);
  if (m) {
    const d = new Date(Number(m[3]), Number(m[2]) - 1, Number(m[1]));
    return Number.isNaN(d.getTime()) ? null : d;
  }
  const d = new Date(t);
  return Number.isNaN(d.getTime()) ? null : d;
}

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear()
      && a.getMonth()    === b.getMonth()
      && a.getDate()     === b.getDate();
}

function parseBool(s: string): boolean {
  const t = (s ?? "").toLowerCase().trim();
  return t === "true" || t === "1" || t === "yes" || t === "yes" || t === "y";
}

/** ISO "YYYY-MM-DD" → "ДД.ММ.РРРР". */
export function uaFromIso(s: string): string {
  if (!s) return "";
  const m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
  if (!m) return s;
  return `${m[3].padStart(2,"0")}.${m[2].padStart(2,"0")}.${m[1]}`;
}

/** "ДД.ММ.РРРР" → ISO "YYYY-MM-DD" (для input[type=date]). */
export function isoFromUa(s: string): string {
  if (!s) return "";
  const m = s.match(/^(\d{1,2})\.(\d{1,2})\.(\d{4})$/);
  if (!m) return "";
  return `${m[3]}-${m[2].padStart(2,"0")}-${m[1].padStart(2,"0")}`;
}
