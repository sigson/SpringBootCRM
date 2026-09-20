import {
  useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState,
  type ReactNode, type MouseEvent as ReactMouseEvent,
} from "react";
import type {
  AdvancedFilter, ColumnFilterType, EnumOption, ReferenceSource,
} from "./filterTypes";
import { applyFilter } from "./filterTypes";
import { useWindowStack, nextWindowId } from "../windows/WindowStack";
import { FilterDialog, type FilterDialogColumn } from "./FilterDialog";
import { ColumnSettingsDialog } from "./ColumnSettingsDialog";
import { useMetadata } from "../metadata/MetadataProvider";
import { buildReferenceSource } from "./referenceSource";
import {
  type RowSource, type ServerRowSource, type RowQuery, usePagedRows, isPending,
  type MaybeRow, type SortSpec,
} from "./rowSource";
import { useScrollPaging } from "./useScrollPaging";

/* ============================================================================
 * ListView — табличний компонент із:
 *   • per-column quick-filter (substring) з debounce'ом;
 *   • розширеними фільтрами з операторами (=, ≠, >, contains, in, empty …) в
 *     окремому модальному вікні через WindowStack;
 *   • типізованими полями вводу: number, date, boolean, enum, reference;
 *   • рекурсивним ref-picker'ом для оператора «у списку» (modal-over-modal);
 *   • налаштуваннями видимості/порядку/ширини колонок;
 *   • збереженням конфігу в localStorage під `springbootcrm.listconfig.${persistKey}`.
 * ============================================================================ */

// ----------------------------------------------------------------------------
// Public types
// ----------------------------------------------------------------------------

export interface Column<T> {
  id: string;
  header: string;
  textOf: (row: T) => string;
  /**
   * Канонічне значення для порівняння (ID для ref, code для enum).
   * За замовчуванням — textOf.
   */
  idOf?: (row: T) => string;
  render?: (row: T) => ReactNode;
  width?: string;
  sortable?: boolean;
  filterable?: boolean;
  align?: "left" | "right" | "center";
  cellClassName?: string;
  alwaysVisible?: boolean;
  /**
   * Прихована за замовчуванням: не показується при першому відкритті, але доступна
   * через «⚙️ Колонки» та у фільтрах. Збережений користувачем вибір має пріоритет.
   */
  defaultHidden?: boolean;

  /** Тип для типізованого фільтрування. Якщо не задано — fallback за semantic. */
  filterType?: ColumnFilterType;
  /** Для filterType="enum". */
  enumOptions?: EnumOption[];
  /** ReferenceSource для filterType="reference". Якщо не задано, але задано
   * {@link refTypeId} — ListView побудує source автоматично з MetadataProvider. */
  refSource?: ReferenceSource<any>;
  /** TypeId доменного типу: ListView сам збудує {@code refSource} з references-API. */
  refTypeId?: number;
  /** Допустимі типи union-ссилкової колонки. >1 типу → filterType:"unionReference"
   * (у фільтрі зʼявляється вибір типу, потім picker обраного типу). */
  refTypeIds?: number[];

  /** @deprecated Використовуйте filterType. */
  semantic?: "string" | "number";
}

export interface RowAction<T> {
  label: string;
  icon?: string;
  onClick: (row: T) => void;
  visible?: (row: T) => boolean;
  kind?: "default" | "danger" | "primary";
}

/**
 * Синтетична колонка «Дії». Не входить у {@code columns}/visibleColumns, але
 * бере участь у механіці ширин нарівні з рештою: її ширину можна перетягувати
 * (дельта зберігається в {@code columnDeltas} під цим id) і скидається подвійним
 * кліком по межі. {@link ACTIONS_DEFAULT_W} — базова ширина, поверх якої
 * накладається користувацька дельта.
 */
const ACTIONS_COL_ID = "__actions";
const ACTIONS_DEFAULT_W = 220;
/** Мінімальна ширина колонки «Дії» при перетягуванні (щоб кнопки не зникали). */
const ACTIONS_MIN_W = 72;

/** Ширина колонки чекбоксів (multi-select). Не масштабується. */
const SELECT_COL_W = 44;
/** Мінімальна ширина звичайної колонки (px) — нижче неї колонки не стискаються;
 *  далі таблиця виходить за межі області перегляду (горизонтальний скрол). */
const MIN_COL_PX = 64;
/** Базова ширина колонки, для якої ще не виміряно натуральний розмір контенту. */
const DEFAULT_BASE_PX = 140;
/** Межі натуральної (контентної) базової ширини, щоб одна аномально довга
 *  клітинка не «з'їдала» всю таблицю, а порожня — не була мікроскопічною. */
const NATURAL_MIN_PX = 72;
const NATURAL_MAX_PX = 460;

/**
 * Елемент розподілу ширини: {@code base} — базовий («натуральний») розмір
 * колонки, {@code min} — мінімально допустима ширина.
 */
interface FitItem { id: string; base: number; min: number; }

/**
 * Вписує колонки у доступну ширину {@code available}, масштабуючи їхні базові
 * розміри спільним коефіцієнтом {@code k = available / Σbase} (constrained
 * water-filling):
 *
 * <ul>
 *   <li>якщо місця більше за суму базових — усі колонки пропорційно
 *       <b>розтягуються</b>, заповнюючи простір;</li>
 *   <li>якщо менше — пропорційно <b>стискаються</b>; колонки, що впираються у
 *       свій {@code min}, фіксуються на мінімумі, а решта стискається сильніше;</li>
 *   <li>якщо навіть на мінімумах колонки не влазять — таблиця <b>виходить за
 *       межі</b> області (горизонтальний скрол), але масштабування лишається
 *       детермінованим і перераховується при зміні {@code available}.</li>
 * </ul>
 *
 * Повертає <i>цілочисельні</i> базові ширини (без користувацьких дельт). Коли
 * колонки вписуються — сума точно дорівнює {@code available} (залишок округлення
 * додається до найширшої нефіксованої колонки), що гарантує відсутність
 * паразитного горизонтального скролу на 1px.
 */
function distributeWidths(items: FitItem[], available: number): Record<string, number> {
  const out: Record<string, number> = {};
  if (items.length === 0) return out;
  const safeAvail = Math.max(0, available);
  let remaining = safeAvail;
  let active = items.slice();
  const pinned = new Set<string>();

  for (let guard = 0; guard <= items.length; guard++) {
    if (active.length === 0) break;
    const sumBase = active.reduce((s, it) => s + it.base, 0);
    if (sumBase <= 0) {
      const each = remaining / active.length;
      for (const it of active) out[it.id] = Math.max(it.min, each);
      break;
    }
    const k = remaining / sumBase;
    const violators = active.filter(it => k * it.base < it.min);
    if (violators.length === 0) {
      for (const it of active) out[it.id] = k * it.base;
      break;
    }
    for (const it of violators) { out[it.id] = it.min; remaining -= it.min; pinned.add(it.id); }
    active = active.filter(it => !violators.includes(it));
    if (active.length === 0) break;
  }

  // Чи вписалися колонки у доступний простір (інакше — навмисний overflow).
  let floatSum = 0;
  for (const it of items) floatSum += out[it.id] ?? it.base;
  const fits = floatSum <= safeAvail + 0.5;

  for (const it of items) out[it.id] = Math.max(it.min, Math.round(out[it.id] ?? it.base));

  if (fits) {
    let roundSum = 0;
    for (const it of items) roundSum += out[it.id];
    const diff = safeAvail - roundSum;
    if (diff !== 0) {
      const flex = items.filter(it => !pinned.has(it.id)).sort((a, b) => b.base - a.base);
      const target = flex[0] ?? items.slice().sort((a, b) => b.base - a.base)[0];
      if (target) out[target.id] = Math.max(target.min, out[target.id] + diff);
    }
  }
  return out;
}

interface PersistedConfig {
  filters: AdvancedFilter[];
  hiddenColumns: string[];
  columnOrder: string[];
  /**
   * Користувацькі дельти ширини колонок (у px), id → delta. Накладаються поверх
   * базової (відмасштабованої під доступну ширину) ширини колонки:
   * {@code effective = baseScaled + delta}. Зберігаємо саме дельти, а не
   * абсолютні ширини, щоб колонки лишались адаптивними до розміру вікна навіть
   * після ручного коригування користувачем.
   */
  columnDeltas?: Record<string, number>;
}

interface ListViewProps<T> {
  /** Рядки у пам'яті (client-режим). Ігнорується, якщо задано dataSource kind="server". */
  rows?: T[];
  columns: Column<T>[];
  rowId: (row: T) => string;
  rowActions?: RowAction<T>[];
  onRowDoubleClick?: (row: T) => void;
  selectedRowId?: string | null;
  onRowClick?: (row: T) => void;
  toolbar?: ReactNode;
  emptyText?: string;
  loading?: boolean;
  footer?: ReactNode;
  /** Якщо задано — конфіг (фільтри/видимість/порядок) персистентний у localStorage. */
  persistKey?: string;
  /**
   * Debounce для quick-фільтрів і глобального пошуку, мс.
   * За замовчуванням — 1000.
   */
  quickFilterDebounceMs?: number;

  /**
   * Уніфіковане джерело рядків. kind="server" — chunked-завантаження + віртуальний
   * скрол / посторінковий режим; kind="client" — усі рядки у пам'яті, клієнтська
   * фільтрація/сортування.
   */
  dataSource?: RowSource<T>;
  /**
   * Початковий режим відображення для server-джерела: «scroll» (вільна
   * прокрутка з віртуалізацією) або «pages» (справжні сторінки). За замовчуванням
   * «scroll». Користувач може перемкнути через тулбар (якщо {@link enableModeToggle}).
   */
  displayMode?: DisplayMode;
  /** Показувати перемикач режиму відображення (scroll ↔ pages). */
  enableModeToggle?: boolean;
  /** Контрольована поточна сторінка (для синхронізації з routing'ом у list-режимі). */
  page?: number;
  /** Колбек зміни сторінки (для запису в URL). Якщо задано — pages-режим керований. */
  onPageChange?: (page: number) => void;
  /** Висота рядка для віртуалізації (px). За замовчуванням 38. */
  rowHeightPx?: number;

  // --- Picker-режим (той самий модуль + вибір) ---
  /** Увімкнути режим вибору: показує чекбокси/підсвітку та повертає вибір. */
  selectable?: boolean;
  /** Множинний вибір (для in/nin фільтрів). */
  multiSelect?: boolean;
  /** Поточно вибрані id (контрольовано). */
  selectedIds?: Set<string>;
  /** Колбек зміни вибору. {@code row} — рядок, по якому клікнули (доступний для
   *  server-режиму, де власник не тримає повний список у пам'яті). */
  onToggleSelect?: (id: string, row?: T) => void;

  /**
   * Колбек кнопки «↻ Оновити». Якщо заданий — викликається замість внутрішнього
   * reload (дозволяє власнику джерела перечитати дані з сервера, напр. через
   * інкремент version у {@code clientPagedSource}). Якщо не заданий і джерело
   * серверне — використовується внутрішній {@code paged.reload()}.
   */
  onRefresh?: () => void;
}

export type DisplayMode = "scroll" | "pages";

// ----------------------------------------------------------------------------
// Persistence
// ----------------------------------------------------------------------------

function lsKey(k: string) { return `springbootcrm.listconfig.${k}`; }

function loadConfig(k?: string): PersistedConfig & { saved: boolean } {
  if (!k) return { filters: [], hiddenColumns: [], columnOrder: [], columnDeltas: {}, saved: false };
  try {
    const raw = localStorage.getItem(lsKey(k));
    if (!raw) return { filters: [], hiddenColumns: [], columnOrder: [], columnDeltas: {}, saved: false };
    const p = JSON.parse(raw) as Partial<PersistedConfig> & { columnWidths?: unknown };
    // columnDeltas — нова модель. Старі конфіги могли тримати абсолютні
    // columnWidths; їх свідомо ігноруємо (мігруємо до адаптивних базових ширин),
    // бо абсолютні px несумісні з масштабуванням під доступний простір.
    const rawDeltas = (p.columnDeltas && typeof p.columnDeltas === "object")
        ? (p.columnDeltas as Record<string, unknown>) : {};
    const columnDeltas: Record<string, number> = {};
    for (const [id, v] of Object.entries(rawDeltas)) {
      const n = typeof v === "number" ? v : Number(v);
      if (Number.isFinite(n) && n !== 0) columnDeltas[id] = n;
    }
    return {
      filters: Array.isArray(p.filters) ? p.filters : [],
      hiddenColumns: Array.isArray(p.hiddenColumns) ? p.hiddenColumns : [],
      columnOrder: Array.isArray(p.columnOrder) ? p.columnOrder : [],
      columnDeltas,
      saved: true,
    };
  } catch {
    return { filters: [], hiddenColumns: [], columnOrder: [], columnDeltas: {}, saved: false };
  }
}

function saveConfig(k: string | undefined, cfg: PersistedConfig) {
  if (!k) return;
  try { localStorage.setItem(lsKey(k), JSON.stringify(cfg)); }
  catch { /* quota / private mode */ }
}

// ----------------------------------------------------------------------------
// Debounce hook
// ----------------------------------------------------------------------------

function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    if (delayMs <= 0) { setDebounced(value); return; }
    const t = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(t);
  }, [value, delayMs]);
  return debounced;
}

// ----------------------------------------------------------------------------
// semantic → filterType
// ----------------------------------------------------------------------------

function effectiveFilterType<T>(col: Column<T>): ColumnFilterType {
  if (col.filterType) return col.filterType;
  if (col.semantic === "number") return "number";
  return "string";
}

/** type_id ссилкової колонки рядка (для сортування ссилок за дискримінатором). */
function refTypeIdFromColumn<T>(col: Column<T>, row: T): number | null {
  const idv = col.idOf ? (col.idOf(row) ?? "") : "";
  const i = idv.indexOf(":");
  if (i > 0) {
    const n = Number(idv.slice(0, i));
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

/** Порівняння двох рядків за колонкою (client-режим). Дзеркалить applyClientQuery. */
function compareColumnClient<T>(col: Column<T>, a: T, b: T): number {
  const isUnion = (col.refTypeIds?.length ?? 0) > 1;
  const ft: ColumnFilterType = isUnion ? "unionReference" : effectiveFilterType(col);
  if (ft === "reference" || ft === "unionReference") {
    const ta = refTypeIdFromColumn(col, a);
    const tb = refTypeIdFromColumn(col, b);
    if (ta == null && tb == null) return 0;
    if (ta == null) return 1;
    if (tb == null) return -1;
    return ta - tb;
  }
  const sa = col.textOf(a) ?? "";
  const sb = col.textOf(b) ?? "";
  if (ft === "number") {
    const na = Number(sa.replace(",", "."));
    const nb = Number(sb.replace(",", "."));
    if (!Number.isNaN(na) && !Number.isNaN(nb)) return na - nb;
  }
  if (ft === "date") {
    let da = Date.parse(sa); if (Number.isNaN(da)) da = Date.parse(sa.split(".").reverse().join("-"));
    let db = Date.parse(sb); if (Number.isNaN(db)) db = Date.parse(sb.split(".").reverse().join("-"));
    if (!Number.isNaN(da) && !Number.isNaN(db)) return da - db;
  }
  return sa.localeCompare(sb);
}

// ----------------------------------------------------------------------------
// Main component
// ----------------------------------------------------------------------------

export function ListView<T>({
                              rows: rowsProp, columns, rowId, rowActions, onRowDoubleClick, selectedRowId,
                              onRowClick, toolbar, emptyText, loading, footer, persistKey,
                              quickFilterDebounceMs = 1000,
                              dataSource, displayMode = "scroll", enableModeToggle = false,
                              page: pageProp, onPageChange, rowHeightPx = 38,
                              selectable = false, multiSelect = false, selectedIds, onToggleSelect,
                              onRefresh,
                            }: ListViewProps<T>) {
  const { open, closeById } = useWindowStack();
  const { byTypeId } = useMetadata();

  // Визначаємо джерело: явний client-source, server-source, або старий rows-проп.
  const isServer = dataSource?.kind === "server";
  const rows: T[] = useMemo(() => {
    if (dataSource?.kind === "client") return dataSource.rows;
    if (!dataSource) return rowsProp ?? [];
    return [];   // server — рядки беруться через pagedController
  }, [dataSource, rowsProp]);

  // Режим відображення (scroll | pages) — локальний стан із можливістю
  // керування ззовні (page/onPageChange синхронізують pages-режим з URL).
  const [mode, setMode] = useState<DisplayMode>(displayMode);
  const [internalPage, setInternalPage] = useState(0);
  const currentPage = pageProp ?? internalPage;
  const setPage = (p: number) => {
    if (onPageChange) onPageChange(p);
    else setInternalPage(p);
  };

  // ---- Persisted config ----
  const initialCfg = useMemo(() => loadConfig(persistKey), [persistKey]);
  // defaultHidden-колонки приховані, доки користувач сам не збереже власний вибір
  // видимості; збережений конфіг має пріоритет.
  const defaultHiddenIds = useMemo(
      () => columns.filter(c => c.defaultHidden).map(c => c.id),
      [columns],
  );
  const [advancedFilters, setAdvancedFilters] =
      useState<AdvancedFilter[]>(initialCfg.filters);
  const [hiddenColumns, setHiddenColumns] =
      useState<string[]>(initialCfg.saved
          ? initialCfg.hiddenColumns
          : Array.from(new Set([...initialCfg.hiddenColumns, ...defaultHiddenIds])));
  const [columnOrder, setColumnOrder] =
      useState<string[]>(initialCfg.columnOrder);
  // Користувацькі дельти ширини колонок (px), що накладаються поверх базової
  // (відмасштабованої під доступний простір) ширини. Зберігаються в localStorage.
  const [columnDeltas, setColumnDeltas] =
      useState<Record<string, number>>(initialCfg.columnDeltas ?? {});

  // --- Адаптивна модель ширин ---
  // Кожна колонка має «натуральний» (контентний) базовий розмір. Базовий розмір
  // вимірюється один раз у режимі max-content. Під час показу базові розміри
  // масштабуються спільним коефіцієнтом так, щоб вписати колонки в доступну
  // ширину (distributeWidths). Поверх відмасштабованої бази накладається
  // користувацька дельта. При зміні доступної ширини (resize вікна, поява смуги
  // прокрутки) база перераховується, дельти лишаються незмінними.
  const [naturalWidths, setNaturalWidths] = useState<Record<string, number>>({});
  // Тривимірний прохід виміру: під час нього таблиця рендериться у max-content,
  // щоб отримати натуральні ширини; потім перемикаємось у fixed.
  const [measuring, setMeasuring] = useState(false);
  // Поточна доступна ширина області відображення (внутрішня ширина скрол-
  // контейнера). Оновлюється через ResizeObserver і window.resize.
  const [availWidth, setAvailWidth] = useState<number | null>(null);
  const headRowRef = useRef<HTMLTableRowElement | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    saveConfig(persistKey, { filters: advancedFilters, hiddenColumns, columnOrder, columnDeltas });
  }, [persistKey, advancedFilters, hiddenColumns, columnOrder, columnDeltas]);

  // ---- Quick filter (per-column substring) + debounce ----
  // colFilters — те, що видно у input (миттєво).
  // debouncedColFilters — те, що фактично застосовується (через 1с тиші).
  const [colFilters, setColFilters] = useState<Record<string, string>>({});
  const debouncedColFilters = useDebouncedValue(colFilters, quickFilterDebounceMs);

  // ---- Global search + debounce ----
  const [globalSearch, setGlobalSearch] = useState("");
  const debouncedGlobalSearch = useDebouncedValue(globalSearch, quickFilterDebounceMs);
  const [searchVisible, setSearchVisible] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);

  // ---- Sort (багатоколоночне) ----
  // Порядок у масиві = пріоритет. Звичайний клік — одноколоночне сортування
  // (asc → desc → off); Shift-клік додає/перемикає колонку як tie-breaker.
  const [sorts, setSorts] = useState<SortSpec[]>([]);

  // ---- Server query (фільтри/пошук/сортування → бекенд) ----
  // Для server-джерела передаються у fetchPage; для client-джерела ті ж стани
  // застосовуються локально (нижче).
  const serverQuery: RowQuery = useMemo(() => ({
    columnFilters: Object.fromEntries(
        Object.entries(debouncedColFilters).filter(([, v]) => v && v.trim())),
    search: debouncedGlobalSearch.trim() || undefined,
    advanced: advancedFilters.map(f => ({
      columnId: f.columnId, op: f.op,
      value: (f as { value?: unknown }).value,
      values: (f as { values?: unknown[] }).values,
    })),
    sort: sorts[0] ?? null,
    sorts,
  }), [debouncedColFilters, debouncedGlobalSearch, advancedFilters, sorts]);

  // Paged-контролер (hooks безумовні): для не-server передаємо порожнє джерело.
  const emptyServerSource = useRef<ServerRowSource<T>>({
    kind: "server",
    fetchPage: async () => ({ content: [], total: 0 }),
    pageSize: 100,
  }).current;
  const serverSource: ServerRowSource<T> = isServer
      ? (dataSource as ServerRowSource<T>) : emptyServerSource;
  const paged = usePagedRows<T>(serverSource, isServer ? serverQuery : undefined);

  // Віртуальний скрол (активний лише для server + mode="scroll").
  const scroll = useScrollPaging<T>(paged, {
    rowHeight: rowHeightPx,
    enabled: isServer && mode === "scroll",
  });

  // Скидаємо сторінку на 0 при зміні запиту (нові фільтри/пошук/сортування).
  const serverQueryKey = JSON.stringify(isServer ? serverQuery : {});
  useEffect(() => {
    if (isServer && mode === "pages") setPage(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverQueryKey]);

  // SERVER + PAGES: довантаження поточної сторінки в ефекті (не під час рендеру).
  useEffect(() => {
    if (isServer && mode === "pages") {
      const size = paged.pageSize;
      const base = currentPage * size;
      paged.ensureRange(base, base + size - 1);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isServer, mode, currentPage, paged.pageSize, serverQueryKey]);

  // ---- Visible / ordered columns ----
  const orderedColumns = useMemo(() => {
    if (columnOrder.length === 0) return columns;
    const byId = new Map(columns.map(c => [c.id, c]));
    const seen = new Set<string>();
    const out: Column<T>[] = [];
    for (const id of columnOrder) {
      const c = byId.get(id);
      if (c) { out.push(c); seen.add(c.id); }
    }
    for (const c of columns) if (!seen.has(c.id)) out.push(c);
    return out;
  }, [columns, columnOrder]);

  const visibleColumns = useMemo(
      () => orderedColumns.filter(c => !hiddenColumns.includes(c.id)),
      [orderedColumns, hiddenColumns]
  );

  // ---- Ctrl-F → focus search ----
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const isMac = navigator.platform.toLowerCase().includes("mac");
      const cmdKey = isMac ? e.metaKey : e.ctrlKey;
      if (cmdKey && e.key.toLowerCase() === "f") {
        const tgt = e.target as HTMLElement | null;
        if (tgt && (tgt.tagName === "INPUT" || tgt.tagName === "TEXTAREA")) return;
        e.preventDefault();
        setSearchVisible(true);
        setTimeout(() => searchRef.current?.focus(), 10);
      }
      if (e.key === "Escape" && searchVisible) {
        setSearchVisible(false);
        setGlobalSearch("");
      }
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [searchVisible]);

  // ---- Filter, sort, slice → visibleRows ----
  // Використовуємо DEBOUNCED значення quick-фільтрів і пошуку, advanced-фільтри
  // та сортування — миттєво (вони змінюються тільки через модалки/кліки).
  const visibleRows = useMemo(() => {
    let out = rows;

    // Per-column quick filter (substring по textOf, через debounce)
    for (const col of visibleColumns) {
      const f = debouncedColFilters[col.id];
      if (f && f.trim()) {
        const q = f.trim().toLowerCase();
        out = out.filter(r => (col.textOf(r) ?? "").toLowerCase().includes(q));
      }
    }

    // Advanced filters
    if (advancedFilters.length > 0) {
      const byId = new Map(columns.map(c => [c.id, c] as const));
      out = out.filter(r => advancedFilters.every(f => {
        const c = byId.get(f.columnId);
        if (!c) return true;
        const isUnion = (c.refTypeIds?.length ?? 0) > 1;
        return applyFilter({
          id: c.id,
          textOf: c.textOf,
          idOf: c.idOf,
          filterType: isUnion ? "unionReference" : effectiveFilterType(c),
        }, r, f);
      }));
    }

    // Global search (across visible columns, через debounce)
    if (debouncedGlobalSearch.trim()) {
      const q = debouncedGlobalSearch.trim().toLowerCase();
      out = out.filter(r => visibleColumns.some(
          c => (c.textOf(r) ?? "").toLowerCase().includes(q)));
    }

    // Sort — багатоколоночне (client-режим, rows-проп). Ссилкові/union-колонки
    // сортуються за type_id (як і у applyClientQuery для server-style джерел).
    if (sorts.length > 0) {
      const byColId = new Map(columns.map(c => [c.id, c] as const));
      out = [...out].sort((a, b) => {
        for (const s of sorts) {
          const col = byColId.get(s.columnId);
          if (!col) continue;
          const c = compareColumnClient(col, a, b);
          if (c !== 0) return s.dir === "desc" ? -c : c;
        }
        return 0;
      });
    }
    return out;
  }, [rows, debouncedColFilters, visibleColumns, columns, advancedFilters,
    debouncedGlobalSearch, sorts]);

  // Спек сортування колонки (позиція + напрямок).
  function sortInfo(colId: string): { index: number; dir: "asc" | "desc" } | null {
    const i = sorts.findIndex(s => s.columnId === colId);
    return i === -1 ? null : { index: i, dir: sorts[i]!.dir };
  }

  function clickHeader(col: Column<T>, additive: boolean) {
    if (col.sortable === false) return;
    setSorts(prev => {
      const idx = prev.findIndex(s => s.columnId === col.id);
      if (additive) {
        // Shift-клік: керуємо саме цією колонкою, інші лишаємо.
        if (idx === -1) return [...prev, { columnId: col.id, dir: "asc" }];
        const cur = prev[idx]!;
        if (cur.dir === "asc") {
          const next = [...prev]; next[idx] = { ...cur, dir: "desc" }; return next;
        }
        return prev.filter((_, i) => i !== idx);  // desc → прибрати
      }
      // Звичайний клік: одноколоночне сортування з циклом asc → desc → off.
      if (idx === -1 || prev.length > 1) return [{ columnId: col.id, dir: "asc" }];
      const cur = prev[0]!;
      if (cur.dir === "asc") return [{ columnId: col.id, dir: "desc" }];
      return [];
    });
  }

  // ==========================================================================
  // Адаптивна модель ширин колонок
  //
  // 1) «Базовий» розмір колонки = її натуральна (контентна) ширина. Для колонок
  //    із заданою width беремо її; для решти — виміряну у max-content (clamp до
  //    [NATURAL_MIN_PX..NATURAL_MAX_PX]).
  // 2) «Базовий коефіцієнт»: distributeWidths масштабує всі базові розміри так,
  //    щоб сумарно вписати їх у доступну ширину (availWidth − колонка чекбоксів).
  //    Якщо місця більше — колонки розтягуються; менше — стискаються; зовсім
  //    мало (нижче min) — таблиця виходить за межі (горизонтальний скрол).
  // 3) Поверх відмасштабованої бази накладається користувацька дельта:
  //    effective = baseScaled + delta. Збільшення колонки розширює таблицю й
  //    зсуває сусідів праворуч (зʼявляється горизонтальний скрол); зменшення —
  //    ліворуч. Сусіди зберігають свій базовий (відмасштабований) розмір.
  // При зміні availWidth (resize вікна, поява смуги прокрутки) база
  // перераховується, дельти лишаються незмінними.
  // ==========================================================================

  /** Чи всі видимі колонки мають відому базу (задану width або виміряну). */
  const allNaturalsKnown = useMemo(
      () => visibleColumns.every(c => !!c.width || naturalWidths[c.id] != null),
      [visibleColumns, naturalWidths]
  );

  /** Чи можна застосовувати fixed-layout з обчисленими ширинами. */
  const widthsReady = !measuring && allNaturalsKnown && availWidth != null;

  /** Базовий («натуральний») розмір колонки у px. */
  const baseOf = (col: Column<T>): number => {
    const explicit = col.width ? parseInt(col.width, 10) : NaN;
    if (Number.isFinite(explicit)) return explicit;
    const m = naturalWidths[col.id];
    return m != null ? m : DEFAULT_BASE_PX;
  };

  /**
   * Відмасштабовані базові ширини (без користувацьких дельт): результат вписування
   * базових розмірів у доступний простір. Це і є «базовий коефіцієнт» у дії.
   */
  const baseScaledWidths = useMemo<Record<string, number>>(() => {
    if (availWidth == null) return {};
    const selW = selectable && multiSelect ? SELECT_COL_W : 0;
    const items: FitItem[] = visibleColumns.map(c => ({
      id: c.id, base: baseOf(c), min: MIN_COL_PX,
    }));
    if (rowActions && rowActions.length > 0) {
      items.push({ id: ACTIONS_COL_ID, base: ACTIONS_DEFAULT_W, min: ACTIONS_MIN_W });
    }
    return distributeWidths(items, availWidth - selW);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [availWidth, visibleColumns, naturalWidths, selectable, multiSelect, rowActions]);

  /** Ефективні ширини: відмасштабована база + користувацька дельта (clamp до min). */
  const effectiveWidths = useMemo<Record<string, number>>(() => {
    const out: Record<string, number> = {};
    for (const id of Object.keys(baseScaledWidths)) {
      const min = id === ACTIONS_COL_ID ? ACTIONS_MIN_W : MIN_COL_PX;
      const d = columnDeltas[id] ?? 0;
      out[id] = Math.max(min, Math.round(baseScaledWidths[id]! + d));
    }
    return out;
  }, [baseScaledWidths, columnDeltas]);

  // Перетягування правої межі заголовка — змінює користувацьку ДЕЛЬТУ колонки.
  // delta = (бажана ефективна ширина) − (відмасштабована база). Сусіди зберігають
  // свою базу й зсуваються, бо ширина таблиці = сумі ефективних ширин.
  function startResize(e: ReactMouseEvent, col: Column<T>) {
    startResizeById(e, col.id);
  }

  /**
   * Базова логіка resize за id колонки (звичайні колонки + синтетична «Дії»).
   * @param minW мінімальна ширина у px.
   */
  function startResizeById(e: ReactMouseEvent, colId: string, minW = MIN_COL_PX) {
    e.preventDefault();
    e.stopPropagation();
    const th = (e.currentTarget as HTMLElement).closest("th") as HTMLElement | null;
    const startX = e.clientX;
    const startW = th
        ? th.getBoundingClientRect().width
        : (effectiveWidths[colId] ?? DEFAULT_BASE_PX);
    // Відмасштабована база фіксується на момент початку перетягування: дельта
    // рахується відносно неї, тож при наступних resize колонка збереже «надбавку».
    const base = baseScaledWidths[colId] ?? startW;
    const onMove = (ev: MouseEvent) => {
      const wantEff = Math.max(minW, Math.round(startW + (ev.clientX - startX)));
      const delta = Math.round(wantEff - base);
      setColumnDeltas(prev => {
        if ((prev[colId] ?? 0) === delta) return prev;
        return { ...prev, [colId]: delta };
      });
    };
    const onUp = () => {
      document.removeEventListener("mousemove", onMove);
      document.removeEventListener("mouseup", onUp);
      document.body.style.cursor = "";
    };
    document.addEventListener("mousemove", onMove);
    document.addEventListener("mouseup", onUp);
    document.body.style.cursor = "col-resize";
  }

  /** Скинути користувацьку дельту однієї колонки (подвійний клік по межі). */
  function resetColumnWidth(colId: string) {
    setColumnDeltas(prev => {
      if (!(colId in prev)) return prev;
      const n = { ...prev }; delete n[colId]; return n;
    });
  }

  /** Скинути ВСІ користувацькі дельти ширин — колонки перемасштабуються під
   *  доступний простір згідно з базовими розмірами. */
  function resetAllColumnWidths() {
    setColumnDeltas({});
  }

  /** Стабільний ref-колбек для контейнера прокрутки таблиці. Один і той самий
   *  DOM-вузол слугує і для виміру доступної ширини (ResizeObserver на wrapRef),
   *  і для віртуального скролу (scroll.scrollRef). Робимо його стабільним через
   *  useCallback, аби React 18 не перевстановлював (null→node) ref на кожен
   *  рендер — інакше ResizeObserver відписувався б/перепідписувався зайвий раз. */
  const setWrapEl = useCallback((el: HTMLDivElement | null) => {
    wrapRef.current = el;
    // Той самий DOM-вузол використовує і віртуальний скрол (коли активний).
    scroll.scrollRef.current = el;
  }, [scroll.scrollRef]);

  /** Поточна ефективна ширина колонки «Дії» (px). */
  const actionsWidthPx = effectiveWidths[ACTIONS_COL_ID] ?? ACTIONS_DEFAULT_W;

  /** Поточна ефективна ширина колонки (px-рядок) для fixed-layout. */
  const widthOf = (col: Column<T>): string | undefined => {
    const w = effectiveWidths[col.id];
    return w != null ? `${w}px` : undefined;
  };

  // Вимір натуральних (контентних) ширин. Виконуємо у режимі max-content (без
  // fixed-layout), де колонки самі тиснуться під вміст; знятий розмір clamp'имо
  // у [NATURAL_MIN_PX..NATURAL_MAX_PX]. Міряємо лише колонки без заданої width і
  // без уже відомого натурального розміру.
  useLayoutEffect(() => {
    const head = headRowRef.current;
    if (!head) return;
    if (allNaturalsKnown) {
      if (measuring) setMeasuring(false);
      return;
    }
    if (!measuring) { setMeasuring(true); return; }  // увійти в режим виміру
    // measuring === true: таблиця вже у max-content — знімаємо ширини.
    const ths = head.querySelectorAll<HTMLTableCellElement>("th[data-col-id]");
    const found: Record<string, number> = {};
    ths.forEach(th => {
      const id = th.getAttribute("data-col-id");
      if (!id || naturalWidths[id] != null) return;
      const raw = Math.ceil(th.getBoundingClientRect().width);
      found[id] = Math.max(NATURAL_MIN_PX, Math.min(NATURAL_MAX_PX, raw));
    });
    if (Object.keys(found).length > 0) {
      setNaturalWidths(prev => ({ ...prev, ...found }));
    }
    setMeasuring(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visibleColumns, naturalWidths, measuring, allNaturalsKnown]);

  // Відстеження доступної ширини області відображення (внутрішня ширина скрол-
  // контейнера). ResizeObserver реагує і на зміну розміру вікна, і на появу/
  // зникнення вертикальної смуги прокрутки (scrollbar-gutter тримає її стабільною).
  useLayoutEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const update = () => {
      const w = el.clientWidth;
      setAvailWidth(prev => (prev === w ? prev : w));
    };
    update();
    let ro: ResizeObserver | null = null;
    if (typeof ResizeObserver !== "undefined") {
      ro = new ResizeObserver(update);
      ro.observe(el);
    }
    window.addEventListener("resize", update);
    return () => {
      ro?.disconnect();
      window.removeEventListener("resize", update);
    };
  }, []);

  /** Сумарна ширина таблиці (px) у fixed-режимі = сума ефективних ширин колонок.
   *  Коли дельт немає — точно дорівнює availWidth (немає паразитного скролу);
   *  коли користувач розширив колонки — більша за availWidth (горизонтальний скрол). */
  const tableTotalWidth = useMemo<number | undefined>(() => {
    if (!widthsReady) return undefined;
    let sum = selectable && multiSelect ? SELECT_COL_W : 0;
    for (const col of visibleColumns) sum += effectiveWidths[col.id] ?? baseOf(col);
    if (rowActions && rowActions.length > 0) sum += actionsWidthPx;
    return sum;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [widthsReady, visibleColumns, effectiveWidths, selectable, multiSelect, rowActions, actionsWidthPx]);

  // Кнопка «↻ Оновити»: зовнішній колбек (перечитати з сервера) або внутрішній reload.
  function doRefresh() {
    if (onRefresh) onRefresh();
    else if (isServer) paged.reload();
  }

  // ---- Open dialogs via WindowStack (modal-over-table) ----
  function openFilterDialog() {
    const winId = nextWindowId();
    const filterCols: FilterDialogColumn[] = columns.map(c => {
      const isUnion = (c.refTypeIds?.length ?? 0) > 1;
      return {
        id: c.id,
        header: c.header,
        filterType: isUnion ? "unionReference" : effectiveFilterType(c),
        enumOptions: c.enumOptions,
        // reference-колонка без явного refSource: будуємо його з refTypeId через
        // спільний buildReferenceSource (єдине джерело правди з inline-picker'ом).
        refSource: c.refSource ?? (
            !isUnion && c.refTypeId != null
                ? (buildReferenceSource(c.refTypeId, byTypeId) ?? undefined)
                : undefined
        ),
        refTypeIds: c.refTypeIds,
      };
    });
    open({
      id: winId,
      title: "Filter settings",
      width: "wide",
      content: (
          <FilterDialog
              columns={filterCols}
              initialFilters={advancedFilters}
              onConfirm={(filters) => {
                setAdvancedFilters(filters);
                closeById(winId);
              }}
              onCancel={() => closeById(winId)}
          />
      ),
    });
  }

  function openColumnSettings() {
    const winId = nextWindowId();
    open({
      id: winId,
      title: "Column settings",
      width: "default",
      content: (
          <ColumnSettingsDialog
              columns={columns.map(c => ({
                id: c.id, header: c.header, alwaysVisible: c.alwaysVisible,
              }))}
              initialHidden={hiddenColumns}
              initialOrder={columnOrder.length === 0 ? columns.map(c => c.id) : columnOrder}
              onConfirm={(hidden, order) => {
                setHiddenColumns(hidden);
                setColumnOrder(order);
                closeById(winId);
              }}
              onCancel={() => closeById(winId)}
              hasWidthOverrides={Object.keys(columnDeltas).length > 0}
              onResetWidths={resetAllColumnWidths}
          />
      ),
    });
  }

  // ---- Pending state (debounce in flight) ----
  const isQuickDebouncing = useMemo(
      () => visibleColumns.some(c =>
          (colFilters[c.id] ?? "") !== (debouncedColFilters[c.id] ?? "")),
      [visibleColumns, colFilters, debouncedColFilters]
  );
  const isSearchDebouncing = globalSearch !== debouncedGlobalSearch;
  const isDebouncing = isQuickDebouncing || isSearchDebouncing;

  // ---- Status flags ----
  const hasQuickFilters = Object.values(debouncedColFilters).some(v => v.trim().length > 0);
  const hasGlobalSearch = debouncedGlobalSearch.trim().length > 0;
  const hasAdvanced = advancedFilters.length > 0;
  const hasAnyFilter = hasQuickFilters || hasGlobalSearch || hasAdvanced;
  const hasColSettings = hiddenColumns.length > 0 || columnOrder.length > 0;

  const colCount = visibleColumns.length
      + (selectable && multiSelect ? 1 : 0)
      + (rowActions && rowActions.length > 0 ? 1 : 0);

  // Рендер однієї data-row (спільний для client / server / scroll / pages).
  function renderRow(row: T) {
    const id = rowId(row);
    const isSel = selectable
        ? (selectedIds?.has(id) ?? false)
        : selectedRowId === id;
    return (
        <tr key={id}
            className={isSel ? "data-table__row--selected" : ""}
            style={{ height: rowHeightPx, cursor: onRowClick || onRowDoubleClick || selectable ? "pointer" : undefined }}
            onClick={() => { if (selectable) onToggleSelect?.(id, row); onRowClick?.(row); }}
            onDoubleClick={() => onRowDoubleClick?.(row)}>
          {selectable && multiSelect && (
              <td style={{ width: 44, textAlign: "center" }}>
                <input type="checkbox" readOnly checked={selectedIds?.has(id) ?? false}
                       onClick={e => e.stopPropagation()} />
              </td>
          )}
          {visibleColumns.map(col => (
              <td key={col.id} className={col.cellClassName}
                  style={{ textAlign: col.align ?? "left" }}>
                {col.render ? col.render(row) : col.textOf(row)}
              </td>
          ))}
          {rowActions && rowActions.length > 0 && (
              <td style={{ textAlign: "right" }}>
                <div className="row-actions">
                  {rowActions.filter(a => !a.visible || a.visible(row)).map((a, i) => (
                      <button key={i}
                              className={`btn btn--small ${
                                  a.kind === "danger" ? "btn--danger" :
                                      a.kind === "primary" ? "btn--primary" : ""}`}
                              onClick={e => { e.stopPropagation(); a.onClick(row); }}
                              title={a.label}>
                        {a.icon && <span>{a.icon}</span>}{a.label}
                      </button>
                  ))}
                </div>
              </td>
          )}
        </tr>
    );
  }

  // Shimmer-плейсхолдер для ще не завантаженого рядка.
  function renderPendingRow(key: string) {
    return (
        <tr key={key} style={{ height: rowHeightPx }} className="data-table__row--pending">
          {selectable && multiSelect && <td className="lv-shimmer-cell"><span className="lv-shimmer" /></td>}
          {visibleColumns.map(col => (
              <td key={col.id} className="lv-shimmer-cell"><span className="lv-shimmer" /></td>
          ))}
          {rowActions && rowActions.length > 0 && <td className="lv-shimmer-cell"><span className="lv-shimmer" /></td>}
        </tr>
    );
  }

  return (
      <div className="listview">
        <div className="listview__toolbar">
          <div className="listview__toolbar-left">{toolbar}</div>
          <div className="listview__toolbar-right">
            {/* Перемикач режиму відображення (server-джерело): вільна прокрутка ↔ сторінки */}
            {isServer && enableModeToggle && (
                <div className="lv-mode-toggle" role="group" aria-label="Display mode">
                  <button
                      className={`btn btn--small ${mode === "scroll" ? "btn--primary" : ""}`}
                      onClick={() => setMode("scroll")}
                      title="Free scrolling (virtual scroll)">↕ Scrolling</button>
                  <button
                      className={`btn btn--small ${mode === "pages" ? "btn--primary" : ""}`}
                      onClick={() => setMode("pages")}
                      title="Paged view">▤ Pages</button>
                </div>
            )}

            {/* Фільтри/Колонки/Пошук — доступні в обох режимах. Для server-джерела
              вони транслюються у серверний запит (fetchPage); для client —
              застосовуються локально. */}
            <button
                className={`btn btn--small ${hasAdvanced ? "btn--primary" : ""}`}
                onClick={openFilterDialog}
                title="Configure filters with operators"
            >🎚️ Filters{hasAdvanced ? ` (${advancedFilters.length})` : ""}</button>
            <button
                className={`btn btn--small ${hasColSettings ? "btn--primary" : ""}`}
                onClick={openColumnSettings}
                title="Configure column visibility and order"
            >⚙️ Columns</button>

            {/* «↻ Оновити» — опитати джерело й оновити поточний зріз
              (видиму сторінку у pages-режимі або вікно прокрутки). */}
            {(isServer || onRefresh) && (
                <button className="btn btn--small"
                        onClick={doRefresh}
                        disabled={isServer && paged.loading}
                        title="Reload data from the server">
                  {isServer && paged.loading ? "Refreshing…" : "↻ Refresh"}
                </button>
            )}

            {searchVisible || globalSearch ? (
                <div className="listview__search-wrap">
                  <input ref={searchRef} className="listview__search"
                         value={globalSearch}
                         onChange={e => setGlobalSearch(e.target.value)}
                         placeholder="Search across all columns…"
                         onBlur={() => { if (!globalSearch) setSearchVisible(false); }} />
                  <button className="icon-btn icon-btn--small"
                          onClick={() => { setSearchVisible(false); setGlobalSearch(""); }}
                          title="Close search (Esc)">✕</button>
                </div>
            ) : (
                <button className="btn btn--small"
                        onClick={() => {
                          setSearchVisible(true);
                          setTimeout(() => searchRef.current?.focus(), 10);
                        }}
                        title="Search (Ctrl-F)">🔍 Find</button>
            )}

            {hasAnyFilter && (
                <button className="btn btn--small btn--danger"
                        onClick={() => {
                          setColFilters({});
                          setGlobalSearch("");
                          setAdvancedFilters([]);
                        }}
                        title="Reset all filters">✕ Clear</button>
            )}
          </div>
        </div>

        <div className={`listview__table-wrap ${isServer && mode === "scroll" ? "listview__table-wrap--virtual" : ""}`}
             ref={setWrapEl}
            // tabIndex — щоб контейнер міг приймати клавіші (PageUp/PageDown/
            // стрілки/Home/End) у режимі віртуальної прокрутки.
             tabIndex={isServer && mode === "scroll" ? 0 : undefined}
             onScroll={isServer && mode === "scroll" ? scroll.onScroll : undefined}
             onKeyDown={isServer && mode === "scroll" ? scroll.onKeyDown : undefined}>
          <table className={`data-table data-table--1c${widthsReady ? " data-table--fixed" : ""}`}
                 style={
                   measuring
                       // Прохід виміру: max-content, щоб зняти натуральні ширини колонок.
                       ? { tableLayout: "auto", width: "max-content" }
                       : (widthsReady && tableTotalWidth ? { width: tableTotalWidth } : undefined)
                 }>
            <thead>
            <tr ref={headRowRef}>
              {selectable && multiSelect && <th style={{ width: SELECT_COL_W }}>✓</th>}
              {visibleColumns.map(col => {
                const si = sortInfo(col.id);
                return (
                    <th key={col.id}
                        data-col-id={col.id}
                        style={{ width: measuring ? undefined : widthOf(col), textAlign: col.align ?? "left" }}
                        onClick={(e) => clickHeader(col, e.shiftKey)}
                        className={col.sortable === false ? "" : "data-table__th--sortable"}
                        title={col.sortable === false ? undefined
                            : "Click to sort (A-Z ▲ / Z-A ▼); Shift+click to add the column to the sort"}>
                    <span className="data-table__th-content">
                      {col.header}
                      {si && (
                          <span className="data-table__sort-mark">
                          {si.dir === "asc" ? "▲" : "▼"}
                            {sorts.length > 1 && <sub style={{ fontSize: 8 }}>{si.index + 1}</sub>}
                        </span>
                      )}
                    </span>
                      {/* Resizer — на кожній колонці (зокрема несортованих/останніх). */}
                      <span
                          className="data-table__resizer"
                          onMouseDown={(e) => startResize(e, col)}
                          onClick={(e) => e.stopPropagation()}
                          onDoubleClick={(e) => {
                            e.stopPropagation();
                            // Подвійний клік по межі — скинути користувацьку дельту
                            // (повертаємось до відмасштабованої базової ширини).
                            resetColumnWidth(col.id);
                          }}
                          title="Drag to resize (double-click for auto)"
                      />
                    </th>
                );
              })}
              {rowActions && rowActions.length > 0 && (
                  <th style={{ width: measuring ? undefined : actionsWidthPx, textAlign: "right" }}>
                    <span className="data-table__th-content">Actions</span>
                    {/* Resizer колонки «Дії» — як у звичайних колонок. */}
                    <span
                        className="data-table__resizer"
                        onMouseDown={(e) => startResizeById(e, ACTIONS_COL_ID, ACTIONS_MIN_W)}
                        onClick={(e) => e.stopPropagation()}
                        onDoubleClick={(e) => {
                          e.stopPropagation();
                          resetColumnWidth(ACTIONS_COL_ID);
                        }}
                        title="Drag to resize (double-click for auto)"
                    />
                  </th>
              )}
            </tr>
            {/* Рядок quick-фільтрів (для server-джерела транслюється у запит).
                Під час проходу виміру (max-content) НЕ рендеримо — інакше input'и
                із width:100% роздули б натуральні ширини колонок. */}
            {!measuring && (
                <tr className="data-table__filter-row">
                  {selectable && multiSelect && <th style={{ width: SELECT_COL_W }}></th>}
                  {visibleColumns.map(col => {
                    const pending = (colFilters[col.id] ?? "") !== (debouncedColFilters[col.id] ?? "");
                    return (
                        <th key={col.id + "-filter"} style={{ width: widthOf(col) }}>
                          {col.filterable === false ? null : (
                              <div className={`data-table__col-filter-wrap ${pending ? "is-pending" : ""}`}>
                                <input
                                    className="data-table__col-filter"
                                    value={colFilters[col.id] ?? ""}
                                    onChange={e =>
                                        setColFilters({ ...colFilters, [col.id]: e.target.value })}
                                    placeholder="Quick filter…"
                                    title={pending
                                        ? "Waiting for input to finish (1s since the last press)…"
                                        : "Substring-filter by display-the column value"}
                                />
                                {pending && (
                                    <span className="data-table__col-filter-spinner" aria-hidden>⋯</span>
                                )}
                              </div>
                          )}
                        </th>
                    );
                  })}
                  {rowActions && rowActions.length > 0 && <th style={{ width: actionsWidthPx }}></th>}
                </tr>
            )}
            </thead>
            <tbody>
            {(() => {
              // ---- SERVER + SCROLL: віртуальний скрол зі spacer'ами ----
              if (isServer && mode === "scroll") {
                if (paged.error) {
                  return (
                      <tr><td colSpan={colCount} className="data-table__msg">
                        Loading error: {paged.error}
                      </td></tr>
                  );
                }
                const total = scroll.total;
                if (total === 0 && !paged.loading) {
                  return (
                      <tr><td colSpan={colCount} className="data-table__msg">
                        {emptyText ?? "No records"}
                      </td></tr>
                  );
                }
                const w = scroll.window;
                const rowsOut: ReactNode[] = [];
                if (w.topSpacer > 0) {
                  rowsOut.push(
                      <tr key="__top" style={{ height: w.topSpacer }} aria-hidden>
                        <td colSpan={colCount} style={{ padding: 0, border: "none" }} />
                      </tr>);
                }
                // Захист від дубль-ключів у видимому вікні. У нормі сторінки не
                // перекриваються (offset і keyset тепер сортують однаково — див.
                // SqlRowFilter), але якщо через перехідний стан кешу один і той
                // самий id потрапив у дві позиції, рендеримо повтор як плейсхолдер:
                // React не отримує дублюючий key (без «дребезжання» й зникнення
                // рядків), а геометрія вікна зберігається.
                const seenIds = new Set<string>();
                for (let i = w.startIndex; i <= w.endIndex; i++) {
                  const r: MaybeRow<T> = paged.rowAt(i);
                  if (isPending(r)) {
                    rowsOut.push(renderPendingRow("__p" + i));
                    continue;
                  }
                  const id = rowId(r as T);
                  if (seenIds.has(id)) {
                    rowsOut.push(renderPendingRow("__dup" + i));
                    continue;
                  }
                  seenIds.add(id);
                  rowsOut.push(renderRow(r as T));
                }
                if (w.bottomSpacer > 0) {
                  rowsOut.push(
                      <tr key="__bot" style={{ height: w.bottomSpacer }} aria-hidden>
                        <td colSpan={colCount} style={{ padding: 0, border: "none" }} />
                      </tr>);
                }
                return rowsOut;
              }

              // ---- SERVER + PAGES: рендеримо лише поточну сторінку ----
              if (isServer && mode === "pages") {
                if (paged.error) {
                  return (
                      <tr><td colSpan={colCount} className="data-table__msg">
                        Loading error: {paged.error}
                      </td></tr>
                  );
                }
                const size = paged.pageSize;
                const base = currentPage * size;
                if (!paged.isPageLoaded(currentPage)) {
                  // Завантаження ініціюється ефектом; тут лише shimmer.
                  return Array.from({ length: Math.min(size, 12) },
                      (_, i) => renderPendingRow("__pp" + i));
                }
                const out: ReactNode[] = [];
                for (let i = base; i < base + size && i < (scroll.total); i++) {
                  const r = paged.rowAt(i);
                  if (!isPending(r)) out.push(renderRow(r as T));
                }
                if (out.length === 0) {
                  return (
                      <tr><td colSpan={colCount} className="data-table__msg">
                        {emptyText ?? "No records"}
                      </td></tr>
                  );
                }
                return out;
              }

              // ---- CLIENT: повна клієнтська фільтрація ----
              if (loading && visibleRows.length === 0) {
                return (
                    <tr><td colSpan={colCount} className="data-table__msg">Loading…</td></tr>
                );
              }
              if (visibleRows.length === 0) {
                return (
                    <tr><td colSpan={colCount} className="data-table__msg">
                      {hasAnyFilter
                          ? "Nothing found for the given filters"
                          : (emptyText ?? "No records")}
                    </td></tr>
                );
              }
              return visibleRows.map(row => renderRow(row));
            })()}
            </tbody>
          </table>
        </div>

        {/* Pagination controls — лише server+pages */}
        {isServer && mode === "pages" && scroll.total > 0 && (
            <PaginationControls
                page={currentPage}
                pageSize={paged.pageSize}
                total={scroll.total}
                onPage={setPage}
            />
        )}

        {footer && <div className="listview__footer">{footer}</div>}

        <div className="listview__status muted">
          {isServer ? (
              <>Total: {scroll.total ?? "…"}
                {paged.loading && <> · <span className="lv-debouncing">loading…</span></>}
                {" "}· mode: {mode === "scroll" ? "scroll" : `page ${currentPage + 1}`}</>
          ) : (
              <>Total: {rows.length}
                {hasAnyFilter && visibleRows.length !== rows.length && (
                    <> · Found: {visibleRows.length}</>
                )}
                {hiddenColumns.length > 0 && (
                    <> · Columns hidden: {hiddenColumns.length}</>
                )}
                {isDebouncing && (
                    <> · <span className="lv-debouncing">⏳ Waiting for input to finish…</span></>
                )}
                {" "}· <span style={{ fontSize: 11 }}>Ctrl-F — search</span>
              </>
          )}
        </div>
      </div>
  );
}

// Re-export типів для зворотної сумісності існуючих імпортів.
export type {
  AdvancedFilter, FilterOp, ColumnFilterType,
  ReferenceSource, PickerColumn, EnumOption, FilterValue,
} from "./filterTypes";

// ----------------------------------------------------------------------------
// Pagination controls (server + pages-режим)
// ----------------------------------------------------------------------------

function PaginationControls({ page, pageSize, total, onPage }: {
  page: number; pageSize: number; total: number; onPage: (p: number) => void;
}) {
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const cur = Math.min(page, pageCount - 1);
  const go = (p: number) => onPage(Math.max(0, Math.min(pageCount - 1, p)));

  // Вікно номерів сторінок навколо поточної.
  const windowSize = 5;
  let start = Math.max(0, cur - Math.floor(windowSize / 2));
  const end = Math.min(pageCount - 1, start + windowSize - 1);
  start = Math.max(0, end - windowSize + 1);
  const nums: number[] = [];
  for (let i = start; i <= end; i++) nums.push(i);

  return (
      <div className="lv-pagination">
        <button className="btn btn--small" disabled={cur === 0} onClick={() => go(0)} title="First">«</button>
        <button className="btn btn--small" disabled={cur === 0} onClick={() => go(cur - 1)} title="Previous">‹</button>
        {start > 0 && <span className="lv-pagination__ellipsis">…</span>}
        {nums.map(n => (
            <button key={n}
                    className={`btn btn--small ${n === cur ? "btn--primary" : ""}`}
                    onClick={() => go(n)}>{n + 1}</button>
        ))}
        {end < pageCount - 1 && <span className="lv-pagination__ellipsis">…</span>}
        <button className="btn btn--small" disabled={cur >= pageCount - 1} onClick={() => go(cur + 1)} title="Next">›</button>
        <button className="btn btn--small" disabled={cur >= pageCount - 1} onClick={() => go(pageCount - 1)} title="Last">»</button>
        <span className="muted lv-pagination__goto">
        Page{" "}
          <PageInput cur={cur} pageCount={pageCount} onGo={go} />
          {" "}of {pageCount}
      </span>
      </div>
  );
}

/**
 * Текстовий контрол поточної сторінки. Користувач може вписати номер рукою; після
 * завершення редагування (Enter або втрата фокусу) viewer переходить на введену
 * сторінку. Якщо введене значення не проходить валідацію (не число або поза
 * діапазоном 1..pageCount) — поле повертає поточний номер сторінки.
 */
function PageInput({ cur, pageCount, onGo }: {
  cur: number; pageCount: number; onGo: (pageIndex: number) => void;
}) {
  // 1-based рядок, що редагується. Синхронізується з поточною сторінкою, доки
  // поле не у фокусі (зовнішня навігація кнопками оновлює відображення).
  const [text, setText] = useState(String(cur + 1));
  const [editing, setEditing] = useState(false);
  useEffect(() => {
    if (!editing) setText(String(cur + 1));
  }, [cur, editing]);

  /** Валідація + перехід або відкат до поточної сторінки. */
  function commit() {
    setEditing(false);
    const n = Number(text.trim());
    if (Number.isInteger(n) && n >= 1 && n <= pageCount) {
      const target = n - 1;
      if (target !== cur) onGo(target);
      else setText(String(cur + 1));
    } else {
      // Валідація не пройшла — повертаємо поточне значення.
      setText(String(cur + 1));
    }
  }

  return (
      <input
          className="lv-pagination__page-input"
          type="text"
          inputMode="numeric"
          value={text}
          aria-label="Page number"
          title={`Enter a page number (1..${pageCount}) and press Enter`}
          onFocus={(e) => { setEditing(true); e.currentTarget.select(); }}
          onChange={(e) => setText(e.target.value)}
          onBlur={commit}
          onKeyDown={(e) => {
            if (e.key === "Enter") { e.preventDefault(); (e.target as HTMLInputElement).blur(); }
            else if (e.key === "Escape") {
              e.preventDefault();
              setText(String(cur + 1));
              setEditing(false);
              (e.target as HTMLInputElement).blur();
            }
          }}
      />
  );
}

// ----------------------------------------------------------------------------
// Auto-build ReferenceSource з метаданих (для filterType:"reference" + refTypeId)
// ----------------------------------------------------------------------------
// Логіку винесено у спільний `./referenceSource#buildReferenceSource`, який
// використовують і inline-picker (`useReferenceSource`), і filter-picker нижче.
// Це прибирає дві колишні копії диспатчу, що встигли розійтися (align колонки
// «Код» + відсутність серверної пагінації у filter-picker'і).