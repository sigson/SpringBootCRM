import type {
  ColumnFilterType, FilterValue, ReferenceSource, EnumOption,
} from "./filterTypes";
import { uaFromIso, isoFromUa } from "./filterTypes";
import { RefAutocompleteInput } from "./RefAutocompleteInput";
import { UnionFilterValueInput } from "./UnionFilterValueInput";

/**
 * Універсальне поле введення значення фільтра, типізоване під ColumnFilterType.
 *
 * <ul>
 *   <li><b>number</b>   → input[type=number, inputMode=decimal]</li>
 *   <li><b>date</b>     → input[type=date], зберігаємо у raw як "ДД.ММ.РРРР"</li>
 *   <li><b>boolean</b>  → select Так/Ні</li>
 *   <li><b>enum</b>     → select зі списком EnumOption</li>
 *   <li><b>reference</b> → {@link RefAutocompleteInput} — inline-автодоповнення
 *       по коду/найменуванню + кнопка «…» для відкриття повного picker'а зі своєю
 *       таблицею (з власними фільтрами/колонками). Picker рекурсивно підтримує
 *       reference-колонки (модал-над-модалом через WindowStack);</li>
 *   <li><b>string</b>   → input[type=text] (default)</li>
 * </ul>
 *
 * <p>На відміну від попередньої версії, для reference тепер відразу доступно
 * inline-введення коду/найменування з клавіатури — без необхідності
 * обов'язково відкривати picker. Picker залишається як швидкий шлях для
 * вибору з фільтрацією/сортуванням довідника.
 */
interface FilterValueInputProps {
  filterType: ColumnFilterType;
  value: FilterValue;
  onChange: (v: FilterValue) => void;
  enumOptions?: EnumOption[];
  refSource?: ReferenceSource;
  /** Для filterType="unionReference" — перелік допустимих типів (для [Т]-вибору). */
  refTypeIds?: number[];
  disabled?: boolean;
  autoFocus?: boolean;
}

export function FilterValueInput({
  filterType, value, onChange, enumOptions, refSource, refTypeIds, disabled, autoFocus,
}: FilterValueInputProps) {
  if (filterType === "number") {
    return (
      <input className="lv-input" type="number" inputMode="decimal"
             value={value.raw} disabled={disabled} autoFocus={autoFocus}
             onChange={e => onChange({ raw: e.target.value, display: e.target.value })}
             placeholder="Number…" />
    );
  }

  if (filterType === "date") {
    return (
      <input className="lv-input" type="date"
             value={isoFromUa(value.raw)} disabled={disabled} autoFocus={autoFocus}
             onChange={e => {
               const ua = uaFromIso(e.target.value);
               onChange({ raw: ua, display: ua });
             }}
             placeholder="dd.MM.yyyy" />
    );
  }

  if (filterType === "boolean") {
    return (
      <select className="lv-select" value={value.raw} disabled={disabled}
              onChange={e => {
                const v = e.target.value;
                onChange({
                  raw: v,
                  display: v === "true" ? "Yes" : (v === "false" ? "No" : ""),
                });
              }}>
        <option value="">— choose —</option>
        <option value="true">Yes</option>
        <option value="false">No</option>
      </select>
    );
  }

  if (filterType === "enum" && enumOptions) {
    return (
      <select className="lv-select" value={value.raw} disabled={disabled}
              onChange={e => {
                const opt = enumOptions.find(o => o.value === e.target.value);
                onChange({
                  raw: e.target.value,
                  display: opt?.label ?? e.target.value,
                });
              }}>
        <option value="">— choose —</option>
        {enumOptions.map(o => (
          <option key={o.value} value={o.value}>{o.label}</option>
        ))}
      </select>
    );
  }

  if (filterType === "unionReference" && refTypeIds && refTypeIds.length > 0) {
    return (
      <UnionFilterValueInput
        value={value}
        refTypeIds={refTypeIds}
        onChange={onChange}
        disabled={disabled}
        autoFocus={autoFocus}
      />
    );
  }

  if (filterType === "reference" && refSource) {
    return (
      <RefAutocompleteInput
        value={value.raw || null}
        displayValue={value.display || null}
        source={refSource}
        onChange={(picked) => onChange(picked
          ? { raw: picked.id, display: picked.display }
          : { raw: "", display: "" })}
        clearable
        disabled={disabled}
        autoFocus={autoFocus}
      />
    );
  }

  return (
    <input className="lv-input" type="text"
           value={value.raw} disabled={disabled} autoFocus={autoFocus}
           onChange={e => onChange({ raw: e.target.value, display: e.target.value })}
           placeholder="Value…" />
  );
}
