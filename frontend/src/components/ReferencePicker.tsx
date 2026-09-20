import { useEffect, useMemo, useState } from "react";
import type { ReferenceSource, PickerColumn, PickerColumnContext } from "./filterTypes";
import { Alert } from "./Common";
import { ListView, type Column, type RowAction } from "./ListView";
import { clientPagedSource, serverPagedSource, type ClientQueryColumn, type RowSource } from "./rowSource";
import { useDisplayResolver } from "../metadata/DisplayResolver";

/**
 * Модальний picker ссилкових значень. Рендериться як зміст SubWindow.
 *
 * <h3>Режими завантаження даних:</h3>
 * <ul>
 *   <li><b>Server-paged (за наявності {@link ReferenceSource#fetchPage}).</b> Picker
 *       працює поверх {@link serverPagedSource}: фільтри/пошук/сортування/пагінація
 *       транслюються у SQL, у пам'ять вантажиться лише поточна сторінка. Це усуває
 *       завантаження всього довідника (критично для типів на мільйони рядків) і
 *       пришвидшує фільтрацію — обчислення відбувається в СУБД, а не в браузері.
 *       Дисплеї обраних рядків накопичуються по мірі вибору (бо повного списку в
 *       пам'яті більше немає).</li>
 *   <li><b>In-memory fallback (лише {@link ReferenceSource#fetchAll}).</b> Сумісний
 *       режим для джерел без серверної пагінації (напр. невеликі кастомні пікери):
 *       весь список тягнеться один раз і ріжеться на сторінки клієнтом через
 *       {@link clientPagedSource}.</li>
 * </ul>
 *
 * <p><b>Глибина:</b> picker внутрішньо використовує повний {@link ListView} — у ньому
 * доступні ті самі фільтри з операторами, налаштування колонок, debounced quick-filter
 * та рекурсивні ref-picker'и (modal-over-modal через WindowStack).
 */
export interface PickedRef {
  id: string;
  display: string;
}

interface ReferencePickerProps<I> {
  source: ReferenceSource<I>;
  /** Режим множинного вибору (для in/nin). */
  multi: boolean;
  /** Початково вибрані ID. */
  initialSelectedIds?: string[];
  /** Початково вибрані значення з display'ами. У server-paged-режимі дозволяє
   * повернути display раніше обраних записів, яких немає на поточній сторінці. */
  initialSelected?: PickedRef[];
  /**
   * Додаткові дії в рядку (наприклад, «🔍 Перегляд» для drill-down у запис).
   * Передаються прямо в ListView як rowActions після конвертації типів.
   */
  extraRowActions?: RowAction<I>[];
  onConfirm: (picked: PickedRef[]) => void;
  onCancel: () => void;
}

export function ReferencePicker<I>({
  source, multi, initialSelectedIds, initialSelected, extraRowActions, onConfirm, onCancel,
}: ReferencePickerProps<I>) {
  const resolver = useDisplayResolver();
  const serverMode = typeof source.fetchPage === "function";

  // In-memory режим: тримаємо весь список. Server-режим: items лишається порожнім.
  const [items, setItems] = useState<I[]>([]);
  const [loading, setLoading] = useState(!serverMode);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(
    new Set(initialSelectedIds ?? [])
  );
  // id → display обраних записів. У server-режимі наповнюється по мірі вибору
  // (з рядка, по якому клікнули) та з {@code initialSelected}.
  const [selectedDisplays, setSelectedDisplays] = useState<Map<string, string>>(
    () => new Map((initialSelected ?? []).map(p => [p.id, p.display]))
  );
  // Версія джерела — інкрементуємо після reload, щоб paged-кеш скинувся.
  const [sourceVersion, setSourceVersion] = useState(0);

  function reload() {
    if (serverMode) {
      // Server-режим: інвалідовуємо кеш сторінок — ListView перечитає з сервера.
      setSourceVersion(v => v + 1);
      return;
    }
    setLoading(true);
    setError(null);
    source.fetchAll().then(rs => {
      setItems(rs);
      setLoading(false);
      setSourceVersion(v => v + 1);
    }).catch(e => {
      setError(String(e?.message ?? e));
      setLoading(false);
    });
  }

  useEffect(() => {
    // Server-режим не тягне весь список — завантаженням сторінок керує ListView.
    if (serverMode) return;
    let alive = true;
    setLoading(true);
    setError(null);
    source.fetchAll().then(rs => {
      if (!alive) return;
      setItems(rs);
      setLoading(false);
      setSourceVersion(v => v + 1);
    }).catch(e => {
      if (!alive) return;
      setError(String(e?.message ?? e));
      setLoading(false);
    });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function toggleId(id: string, row?: I) {
    if (row) {
      const display = source.getDisplay(row);
      setSelectedDisplays(prev => {
        if (prev.get(id) === display) return prev;
        const next = new Map(prev);
        next.set(id, display);
        return next;
      });
    }
    setSelected(prev => {
      const next = new Set(prev);
      if (multi) {
        if (next.has(id)) next.delete(id); else next.add(id);
      } else {
        next.clear();
        next.add(id);
      }
      return next;
    });
  }

  function confirmCurrent() {
    const picked: PickedRef[] = [];
    if (serverMode) {
      // Повного списку немає — беремо display з накопиченої мапи (fallback на id).
      for (const id of selected) {
        picked.push({ id, display: selectedDisplays.get(id) ?? id });
      }
    } else {
      for (const item of items) {
        if (selected.has(source.getId(item))) {
          picked.push({ id: source.getId(item), display: source.getDisplay(item) });
        }
      }
    }
    onConfirm(picked);
  }

  /** Для single-mode подвійний клік: одразу підтверджуємо саме цей item. */
  function pickAndConfirm(item: I) {
    onConfirm([{ id: source.getId(item), display: source.getDisplay(item) }]);
  }

  // Колонки picker'а з ін'єкцією {@code DisplayResolver}: {@code buildPickerColumns}
  // (якщо є) дає ref-коміркам читабельний рядок замість UUID, інакше — статичні
  // {@code pickerColumns}.
  const pickerColumns: PickerColumn<I>[] = useMemo(() => {
    if (source.buildPickerColumns) {
      const ctx: PickerColumnContext = {
        resolveRef: (refTypeId, id) => resolver.displayOf(refTypeId, id),
      };
      return source.buildPickerColumns(ctx);
    }
    return source.pickerColumns;
  }, [source, resolver]);

  // ListView-колонки з PickerColumn. Вибір (single/multi) обробляє сам ListView
  // через selectable-режим — picker і звичайний список це один модуль.
  const lvColumns = useMemo<Column<I>[]>(
    () => pickerColumns.map(pc => pickerColToLv(pc)),
    [pickerColumns]);

  // Джерело рядків для ListView: server-paged (потрібна лише сторінка) або
  // in-memory (повний список, нарізаний клієнтом) — залежно від можливостей source.
  const dataSource: RowSource<I> = useMemo(() => {
    if (serverMode) {
      // Справжня серверна пагінація: фільтри/пошук/сортування → SQL, без завантаження
      // всього довідника. opts.withCount керує пропуском count(...) на наступних сторінках.
      return serverPagedSource<I>({
        fetchPage: (page, size, query, opts) => source.fetchPage!(page, size, query, opts),
        version: sourceVersion,
        pageSize: 100,
      });
    }
    if (loading || error) {
      // In-memory режим на час завантаження / помилки — порожнє server-джерело
      // (ListView покаже spinner / повідомлення «записів немає»).
      return {
        kind: "server",
        pageSize: 100,
        version: sourceVersion,
        fetchPage: async () => ({ content: [], total: 0 }),
      };
    }
    const queryColumns: ClientQueryColumn<I>[] = lvColumns.map(c => ({
      id: c.id,
      textOf: c.textOf,
      idOf: c.idOf,
      filterType: c.filterType,
    }));
    return clientPagedSource<I>({
      fetchAll: async () => items,
      columns: queryColumns,
      version: sourceVersion,
      pageSize: 100,
    });
  }, [serverMode, source, items, lvColumns, loading, error, sourceVersion]);

  if (error) {
    return (
      <div>
        <Alert kind="error">{error}</Alert>
        <div className="hflex" style={{ justifyContent: "flex-end", marginTop: 16 }}>
          <button className="btn" onClick={onCancel}>Close</button>
        </div>
      </div>
    );
  }

  return (
    <div className="ref-picker">
      <ListView
        dataSource={dataSource}
        enableModeToggle
        displayMode="scroll"
        columns={lvColumns}
        rowId={item => source.getId(item)}
        rowActions={extraRowActions}
        onRefresh={reload}
        loading={loading}
        emptyText="No records"
        selectable
        multiSelect={multi}
        selectedIds={selected}
        onToggleSelect={toggleId}
        onRowDoubleClick={multi ? undefined : item => pickAndConfirm(item)}
        persistKey={source.persistKey ? `picker.${source.persistKey}` : undefined}
      />

      <div className="ref-picker__footer">
        <span className="muted" style={{ fontSize: 12, flex: 1 }}>
          {multi
            ? `Selected: ${selected.size}`
            : (selected.size > 0 ? "1 record selected" : "Pick a record with a double click, or press «Confirm»")}
        </span>
        {}
        {/* Відкрити розділ, з якого виконується пікінг, у новій вкладці. */}
        {source.sectionPath && (
          <button className="btn"
                  onClick={() => window.open(source.sectionPath!, "_blank", "noopener")}
                  title="Open section in a new tab">
            ⧉ Open section
          </button>
        )}
        <button className="btn" onClick={onCancel}>Cancel</button>
        <button className="btn btn--primary"
                onClick={confirmCurrent}
                disabled={selected.size === 0}>
          Confirm{multi && selected.size > 0 ? ` (${selected.size})` : ""}
        </button>
      </div>
    </div>
  );
}

/** Конвертація PickerColumn → Column для ListView. */
function pickerColToLv<I>(pc: PickerColumn<I>): Column<I> {
  return {
    id: pc.id,
    header: pc.header,
    textOf: pc.textOf,
    idOf: pc.idOf,
    render: pc.render,
    width: pc.width,
    align: pc.align,
    filterType: pc.filterType,
    enumOptions: pc.enumOptions,
    refSource: pc.refSource,
  };
}
