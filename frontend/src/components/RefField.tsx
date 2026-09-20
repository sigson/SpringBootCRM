import { useCallback } from "react";
import type { ResolvedRef } from "../types/api";
import { useDisplayResolver, NO_ACCESS_LABEL } from "../metadata/DisplayResolver";
import { useMetadata } from "../metadata/MetadataProvider";
import { useOpenTypeEditor } from "../editors/openTypeEditor";
import { useReferenceSource } from "./useReferenceSource";
import { RefAutocompleteInput } from "./RefAutocompleteInput";
import type { RowAction } from "./ListView";

/**
 * Поле вибору ссилкового значення (1С-стиль).
 *
 * <h3>Кнопки у полі:</h3>
 * <ul>
 *   <li><b>«…»</b> — відкриває повноцінний {@link ReferencePicker} (таблицю
 *       довідника з усіма фільтрами/налаштуваннями колонок) у субокні. Picker
 *       сам може містити reference-колонки → рекурсивна глибина модальних
 *       вікон через WindowStack;</li>
 *   <li><b>«→»</b> — відкриває обраний агрегат у субокні (через
 *       {@link useOpenTypeEditor}) — той самий редактор, що й сторінка типу,
 *       але модально, без зміни URL;</li>
 *   <li><b>«✕»</b> — очищує поле (тільки якщо не {@code required}).</li>
 * </ul>
 */

interface RefFieldProps {
  label: string;
  /** UUID поточного значення. */
  value: string | null;
  /** TypeId доменного типу. */
  refTypeId: number;
  /** Викликається при виборі нового значення (або null = очистити). */
  onChange: (newId: string | null) => void;
  readOnly?: boolean;
  required?: boolean;
  error?: string | null;
  description?: string | null;
}

export function RefField({
  label, value, refTypeId, onChange,
  readOnly, required, error, description,
}: RefFieldProps) {
  const { byTypeId } = useMetadata();
  const resolver = useDisplayResolver();
  const openTypeEditor = useOpenTypeEditor();
  const refSource = useReferenceSource(refTypeId);

  const targetType = byTypeId(refTypeId);
  const resolved = value ? resolver.resolve(refTypeId, value) : null;

  // Якщо ссилкове значення є, але користувач не має до нього доступу —
  // закриваємо поле в read-only й показуємо «[Нет доступа]» замість display'у
  // (і замість нескінченного «Завантаження…»). Взаємодіяти все одно нема сенсу:
  // ані переглянути об'єкт, ані відкрити picker (на тип теж нема прав).
  const noAccess = !!value && resolved?.accessible === false;
  const effectiveReadOnly = readOnly || noAccess;
  const effectiveDisplay = noAccess ? NO_ACCESS_LABEL : (resolved?.display ?? null);

  // Кнопка «→» drill-down — відкриває повноцінний редактор того ж типу
  // (User/AccessRole/...) у модальному стеку. Жодних змін URL.
  const handleDrillDown = useCallback(() => {
    if (!value) return;
    openTypeEditor({ typeId: refTypeId, id: value });
  }, [value, refTypeId, openTypeEditor]);

  const canDrillDown = !!value && resolved?.accessible !== false;

  // Додаткова дія «🔍 Перегляд» у picker'і — drill-down з picker'а.
  // Зверніть увагу: тут ResolvedRef, але у нашому новому picker'і (з registry)
  // приходить UserDto/AccessRoleDto. Тип <ResolvedRef> тут — найбільш загальний;
  // ми використовуємо лише {@code item.id}, що є у всіх типах.
  const pickerRowActions: RowAction<any>[] = [
    {
      label: "View",
      icon: "🔍",
      kind: "default",
      onClick: (item: any) => {
        openTypeEditor({ typeId: refTypeId, id: String(item.id) });
      },
    },
  ];

  // Якщо тип невідомий або джерело не зібралося — показуємо плейсхолдер з помилкою.
  if (!targetType || !refSource) {
    return (
      <label className="form-field ref-field">
        <span className="form-field__label">{label}{required && " *"}</span>
        <div className="ref-field__row">
          <span className="ref-field__value">
            <span className="ref-field__placeholder">
              Type {refTypeId} is not registered
            </span>
          </span>
        </div>
        {error && <span className="form-field__error">{error}</span>}
      </label>
    );
  }

  return (
    <label className="form-field ref-field">
      <span className="form-field__label">{label}{required && " *"}</span>
      <RefAutocompleteInput<ResolvedRef>
        value={value}
        displayValue={effectiveDisplay}
        source={refSource as any}
        onChange={(v) => onChange(v?.id ?? null)}
        onDrillDown={canDrillDown ? handleDrillDown : undefined}
        clearable={!required}
        readOnly={effectiveReadOnly}
        pickerRowActions={pickerRowActions}
        placeholder={`${targetType.singularLabel}: code or name…`}
      />
      {description && <span className="form-field__hint">{description}</span>}
      {error && <span className="form-field__error">{error}</span>}
    </label>
  );
}
