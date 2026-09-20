import { useState } from "react";
import type {
  ColumnFilterType, FilterValue, ReferenceSource, EnumOption,
} from "./filterTypes";
import { FilterValueInput } from "./FilterValueInput";
import { useWindowStack, nextWindowId } from "../windows/WindowStack";
import { ReferencePicker } from "./ReferencePicker";

/**
 * Модальне вікно «Список значень» для оператора «у списку» / «не у списку».
 *
 * <p>Призначення: дозволити користувачу зібрати набір значень, з якими буде
 * порівнюватися значення колонки (логічне OR всередині списку).
 *
 * <ul>
 *   <li><b>Для звичайних типів</b> (string/number/date/boolean/enum) — типізоване
 *       поле введення + кнопка «＋ Додати», елементи списку видаляються
 *       індивідуально, є кнопка «🗑 Очистити всі»;</li>
 *   <li><b>Для reference</b> — кнопка «📂 Вибрати з довідника…» відкриває
 *       {@link ReferencePicker} в multi-режимі. Picker сам показує таблицю
 *       з фільтрами/налаштуваннями колонок (через ListView). Користувач
 *       зазначає чекбоксами потрібні рядки та підтверджує → результат стає
 *       новим списком значень.</li>
 * </ul>
 */
interface ValueListPickerProps {
  columnHeader: string;
  filterType: ColumnFilterType;
  initialValues: FilterValue[];
  enumOptions?: EnumOption[];
  refSource?: ReferenceSource;
  /** Для filterType="unionReference" — перелік допустимих типів union'а. */
  refTypeIds?: number[];
  onConfirm: (values: FilterValue[]) => void;
  onCancel: () => void;
}

export function ValueListPicker({
  columnHeader, filterType, initialValues, enumOptions, refSource, refTypeIds,
  onConfirm, onCancel,
}: ValueListPickerProps) {
  const [values, setValues] = useState<FilterValue[]>(initialValues);
  const [draft, setDraft] = useState<FilterValue>({ raw: "", display: "" });
  const { open, closeById } = useWindowStack();

  function addDraft() {
    if (!draft.raw.trim()) return;
    if (values.some(v => v.raw === draft.raw)) {
      // не дублюємо
      setDraft({ raw: "", display: "" });
      return;
    }
    setValues(prev => [...prev, { ...draft }]);
    setDraft({ raw: "", display: "" });
  }
  function removeAt(idx: number) {
    setValues(prev => prev.filter((_, i) => i !== idx));
  }
  function clearAll() { setValues([]); }

  function openRefPicker() {
    if (!refSource) return;
    const winId = nextWindowId();
    open({
      id: winId,
      title: refSource.pickerTitle ?? `Pick values: ${columnHeader}`,
      width: "wide",
      content: (
        <ReferencePicker
          source={refSource}
          multi={true}
          initialSelectedIds={values.map(v => v.raw)}
          initialSelected={values.map(v => ({ id: v.raw, display: v.display }))}
          onConfirm={(picked) => {
            // Заміняємо весь список вибраним у picker'і
            setValues(picked.map(p => ({ raw: p.id, display: p.display })));
            closeById(winId);
          }}
          onCancel={() => closeById(winId)}
        />
      ),
    });
  }

  const isRef = filterType === "reference";
  const isUnionRef = filterType === "unionReference";

  return (
    <div className="value-list-picker">
      <div className="muted" style={{ fontSize: 12, marginBottom: 10 }}>
        Value list for the column <strong>«{columnHeader}»</strong>.
        Logic: <em>the column value must be one from the list</em>
        {" "}(or absent - for «not in list»).
      </div>

      {isRef ? (
        <div className="vlp-ref-action">
          <button className="btn btn--primary" onClick={openRefPicker}>
            📂 Pick from catalog…
          </button>
          <span className="muted" style={{ fontSize: 11, marginLeft: 8 }}>
            {values.length > 0
              ? `Currently selected: ${values.length}. Having opened picker, you can extend or adjust the list.`
              : "A card file opens, with filters and column settings."}
          </span>
        </div>
      ) : isUnionRef ? (
        <div className="vlp-add-row">
          <div style={{ flex: 1 }}>
            {/* Union: [Т] вибір типу → picker цього типу; кожне значення додається в список окремо. */}
            <FilterValueInput
              filterType={filterType}
              value={draft}
              onChange={setDraft}
              refTypeIds={refTypeIds}
            />
          </div>
          <button className="btn btn--primary"
                  onClick={addDraft}
                  disabled={!draft.raw.trim()}>
            ＋ Add
          </button>
        </div>
      ) : (
        <div className="vlp-add-row">
          <div style={{ flex: 1 }}>
            <FilterValueInput
              filterType={filterType}
              value={draft}
              onChange={setDraft}
              enumOptions={enumOptions}
            />
          </div>
          <button className="btn btn--primary"
                  onClick={addDraft}
                  disabled={!draft.raw.trim()}>
            ＋ Add
          </button>
        </div>
      )}

      {}
      <div className="value-list">
        {values.length === 0 ? (
          <div className="empty-state">The list is empty</div>
        ) : (
          <ul className="value-list__items">
            {values.map((v, idx) => (
              <li key={`${v.raw}-${idx}`} className="value-list__item">
                <span className="value-list__index">{idx + 1}.</span>
                <span className="value-list__display">
                  {v.display || v.raw}
                </span>
                {isRef && v.display !== v.raw && (
                  <span className="value-list__raw mono muted"
                        title={`ID: ${v.raw}`}>
                    {v.raw.length > 12 ? v.raw.slice(0, 8) + "…" : v.raw}
                  </span>
                )}
                <button className="icon-btn icon-btn--small"
                        onClick={() => removeAt(idx)}
                        title="Remove from list">✕</button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="vlp-footer">
        <span className="muted" style={{ fontSize: 12 }}>
          Total: <strong>{values.length}</strong>
        </span>
        {values.length > 0 && (
          <button className="btn btn--small btn--danger"
                  onClick={clearAll}
                  style={{ marginLeft: 6 }}>
            🗑 Clear all
          </button>
        )}
        <div style={{ flex: 1 }} />
        <button className="btn" onClick={onCancel}>Cancel</button>
        <button className="btn btn--primary" onClick={() => onConfirm(values)}>
          Confirm
        </button>
      </div>
    </div>
  );
}
