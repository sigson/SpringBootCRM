import { useMemo, useState } from "react";
import type {
  AdvancedFilter, FilterOp, ColumnFilterType,
  ReferenceSource, EnumOption,
} from "./filterTypes";
import {
  OP_LABELS, opsForType, defaultOpForType, needsValue, isMultiValueOp,
} from "./filterTypes";
import { FilterValueInput } from "./FilterValueInput";
import { ValueListPicker } from "./ValueListPicker";
import { useWindowStack, nextWindowId } from "../windows/WindowStack";

/**
 * Полегшена проекція Column<T> для передачі в FilterDialog.
 *
 * <p>ListView.tsx будує цю структуру з повних Column<T>, тому залежність
 * однонаправлена: FilterDialog НЕ імпортує Column<T> з ListView.tsx
 * (це дозволяє ListView імпортувати FilterDialog без циклу).
 */
export interface FilterDialogColumn {
  id: string;
  header: string;
  filterType?: ColumnFilterType;
  enumOptions?: EnumOption[];
  refSource?: ReferenceSource;
  /** Для filterType="unionReference" — перелік допустимих типів union'а. */
  refTypeIds?: number[];
}

/**
 * Модальне вікно «Налаштування фільтрів».
 *
 * <p>Рендериться як content SubWindow (відкривається з ListView через
 * {@code useWindowStack().open}). Сам має ще одну глибину модалок:
 * для operator'а «у списку» / «не у списку» відкриває {@link ValueListPicker},
 * який, своєю чергою, може відкрити {@link ReferencePicker} (якщо колонка є
 * ssilkovoy). Усі рівні рендеряться як subwindows, breadcrumb у WindowStack
 * показує шлях.
 *
 * <p>Власну «Скасувати»/«Застосувати» зміни рендеримо у footer контенту,
 * а не в окремому subwindow__footer — щоб не дублювати кнопки close.
 */
interface FilterDialogProps {
  columns: FilterDialogColumn[];
  initialFilters: AdvancedFilter[];
  onConfirm: (filters: AdvancedFilter[]) => void;
  onCancel: () => void;
}

export function FilterDialog({
  columns, initialFilters, onConfirm, onCancel,
}: FilterDialogProps) {
  const [draft, setDraft] = useState<AdvancedFilter[]>(initialFilters);
  const { open, closeById } = useWindowStack();
  const colById = useMemo(
    () => new Map(columns.map(c => [c.id, c])),
    [columns]
  );

  function addRow() {
    const c = columns[0];
    if (!c) return;
    const ftype = c.filterType ?? "string";
    setDraft(d => [...d, {
      uid: `f-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      columnId: c.id,
      op: defaultOpForType(ftype),
      value: { raw: "", display: "" },
    }]);
  }
  function patchRow(uid: string, patch: Partial<AdvancedFilter>) {
    setDraft(d => d.map(f => f.uid === uid ? { ...f, ...patch } : f));
  }
  function removeRow(uid: string) {
    setDraft(d => d.filter(f => f.uid !== uid));
  }
  function clearAll() { setDraft([]); }

  function apply() {
    // Чистимо: викидаємо неповні фільтри (порожнє значення для op'ів, які
    // потребують значення; порожній список для in/nin).
    const cleaned = draft.filter(f => {
      if (!needsValue(f.op)) return true;
      if (isMultiValueOp(f.op)) return (f.values?.length ?? 0) > 0;
      return (f.value?.raw ?? "").trim() !== "";
    });
    onConfirm(cleaned);
  }

  function openListPicker(f: AdvancedFilter) {
    const col = colById.get(f.columnId);
    if (!col) return;
    const winId = nextWindowId();
    open({
      id: winId,
      title: `Value list: ${col.header}`,
      width: "default",
      content: (
        <ValueListPicker
          columnHeader={col.header}
          filterType={col.filterType ?? "string"}
          initialValues={f.values ?? []}
          enumOptions={col.enumOptions}
          refSource={col.refSource}
          refTypeIds={col.refTypeIds}
          onConfirm={(vals) => {
            patchRow(f.uid, { values: vals });
            closeById(winId);
          }}
          onCancel={() => closeById(winId)}
        />
      ),
    });
  }

  return (
    <div className="filter-dialog">
      {draft.length === 0 ? (
        <div className="empty-state">
          No filters set. Press <strong>«＋ Add filter»</strong>, to start.
        </div>
      ) : (
        <table className="lv-filter-table">
          <thead>
            <tr>
              <th style={{ width: "26%" }}>Column</th>
              <th style={{ width: "20%" }}>Statement</th>
              <th>Value</th>
              <th style={{ width: 40 }}></th>
            </tr>
          </thead>
          <tbody>
            {draft.map(f => {
              const col = colById.get(f.columnId);
              const ftype: ColumnFilterType = col?.filterType ?? "string";
              const allowedOps = opsForType(ftype);
              return (
                <tr key={f.uid}>
                  <td>
                    <select className="lv-select" value={f.columnId}
                            onChange={e => {
                              const newCol = colById.get(e.target.value);
                              const newType = newCol?.filterType ?? "string";
                              const ops = opsForType(newType);
                              patchRow(f.uid, {
                                columnId: e.target.value,
                                op: ops.includes(f.op) ? f.op : ops[0]!,
                                value: { raw: "", display: "" },
                                values: undefined,
                              });
                            }}>
                      {columns.map(c => (
                        <option key={c.id} value={c.id}>{c.header}</option>
                      ))}
                    </select>
                  </td>
                  <td>
                    <select className="lv-select" value={f.op}
                            onChange={e => {
                              const newOp = e.target.value as FilterOp;
                              const wasMulti = isMultiValueOp(f.op);
                              const isMulti = isMultiValueOp(newOp);
                              patchRow(f.uid, {
                                op: newOp,
                                ...(wasMulti !== isMulti ? {
                                  value: isMulti ? undefined : { raw: "", display: "" },
                                  values: isMulti ? [] : undefined,
                                } : {}),
                              });
                            }}>
                      {allowedOps.map(op => (
                        <option key={op} value={op}>{OP_LABELS[op]}</option>
                      ))}
                    </select>
                  </td>
                  <td>
                    {!needsValue(f.op) ? (
                      <span className="muted" style={{ fontSize: 11 }}>— does not require —</span>
                    ) : isMultiValueOp(f.op) ? (
                      <button className="btn btn--small" onClick={() => openListPicker(f)}>
                        📋 Edit list ({f.values?.length ?? 0})
                      </button>
                    ) : (
                      <FilterValueInput
                        key={`${f.uid}-${ftype}`}
                        filterType={ftype}
                        value={f.value ?? { raw: "", display: "" }}
                        onChange={(v) => patchRow(f.uid, { value: v })}
                        enumOptions={col?.enumOptions}
                        refSource={col?.refSource}
                        refTypeIds={col?.refTypeIds}
                      />
                    )}
                  </td>
                  <td style={{ textAlign: "right" }}>
                    <button className="icon-btn icon-btn--small"
                            onClick={() => removeRow(f.uid)}
                            title="Remove filter">✕</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      <div className="filter-dialog__add-row">
        <button className="btn btn--small" onClick={addRow}>＋ Add filter</button>
        {draft.length > 0 && (
          <button className="btn btn--small btn--danger"
                  onClick={clearAll}
                  style={{ marginLeft: 6 }}>
            Delete all
          </button>
        )}
      </div>

      <div className="muted" style={{ marginTop: 8, fontSize: 11 }}>
        All filters are joined with a logical <strong>AND</strong>.
        For the statement <code>in list</code> / <code>not in list</code> —
        a separate window opens to manage the value list.
      </div>

      <div className="filter-dialog__footer">
        <button className="btn" onClick={onCancel}>Cancel</button>
        <button className="btn btn--primary" onClick={apply}>Apply</button>
      </div>
    </div>
  );
}
