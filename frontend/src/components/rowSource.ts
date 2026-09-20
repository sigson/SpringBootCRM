import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Уніфіковане джерело рядків для {@link ListView} (list- і picker-режими). Два
 * сценарії: <b>client</b> — усі рядки в пам'яті, фільтрація/сортування клієнтські;
 * <b>server</b> — рядки вантажаться чанками ({@code fetchPage}), відомий лише {@code total}.
 */
/** Параметри запиту сторінки для server-джерела: фільтри/пошук/сортування, які
 * застосовує бекенд (співіснують з chunked-завантаженням). */
export interface RowQuery {
  /** Підрядкові фільтри по колонках: { columnId: substring }. */
  columnFilters?: Record<string, string>;
  /** Глобальний пошук (підрядок по всіх колонках). */
  search?: string;
  /** Розширені фільтри (серіалізовані; бекенд інтерпретує за columnId+op). */
  advanced?: { columnId: string; op: string; value?: unknown; values?: unknown[] }[];
  /** Одноколоночне сортування — для ендпоінтів {@code /page}, що приймають один
   * {@code sortBy}/{@code sortDir}. Якщо задано {@link sorts}, дублює його перший елемент. */
  sort?: SortSpec | null;
  /** Багатоколоночне сортування: порядок у масиві = пріоритет (перший — головний
   * ключ). Ссилкові/union-колонки сортуються за {@code type_id}, не за UUID. */
  sorts?: SortSpec[];
}

export interface SortSpec { columnId: string; dir: "asc" | "desc"; }

export interface ServerRowSource<T> {
  kind: "server";
  /**
   * Завантажити сторінку (0-based) з урахуванням запиту. {@code opts.withCount=false}
   * пропускає {@code count(...)} і повертає {@code total < 0} («не рахували»);
   * {@link usePagedRows} запитує count лише на першій сторінці запиту й кешує його.
   *
   * <p>{@code opts.afterValue}/{@code opts.afterId} — keyset-якір (значення сорт-колонки
   * та id останнього рядка попередньої сторінки). Коли заданий і сорт keyset-придатний,
   * бекенд робить seek замість offset (стала вартість на будь-якій глибині).
   */
  fetchPage: (page: number, size: number, query: RowQuery,
              opts?: { withCount?: boolean; afterValue?: string | null; afterId?: string | null }) =>
    Promise<{ content: T[]; total: number }>;
  /** Розмір сторінки (кількість рядків у чанку). За замовчуванням 100. */
  pageSize?: number;
  /** Версія джерела — зміна інвалідовує кеш (наприклад, після create/delete). */
  version?: number;
  /**
   * Витягує keyset-якір з рядка для ПОТОЧНОГО сортування: {@code value} — значення
   * сорт-колонки (рядком; {@code null} якщо колонка порожня), {@code id} — first key.
   * Повертає {@code null}, якщо сорт НЕ keyset-придатний (багатоколонковий, або не
   * по code/name) — тоді {@link usePagedRows} не передає якір і працює offset.
   * Знає схему рядка, тож постачається власником джерела (напр. {@code ObjectList}).
   */
  keyOf?: (row: T) => { value: string | null; id: string } | null;
}

export interface ClientRowSource<T> {
  kind: "client";
  rows: T[];
}

export type RowSource<T> = ServerRowSource<T> | ClientRowSource<T>;

/**
 * Серверне джерело сторінок: передає {@link RowQuery} на бекенд і отримує готову
 * сторінку + {@code total} (вся таблиця не вантажиться в пам'ять). На відміну від
 * {@link clientPagedSource}, обчислення робить СУБД, а не браузер.
 *
 * @param version інкремент інвалідовує кеш сторінок (після create/delete)
 */
export function serverPagedSource<T>(opts: {
  fetchPage: (page: number, size: number, query: RowQuery,
              o?: { withCount?: boolean; afterValue?: string | null; afterId?: string | null }) =>
    Promise<{ content: T[]; total: number }>;
  pageSize?: number;
  version?: number;
  /** Keyset-екстрактор ключа з рядка (див. {@link ServerRowSource#keyOf}). */
  keyOf?: (row: T) => { value: string | null; id: string } | null;
}): ServerRowSource<T> {
  return {
    kind: "server",
    pageSize: opts.pageSize ?? 100,
    version: opts.version ?? 0,
    fetchPage: opts.fetchPage,
    keyOf: opts.keyOf,
  };
}

/*
 * Обгортає fetch'ер, що повертає весь список одразу ({@code GET td.apiBase}), як
 * ServerRowSource: дані діляться на сторінки, фільтруються/шукаються/сортуються
 * локально через {@link applyClientQuery} тим же контролером {@code usePagedRows}.
 * Так будь-який список отримує scroll/pages, віртуалізацію та uniform-фільтрування.
 *
 * {@code fetchAll} викликається один раз на життєвий цикл (по {@code version});
 * зміна {@code version} інвалідовує кеш.
 */
export function clientPagedSource<T>(opts: {
  fetchAll: () => Promise<T[]>;
  /** Локальні акцесори колонок для серверо-подібної фільтрації. */
  columns: ClientQueryColumn<T>[];
  pageSize?: number;
  /** Інкремент → перезавантажити дані з fetchAll (наприклад, після save/delete). */
  version?: number;
}): ServerRowSource<T> {
  // Per-source кеш всього списку, прив'язаний до версії: зміна версії змушує
  // наступний fetchPage заново викликати fetchAll.
  let cached: { version: number; rows: T[] } | null = null;
  let inflight: Promise<T[]> | null = null;
  const version = opts.version ?? 0;
  async function loadAll(): Promise<T[]> {
    if (cached && cached.version === version) return cached.rows;
    if (inflight) return inflight;
    inflight = opts.fetchAll().then(rs => {
      cached = { version, rows: rs };
      inflight = null;
      return rs;
    }).catch(e => { inflight = null; throw e; });
    return inflight;
  }
  return {
    kind: "server",
    pageSize: opts.pageSize ?? 100,
    version,
    fetchPage: async (page, size, query) => {
      const all = await loadAll();
      const filtered = applyClientQuery(all, opts.columns, query);
      const from = Math.min(page * size, filtered.length);
      const to = Math.min(from + size, filtered.length);
      return { content: filtered.slice(from, to), total: filtered.length };
    },
  };
}

// Симетрична з backend-ним InMemoryRowFilter і UI-ним applyFilter (filterTypes.ts).

export interface ClientQueryColumn<T> {
  id: string;
  /** Display-текст (для substring/пошуку/сортування). */
  textOf: (row: T) => string;
  /** Канонічне значення для порівняння (UUID для ref, code для enum). */
  idOf?: (row: T) => string;
  filterType?: "string" | "number" | "date" | "boolean" | "enum" | "reference" | "unionReference";
  /** Дискримінатор типу для ссилкових/union-колонок (сортування ссилки йде за
   * {@code type_id}). Якщо не заданий, typeId береться з префікса композита
   * "<typeId>:<uuid>" у {@link idOf}. */
  typeIdOf?: (row: T) => number | null;
}

export function applyClientQuery<T>(
  rows: T[],
  columns: ClientQueryColumn<T>[],
  query: RowQuery | undefined,
): T[] {
  if (!query) return rows;
  let out = rows;
  const byId = new Map(columns.map(c => [c.id, c] as const));

  if (query.columnFilters) {
    for (const [colId, sub] of Object.entries(query.columnFilters)) {
      if (!sub || !sub.trim()) continue;
      const col = byId.get(colId);
      if (!col) continue;
      const q = sub.trim().toLowerCase();
      out = out.filter(r => (col.textOf(r) ?? "").toLowerCase().includes(q));
    }
  }

  if (query.search && query.search.trim()) {
    const q = query.search.trim().toLowerCase();
    out = out.filter(r => columns.some(c =>
      (c.textOf(r) ?? "").toLowerCase().includes(q)));
  }

  if (query.advanced && query.advanced.length > 0) {
    for (const f of query.advanced) {
      const col = byId.get(f.columnId);
      if (!col) continue;
      out = out.filter(r => matchesAdvanced(col, r, f));
    }
  }

  // Сортування: пріоритет query.sorts → query.sort. Ссилкові/union-колонки
  // сортуються за type_id (див. {@link compareByColumn}).
  const sorts: SortSpec[] = (query.sorts && query.sorts.length > 0)
    ? query.sorts
    : (query.sort ? [query.sort] : []);
  if (sorts.length > 0) {
    out = [...out].sort((a, b) => {
      for (const s of sorts) {
        const col = byId.get(s.columnId);
        if (!col) continue;
        const c = compareByColumn(col, a, b);
        if (c !== 0) return s.dir === "desc" ? -c : c;
      }
      return 0;
    });
  }
  return out;
}

/** type_id ссилкової колонки для сортування: явний {@code typeIdOf} або префікс
 * композита "<typeId>:<uuid>" з {@code idOf}; інакше {@code null}. */
function refTypeIdOf<T>(col: ClientQueryColumn<T>, row: T): number | null {
  if (col.typeIdOf) return col.typeIdOf(row);
  const idv = col.idOf ? (col.idOf(row) ?? "") : "";
  const i = idv.indexOf(":");
  if (i > 0) {
    const n = Number(idv.slice(0, i));
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function parseDateMs(s: string): number {
  // Підтримка ISO та локалі ДД.ММ.РРРР.
  let t = Date.parse(s);
  if (Number.isNaN(t)) t = Date.parse(s.split(".").reverse().join("-"));
  return t;
}

/** Порівняння двох рядків за однією колонкою (без напрямку). */
function compareByColumn<T>(col: ClientQueryColumn<T>, a: T, b: T): number {
  const ft = col.filterType;
  if (ft === "reference" || ft === "unionReference") {
    // Сортуємо ссилки тільки за type_id (uuid сортувати не має сенсу).
    const ta = refTypeIdOf(col, a);
    const tb = refTypeIdOf(col, b);
    if (ta == null && tb == null) return 0;
    if (ta == null) return 1;   // порожні/моно — у кінець
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
    const da = parseDateMs(sa);
    const db = parseDateMs(sb);
    if (!Number.isNaN(da) && !Number.isNaN(db)) return da - db;
  }
  return sa.localeCompare(sb);
}

function matchesAdvanced<T>(
  col: ClientQueryColumn<T>,
  row: T,
  f: { columnId: string; op: string; value?: unknown; values?: unknown[] }
): boolean {
  const text = col.textOf(row) ?? "";
  const idValue = col.idOf ? col.idOf(row) : text;
  const op = f.op;

  if (op === "empty")    return !text || text.trim() === "";
  if (op === "notEmpty") return !!text && text.trim() !== "";

  if (op === "in" || op === "nin") {
    const list = (f.values ?? []) as Array<unknown>;
    const raws = list.map(v =>
      v && typeof v === "object" && "raw" in (v as object)
        ? String((v as { raw: unknown }).raw ?? "")
        : String(v ?? "")
    ).filter(Boolean);
    if (raws.length === 0) return true;
    const set = new Set(raws);
    return op === "in" ? set.has(idValue) : !set.has(idValue);
  }

  const fv = f.value;
  const rawVal = fv && typeof fv === "object" && "raw" in (fv as object)
    ? String((fv as { raw: unknown }).raw ?? "")
    : String(fv ?? "");
  if (!rawVal.trim()) return true;

  if (col.filterType === "number") {
    const a = Number(idValue.replace(",", "."));
    const b = Number(rawVal.replace(",", "."));
    if (!Number.isNaN(a) && !Number.isNaN(b)) {
      switch (op) {
        case "eq":  return a === b;
        case "neq": return a !== b;
        case "gt":  return a >  b;
        case "gte": return a >= b;
        case "lt":  return a <  b;
        case "lte": return a <= b;
      }
    }
  }
  if (col.filterType === "reference" || col.filterType === "enum") {
    if (op === "eq")  return idValue === rawVal;
    if (op === "neq") return idValue !== rawVal;
  }
  const lc = text.toLowerCase();
  const lcv = rawVal.toLowerCase();
  switch (op) {
    case "eq":         return text === rawVal;
    case "neq":        return text !== rawVal;
    case "contains":   return lc.includes(lcv);
    case "ncontains":  return !lc.includes(lcv);
    case "startsWith": return lc.startsWith(lcv);
    case "endsWith":   return lc.endsWith(lcv);
  }
  return true;
}

/**
 * Sentinel для ще не завантаженого рядка віртуального скролу. {@link ListView}
 * рендерить такі рядки як shimmer-плейсхолдери.
 */
export const PENDING_ROW = Symbol("pending-row");
export type MaybeRow<T> = T | typeof PENDING_ROW;

export function isPending<T>(r: MaybeRow<T>): r is typeof PENDING_ROW {
  return r === PENDING_ROW;
}

interface PagedState<T> {
  /** Загальна кількість рядків (для висоти скролу). null — ще не відомо. */
  total: number | null;
  /** Завантажені сторінки: page → рядки. */
  pages: Map<number, T[]>;
  /** Сторінки, що зараз вантажаться (анти-дубль). */
  loadingPages: Set<number>;
  loading: boolean;
  error: string | null;
}

export interface PagedController<T> {
  total: number | null;
  pageSize: number;
  loading: boolean;
  error: string | null;
  /** Повертає рядок за абсолютним індексом або PENDING_ROW, якщо ще не завантажено. */
  rowAt: (index: number) => MaybeRow<T>;
  /** Замовити завантаження сторінок, що покривають діапазон індексів [from, to]. */
  ensureRange: (from: number, to: number) => void;
  /** Перезавантажити все (скидає кеш). */
  reload: () => void;
  /** Завантажена конкретна сторінка повністю? */
  isPageLoaded: (page: number) => boolean;
}

/**
 * Хук chunked-завантаження для {@link ServerRowSource}. Тримає кеш сторінок,
 * віддає рядки за індексом, довантажує сторінки на запит {@link PagedController#ensureRange}.
 */
export function usePagedRows<T>(source: ServerRowSource<T>, query?: RowQuery): PagedController<T> {
  const pageSize = source.pageSize ?? 100;
  const version = source.version ?? 0;
  // Серіалізований запит — частина ключа кешу: зміна запиту скидає завантажені сторінки.
  const queryKey = JSON.stringify(query ?? {});

  const [state, setState] = useState<PagedState<T>>({
    total: null, pages: new Map(), loadingPages: new Set(),
    loading: true, error: null,
  });

  // fetchPage + query у ref, щоб колбеки лишались стабільними.
  const fetchPageRef = useRef(source.fetchPage);
  fetchPageRef.current = source.fetchPage;
  const keyOfRef = useRef(source.keyOf);
  keyOfRef.current = source.keyOf;
  const queryRef = useRef<RowQuery>(query ?? {});
  queryRef.current = query ?? {};

  // Дзеркало завантажених сторінок поза рендером — щоб loadPage міг дістати
  // останній рядок попередньої сторінки для keyset-якоря без stale-closure.
  const pagesRef = useRef<Map<number, T[]>>(new Map());

  // Множина сторінок, для яких уже стартував/завершився fetch (поза рендером).
  const requestedRef = useRef<Set<number>>(new Set());

  // Скидаємо кеш при зміні версії / розміру сторінки / запиту.
  useEffect(() => {
    requestedRef.current = new Set();
    pagesRef.current = new Map();
    setState({
      total: null, pages: new Map(), loadingPages: new Set(),
      loading: true, error: null,
    });
  }, [version, pageSize, queryKey]);

  const loadPage = useCallback((page: number) => {
    if (page < 0) return;
    if (requestedRef.current.has(page)) return;   // вже замовлено — анти-дубль
    requestedRef.current.add(page);
    setState(prev => {
      if (prev.pages.has(page)) return prev;
      const loadingPages = new Set(prev.loadingPages); loadingPages.add(page);
      return { ...prev, loadingPages };
    });

    // Keyset-якір: якщо попередня сторінка вже завантажена й джерело вміє
    // витягти ключ — беремо останній рядок page-1 як «після». Тоді бекенд
    // робить seek (стала вартість на будь-якій глибині) замість offset.
    let afterValue: string | null | undefined;
    let afterId: string | null | undefined;
    const keyOf = keyOfRef.current;
    if (page > 0 && keyOf) {
      const prevRows = pagesRef.current.get(page - 1);
      if (prevRows && prevRows.length > 0) {
        const k = keyOf(prevRows[prevRows.length - 1]);
        if (k && k.id) { afterValue = k.value; afterId = k.id; }
      }
    }

    fetchPageRef.current(page, pageSize, queryRef.current,
        { withCount: page === 0, afterValue, afterId }).then(res => {
      pagesRef.current.set(page, res.content);
      setState(p => {
        const pages = new Map(p.pages); pages.set(page, res.content);
        const lp = new Set(p.loadingPages); lp.delete(page);
        // total < 0 — sentinel «не рахували»: зберігаємо раніше отриманий total.
        const total = res.total >= 0 ? res.total : p.total;
        return { ...p, pages, loadingPages: lp, total, loading: false, error: null };
      });
    }).catch(e => {
      requestedRef.current.delete(page);   // дозволяємо ретрай при помилці
      setState(p => {
        const lp = new Set(p.loadingPages); lp.delete(page);
        return { ...p, loadingPages: lp, loading: false, error: String(e?.message ?? e) };
      });
    });
  }, [pageSize]);

  // Початкове завантаження першої сторінки (щоб дізнатися total).
  useEffect(() => { loadPage(0); }, [loadPage, version, queryKey]);

  const ensureRange = useCallback((from: number, to: number) => {
    const firstPage = Math.max(0, Math.floor(from / pageSize));
    const lastPage = Math.max(0, Math.floor(to / pageSize));
    for (let p = firstPage; p <= lastPage; p++) loadPage(p);
  }, [pageSize, loadPage]);

  const rowAt = useCallback((index: number): MaybeRow<T> => {
    const page = Math.floor(index / pageSize);
    const within = index % pageSize;
    const rows = state.pages.get(page);
    if (!rows) return PENDING_ROW;
    return rows[within] ?? PENDING_ROW;
  }, [state.pages, pageSize]);

  const isPageLoaded = useCallback(
    (page: number) => state.pages.has(page), [state.pages]);

  const reload = useCallback(() => {
    requestedRef.current = new Set();
    pagesRef.current = new Map();
    setState({
      total: null, pages: new Map(), loadingPages: new Set(),
      loading: true, error: null,
    });
    loadPage(0);
  }, [loadPage]);

  return {
    total: state.total,
    pageSize,
    loading: state.loading,
    error: state.error,
    rowAt, ensureRange, reload, isPageLoaded,
  };
}
