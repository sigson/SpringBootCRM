import { useMemo, useState } from "react";

/**
 * Модальне вікно «Налаштування колонок».
 *
 * <p>Дозволяє керувати:
 * <ul>
 *   <li>видимістю кожної колонки (чекбокс);</li>
 *   <li>порядком (стрілки ↑↓);</li>
 *   <li>обов'язкові колонки (alwaysVisible) — заблоковані, не приховуються.</li>
 * </ul>
 *
 * <p>Відкривається ListView'ом через {@code useWindowStack().open}, що дозволяє
 * відкривати її поверх інших модалок (наприклад, при налаштуванні picker'а
 * посередині filter-stack'а).
 */
export interface ColumnSettingsItem {
  id: string;
  header: string;
  alwaysVisible?: boolean;
}

interface ColumnSettingsDialogProps {
  columns: ColumnSettingsItem[];
  initialHidden: string[];
  initialOrder: string[];
  onConfirm: (hidden: string[], order: string[]) => void;
  onCancel: () => void;
  /** Чи є активні користувацькі зміни ширин колонок (вмикає кнопку скидання). */
  hasWidthOverrides?: boolean;
  /** Скинути всі користувацькі корективи ширин — колонки перемасштабуються під
   *  доступний простір згідно з базовими розмірами. Діє одразу (live). */
  onResetWidths?: () => void;
}

export function ColumnSettingsDialog({
                                       columns, initialHidden, initialOrder, onConfirm, onCancel,
                                       hasWidthOverrides = false, onResetWidths,
                                     }: ColumnSettingsDialogProps) {
  const [hidden, setHidden] = useState<Set<string>>(new Set(initialHidden));
  const [order, setOrder] = useState<string[]>(
      initialOrder.length > 0 ? initialOrder : columns.map(c => c.id)
  );
  // Чи вже скидали ширини в межах цього відкриття діалогу (для UI-стану кнопки).
  const [widthsReset, setWidthsReset] = useState(false);

  const byId = useMemo(() => new Map(columns.map(c => [c.id, c])), [columns]);

  function toggleHidden(id: string) {
    const c = byId.get(id);
    if (c?.alwaysVisible) return;
    setHidden(h => {
      const next = new Set(h);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }
  function move(id: string, dir: -1 | 1) {
    setOrder(o => {
      const idx = o.indexOf(id);
      if (idx < 0) return o;
      const target = idx + dir;
      if (target < 0 || target >= o.length) return o;
      const next = o.slice();
      [next[idx], next[target]] = [next[target]!, next[idx]!];
      return next;
    });
  }
  function reset() {
    setHidden(new Set());
    setOrder(columns.map(c => c.id));
  }

  const visibleCount = order.length - hidden.size;

  return (
      <div className="column-settings">
        <p className="muted" style={{ fontSize: 12, marginTop: 0 }}>
          The checkbox is visibility. With the arrows ↑↓ — placement order.
          {onResetWidths && " The «Reset column widths» button drops manual adjustments and fits the columns into the available space."}
        </p>
        <div className="muted" style={{ fontSize: 11, marginBottom: 8 }}>
          Visible columns: <strong>{visibleCount}</strong> of {columns.length}
        </div>

        <ul className="lv-col-list">
          {order.map((id, idx) => {
            const c = byId.get(id);
            if (!c) return null;
            const isHidden = hidden.has(id);
            const locked = !!c.alwaysVisible;
            return (
                <li key={id}
                    className={`lv-col-list__item ${isHidden ? "lv-col-list__item--hidden" : ""}`}>
                  <label className="lv-col-list__cb">
                    <input type="checkbox"
                           checked={!isHidden}
                           onChange={() => toggleHidden(id)}
                           disabled={locked} />
                    <span>{c.header}</span>
                    {locked && (
                        <span className="tag" style={{ marginLeft: 6 }}>required</span>
                    )}
                  </label>
                  <span className="lv-col-list__move">
                <button className="icon-btn icon-btn--small"
                        onClick={() => move(id, -1)}
                        disabled={idx === 0}
                        title="Up">↑</button>
                <button className="icon-btn icon-btn--small"
                        onClick={() => move(id, +1)}
                        disabled={idx === order.length - 1}
                        title="Down">↓</button>
              </span>
                </li>
            );
          })}
        </ul>

        <div className="column-settings__footer">
          <button className="btn btn--small" onClick={reset}
                  title="Make all columns visible in the default order">
            ↺ Reset
          </button>
          {onResetWidths && (
              <button
                  className="btn btn--small"
                  disabled={!hasWidthOverrides || widthsReset}
                  onClick={() => { onResetWidths(); setWidthsReset(true); }}
                  title="Drop manual width adjustments - the columns fit the available space again at their base sizes">
                {widthsReset ? "↔ Widths reset" : "↔ Reset column widths"}
              </button>
          )}
          <div style={{ flex: 1 }} />
          <button className="btn" onClick={onCancel}>Cancel</button>
          <button className="btn btn--primary"
                  onClick={() => onConfirm(Array.from(hidden), order)}>
            Apply
          </button>
        </div>
      </div>
  );
}
