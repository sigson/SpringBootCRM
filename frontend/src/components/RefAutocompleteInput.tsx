import {
  useCallback, useEffect, useMemo, useRef, useState,
  type KeyboardEvent, type ReactNode,
} from "react";
import type { ReferenceSource } from "./filterTypes";
import { ReferencePicker } from "./ReferencePicker";
import { nextWindowId, useWindowStack } from "../windows/WindowStack";
import { isErrorEnvelope } from "../api/client";
import { NO_ACCESS_LABEL } from "../metadata/DisplayResolver";
import type { RowAction } from "./ListView";

/**
 * Універсальне типізоване поле введення ссилкового значення (1С-стиль):
 * inline-автодоповнення (substring по display/code/name) з клавіатурною навігацією,
 * кнопка «…» відкриває {@link ReferencePicker} (рекурсивно через WindowStack),
 * опціональні «→» (drill-down) та «✕» (очищення). При blur з невалідним вводом
 * поле відкочується до валідного display'у або очищається.
 *
 * Список {@code source.fetchAll()} кешується по {@code source.persistKey};
 * інвалідація — через {@link invalidateRefAutocompleteCache}.
 */

const itemsCache = new Map<string, unknown[]>();
const inFlight  = new Map<string, Promise<unknown[]>>();

/** Інвалідувати кеш (всього або конкретного persistKey'у). */
export function invalidateRefAutocompleteCache(persistKey?: string): void {
  if (persistKey) {
    itemsCache.delete(persistKey);
    inFlight.delete(persistKey);
  } else {
    itemsCache.clear();
    inFlight.clear();
  }
}

async function fetchCached<I>(source: ReferenceSource<I>): Promise<I[]> {
  const key = source.persistKey ?? "<anon>";
  const cached = itemsCache.get(key);
  if (cached) return cached as I[];
  const pending = inFlight.get(key);
  if (pending) return pending as Promise<I[]>;
  const p = source.fetchAll()
    .then((items) => {
      itemsCache.set(key, items as unknown[]);
      inFlight.delete(key);
      return items;
    })
    .catch((err) => {
      inFlight.delete(key);
      throw err;
    });
  inFlight.set(key, p as Promise<unknown[]>);
  return p;
}

function itemCode(item: unknown): string {
  if (item && typeof item === "object" && "code" in item) {
    const c = (item as Record<string, unknown>).code;
    return c == null ? "" : String(c);
  }
  return "";
}
function itemName(item: unknown): string {
  if (item && typeof item === "object" && "name" in item) {
    const n = (item as Record<string, unknown>).name;
    return n == null ? "" : String(n);
  }
  return "";
}

export interface RefAutocompleteValue {
  id: string;
  display: string;
}

export interface RefAutocompleteInputProps<I = unknown> {
  /** Поточно обраний ID (null/"" — нічого не обрано). */
  value: string | null;
  /** Display для поточного value. Якщо не задано — шукається у завантажених items
   * за value (корисно, коли display приходить через {@code useDisplayResolver}). */
  displayValue?: string | null;
  /** Джерело даних (метадані picker'а + fetcher). */
  source: ReferenceSource<I>;
  /** Викликається при зміні value (вибір/очищення). */
  onChange: (val: RefAutocompleteValue | null) => void;
  /** Викликається при кліку на «→» (drill-down). Якщо не задано, кнопка не показується. */
  onDrillDown?: () => void;
  /** Дозволити «✕» для очищення (default: true). */
  clearable?: boolean;
  /** Read-only режим (input non-editable, picker недоступний). */
  readOnly?: boolean;
  /** Disabled (input disabled, ВСІ кнопки сховані). */
  disabled?: boolean;
  /** Auto-focus input. */
  autoFocus?: boolean;
  /** Placeholder для пустого поля. */
  placeholder?: string;
  /** Додаткові row-actions у picker'і (наприклад «🔍 Перегляд»). */
  pickerRowActions?: RowAction<I>[];
  /** Кастомне рендерування одного item у dropdown'і (default — code + display). */
  renderOption?: (item: I) => ReactNode;
  /** Максимум елементів у dropdown'і (default 12). */
  maxOptions?: number;
  /** ID, які виключити з autocomplete-варіантів (напр. вже обрані у {@code RefMultiField}).
   * Кеш не розривається — фільтрація у пам'яті на рівні відображення. */
  excludeIds?: ReadonlyArray<string>;
}

export function RefAutocompleteInput<I = unknown>(
  props: RefAutocompleteInputProps<I>,
): JSX.Element {
  const {
    value, displayValue, source, onChange, onDrillDown,
    clearable = true, readOnly, disabled, autoFocus, placeholder,
    pickerRowActions, renderOption, maxOptions = 12, excludeIds,
  } = props;

  const { open, closeById } = useWindowStack();

  // Server-режим: джерело вантажить сторінки (SQL-фільтрація), тож autocomplete
  // запитує лише сторінку збігів під уведений текст, не весь довідник.
  const serverMode = typeof source.fetchPage === "function";

  // Локальний текст input'а; синхронізується з displayValue/value, коли не у фокусі.
  const [text, setText] = useState(displayValue ?? "");
  const [items, setItems] = useState<I[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [open_, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(0);
  const [focused, setFocused] = useState(false);

  const wrapRef  = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  // ---- зовнішня синхронізація ----
  // Зміна value/displayValue ззовні оновлює text, але лише коли користувач не
  // редагує (інакше зб'ємо його ввід).
  useEffect(() => {
    if (focused) return;
    setText(displayValue ?? "");
  }, [displayValue, value, focused]);

  const ensureLoaded = useCallback(() => {
    if (serverMode) return;   // server-режим вантажить сторінки збігів окремим effect'ом
    if (items != null || loading) return;
    setLoading(true);
    setLoadError(null);
    fetchCached(source)
      .then((rs) => { setItems(rs); setLoading(false); })
      .catch((e) => {
        // Нет прав на чтение справочника — показываем «нет доступа», а не сырую ошибку.
        if (isErrorEnvelope(e) && e.kind === "ACCESS_DENIED") {
          setItems([]);
          setLoadError(NO_ACCESS_LABEL);
        } else {
          setLoadError(String(e?.message ?? e));
        }
        setLoading(false);
      });
  }, [serverMode, items, loading, source]);

  // Дебаунс, запит лише поки dropdown відкритий. Серверна фільтрація повертає
  // невелику сторінку — клієнтське ранжування нижче лише впорядковує її.
  useEffect(() => {
    if (!serverMode || !open_) return;
    let alive = true;
    setLoading(true);
    setLoadError(null);
    const handle = setTimeout(() => {
      source.fetchPage!(0, Math.max(maxOptions, 20),
        { search: text.trim() || undefined }, { withCount: false })
        .then((r) => { if (!alive) return; setItems(r.content as I[]); setLoading(false); })
        .catch((e) => {
          if (!alive) return;
          if (isErrorEnvelope(e) && e.kind === "ACCESS_DENIED") {
            setItems([]); setLoadError(NO_ACCESS_LABEL);
          } else {
            setLoadError(String(e?.message ?? e));
          }
          setLoading(false);
        });
    }, 250);
    return () => { alive = false; clearTimeout(handle); };
  }, [serverMode, open_, text, source, maxOptions]);

  /** Пошук по підстроці у display/code/name. Сортування: префікси по коду →
   * префікси по display → substring. {@code excludeIds} відсіюються до пошуку. */
  const matches = useMemo<I[]>(() => {
    if (!items) return [];
    const exclude = excludeIds && excludeIds.length > 0
      ? new Set(excludeIds)
      : null;
    const q = text.trim().toLowerCase();
    if (q === "") {
      const out: I[] = [];
      for (const it of items) {
        if (exclude && exclude.has(source.getId(it))) continue;
        out.push(it);
        if (out.length >= maxOptions) break;
      }
      return out;
    }
    const scored: Array<{ item: I; score: number }> = [];
    for (const it of items) {
      if (exclude && exclude.has(source.getId(it))) continue;
      const code = itemCode(it).toLowerCase();
      const name = itemName(it).toLowerCase();
      const disp = source.getDisplay(it).toLowerCase();
      let score = 0;
      if (code === q || disp === q || name === q) score = 100;
      else if (code.startsWith(q)) score = 90;
      else if (disp.startsWith(q) || name.startsWith(q)) score = 70;
      else if (code.includes(q) || disp.includes(q) || name.includes(q)) score = 30;
      if (score > 0) scored.push({ item: it, score });
    }
    scored.sort((a, b) => b.score - a.score);
    return scored.slice(0, maxOptions).map(s => s.item);
  }, [items, text, source, maxOptions, excludeIds]);

  /** Знайти запис, який ТОЧНО (case-insensitive) збігається з queryStr. */
  const findExactMatch = useCallback((q: string): I | null => {
    if (!items) return null;
    const lc = q.trim().toLowerCase();
    if (!lc) return null;
    for (const it of items) {
      if (itemCode(it).toLowerCase() === lc) return it;
    }
    for (const it of items) {
      if (itemName(it).toLowerCase() === lc) return it;
    }
    for (const it of items) {
      if (source.getDisplay(it).toLowerCase() === lc) return it;
    }
    return null;
  }, [items, source]);

  const pickItem = useCallback((item: I) => {
    const id = source.getId(item);
    const display = source.getDisplay(item);
    onChange({ id, display });
    setText(display);
    setOpen(false);
    setHighlight(0);
  }, [source, onChange]);

  /**
   * Commit при blur: текст == display → нічого; пусто → очищення (якщо clearable);
   * точний збіг з item → вибір; інакше інвалідний ввід → відкат до displayValue
   * (або очищення, якщо value був null).
   */
  const commitOnBlur = useCallback(() => {
    setOpen(false);
    const current = displayValue ?? "";
    if (text === current) return;
    if (text.trim() === "") {
      if (clearable && value) onChange(null);
      else if (!clearable) setText(current);
      return;
    }
    const exact = findExactMatch(text);
    if (exact) {
      pickItem(exact);
      return;
    }
    if (value) {
      // Було обране — повертаємо display
      setText(current);
    } else {
      // Не було обраного — очищаємо текст (value вже null).
      setText("");
    }
  }, [displayValue, text, value, clearable, onChange, findExactMatch, pickItem]);

  useEffect(() => {
    if (!focused && !open_) return;
    function onDocMouseDown(e: MouseEvent) {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) {
        // Симулюємо blur — ввід має бути закомічений
        commitOnBlur();
        setFocused(false);
      }
    }
    document.addEventListener("mousedown", onDocMouseDown);
    return () => document.removeEventListener("mousedown", onDocMouseDown);
  }, [focused, open_, commitOnBlur]);

  function openPicker() {
    if (readOnly || disabled) return;
    // Закриваємо dropdown і скидаємо focused-стан: фокус перейде в picker-модалку,
    // інакше dropdown рендериться поверх picker'а й не синхронізує text.
    setOpen(false);
    setFocused(false);
    const winId = nextWindowId();
    open({
      id: winId,
      title: source.pickerTitle ?? "Pick a value",
      width: "wide",
      content: (
        <ReferencePicker<I>
          source={source}
          multi={false}
          initialSelectedIds={value ? [value] : []}
          initialSelected={value ? [{ id: value, display: displayValue ?? value }] : []}
          extraRowActions={pickerRowActions}
          onConfirm={(picked) => {
            if (picked.length > 0) {
              onChange({ id: picked[0]!.id, display: picked[0]!.display });
              setText(picked[0]!.display);
            }
            closeById(winId);
          }}
          onCancel={() => closeById(winId)}
        />
      ),
    });
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!open_) { ensureLoaded(); setOpen(true); }
      setHighlight(i => Math.min(i + 1, Math.max(0, matches.length - 1)));
      return;
    }
    if (e.key === "ArrowUp") {
      e.preventDefault();
      setHighlight(i => Math.max(0, i - 1));
      return;
    }
    if (e.key === "Enter") {
      if (open_ && matches[highlight]) {
        e.preventDefault();
        pickItem(matches[highlight]!);
      }
      return;
    }
    if (e.key === "Escape") {
      if (open_) {
        e.preventDefault();
        // Не пускаємо Escape далі — інакше WindowStack закриє верхнє субокно
        // (його document-level keydown ловить Escape). Для надійності — і нативний
        // stopImmediatePropagation.
        e.stopPropagation();
        e.nativeEvent.stopImmediatePropagation?.();
        setOpen(false);
        setText(displayValue ?? "");
      }
      return;
    }
    if (e.key === "Tab") {
      // Tab = blur через DOM; commit спрацює через outside-click або onBlur.
    }
  }

  // Display-only режим (readOnly з показом display'у, без можливості ввести):
  const valueIsLoading = value && !displayValue;
  const showPlaceholder = !value && !text;

  function defaultRenderOption(item: I): ReactNode {
    const code = itemCode(item);
    const display = source.getDisplay(item);
    const name = itemName(item);
    return (
      <>
        {code && <span className="ref-ac__opt-code mono">{code}</span>}
        <span className="ref-ac__opt-text">
          {name && name !== display ? name : display}
        </span>
      </>
    );
  }

  return (
    <div
      ref={wrapRef}
      className={`ref-ac${disabled ? " ref-ac--disabled" : ""}${readOnly ? " ref-ac--readonly" : ""}`}
    >
      <div className="ref-ac__row">
        <input
          ref={inputRef}
          type="text"
          className="ref-ac__input"
          value={valueIsLoading && !focused ? "Loading…" : text}
          placeholder={placeholder ?? (showPlaceholder
            ? "Code or name…"
            : undefined)}
          autoFocus={autoFocus}
          disabled={disabled}
          readOnly={readOnly}
          spellCheck={false}
          autoComplete="off"
          onFocus={() => {
            setFocused(true);
            if (readOnly || disabled) return;
            ensureLoaded();
            setOpen(true);
            setHighlight(0);
            // Якщо стояв "Завантаження…", очищаємо для вводу
            if (valueIsLoading) setText("");
          }}
          onBlur={() => {
            // Commit робить outside-click (щоб клік по dropdown не «вмирав» від blur).
          }}
          onChange={(e) => {
            setText(e.target.value);
            if (!open_) setOpen(true);
            setHighlight(0);
            ensureLoaded();
          }}
          onKeyDown={onKeyDown}
          aria-autocomplete="list"
          aria-expanded={open_}
          aria-controls="ref-ac-listbox"
        />
        {!readOnly && !disabled && (
          <button
            type="button"
            className="ref-ac__btn ref-ac__btn--picker"
            title="Pick from a list (opens the catalog table)"
            aria-label="Select"
            tabIndex={-1}
            onMouseDown={(e) => e.preventDefault()}  // не втрачати фокус
            onClick={openPicker}
          >…</button>
        )}
        {onDrillDown && value && !disabled && (
          <button
            type="button"
            className="ref-ac__btn ref-ac__btn--drill"
            title="Open the selected object"
            aria-label="Open"
            tabIndex={-1}
            onMouseDown={(e) => e.preventDefault()}
            onClick={onDrillDown}
          >→</button>
        )}
        {clearable && !readOnly && !disabled && (value || text) && (
          <button
            type="button"
            className="ref-ac__btn ref-ac__btn--clear"
            title="Clear"
            aria-label="Clear"
            tabIndex={-1}
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => {
              setText("");
              onChange(null);
              setOpen(false);
              inputRef.current?.focus();
            }}
          >✕</button>
        )}
      </div>

      {open_ && !readOnly && !disabled && (
        <div className="ref-ac__dropdown" role="listbox" id="ref-ac-listbox">
          {loading ? (
            <div className="ref-ac__msg">Loading catalog…</div>
          ) : loadError ? (
            <div className="ref-ac__msg ref-ac__msg--error">
              Error: {loadError}
            </div>
          ) : matches.length === 0 ? (
            <div className="ref-ac__msg">
              {text.trim()
                ? <>Not found: <strong>«{text.trim()}»</strong>. Press <kbd>…</kbd> to open the full table.</>
                : "The catalog has no records"}
            </div>
          ) : (
            <ul className="ref-ac__list">
              {matches.map((it, idx) => {
                const id = source.getId(it);
                const isSelected = id === value;
                const isHi = idx === highlight;
                return (
                  <li
                    key={id}
                    className={[
                      "ref-ac__item",
                      isHi ? "ref-ac__item--active" : "",
                      isSelected ? "ref-ac__item--selected" : "",
                    ].filter(Boolean).join(" ")}
                    role="option"
                    aria-selected={isSelected}
                    onMouseEnter={() => setHighlight(idx)}
                    onMouseDown={(e) => {
                      // mousedown, не click — щоб onBlur input'а не з'їв подію
                      e.preventDefault();
                      pickItem(it);
                    }}
                  >
                    {renderOption ? renderOption(it) : defaultRenderOption(it)}
                  </li>
                );
              })}
            </ul>
          )}
          <div className="ref-ac__hint muted">
            <span>↑↓ — navigation, Enter — select, Esc — cancel</span>
            <span className="spacer" />
            <span>{items
              ? `Records: ${matches.length}${items.length > maxOptions && matches.length === maxOptions ? "+" : ""} / ${items.length}`
              : ""}</span>
          </div>
        </div>
      )}
    </div>
  );
}
