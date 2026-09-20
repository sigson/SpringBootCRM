import { useState } from "react";
import type { ResolvedRef } from "../types/api";
import { useMetadata } from "../metadata/MetadataProvider";
import { useDisplayResolver, NO_ACCESS_LABEL } from "../metadata/DisplayResolver";
import { useReferenceSource } from "./useReferenceSource";
import { RefAutocompleteInput } from "./RefAutocompleteInput";
import { useOpenTypeEditor } from "../editors/openTypeEditor";
import type { RowAction } from "./ListView";

/**
 * Значение union-ссылочного поля: пара (typeId, id). Любое из полей может быть
 * {@code null} — это «тип ещё не выбран» / «значение ещё не выбрано».
 */
export interface UnionRefValue {
  typeId: number | null;
  id: string | null;
}

interface UnionRefFieldProps {
  label: string;
  value: UnionRefValue;
  /** Перечень допустимых типов (refTypeIds из метаданных поля). */
  refTypeIds: number[];
  /**
   * true — поле помечено маркером AnyReference: ссылка на любой тип. Пикер
   * предлагает выбор среди ВСЕХ подходящих типов. refTypeIds обычно уже раскрыт
   * бэкендом под полный перечень; если он пуст — берём все типы из метаданных.
   */
  anyReference?: boolean;
  onChange: (next: UnionRefValue | null) => void;
  readOnly?: boolean;
  required?: boolean;
  error?: string | null;
  description?: string | null;
}

/**
 * Поле ввода ссылочного значения для union-полей (1С-стиль). Моно-ссылка
 * ({@code refTypeIds.length===1}) — сразу обычный пикер. Union: пока тип не выбран —
 * кнопка [Т] открывает выбор типа, затем поле работает как ссылка выбранного типа.
 * Очистка [✕] сбрасывает и значение, и выбранный тип.
 */
export function UnionRefField({
  label, value, refTypeIds, anyReference, onChange,
  readOnly, required, error, description,
}: UnionRefFieldProps) {
  const { byTypeId, types } = useMetadata();
  const resolver = useDisplayResolver();
  const openTypeEditor = useOpenTypeEditor();
  const [picking, setPicking] = useState(false);

  // Эффективный перечень типов для выбора. Для any-reference, если бэкенд по
  // какой-то причине не раскрыл refTypeIds, берём все «выбираемые» типы из
  // метаданных (STANDARD, не табличные части) — маркер AnyReference означает
  // «любой тип», поэтому пикер показывает все.
  const effectiveTypeIds: number[] = (() => {
    if (refTypeIds && refTypeIds.length > 0) return refTypeIds;
    if (anyReference) {
      return types
        .filter(t => t.representation === "STANDARD" && t.ownerTypeId == null)
        .map(t => t.typeId);
    }
    return refTypeIds ?? [];
  })();

  // Union, если несколько целей ИЛИ маркер any-reference (выбор типа обязателен).
  const isUnion = !!anyReference || effectiveTypeIds.length > 1;
  const effectiveType = value.typeId ?? (isUnion ? null : effectiveTypeIds[0] ?? null);

  const refSource = useReferenceSource(effectiveType);
  const targetType = effectiveType != null ? byTypeId(effectiveType) : null;
  const resolved = effectiveType != null && value.id
    ? resolver.resolve(effectiveType, value.id) : null;

  // Нет доступа к выбранному значению — как и в обычной ссылке (RefField):
  // закрываем поле в read-only и показываем «[Нет доступа]».
  const noAccess = !!value.id && resolved?.accessible === false;
  const effectiveReadOnly = !!readOnly || noAccess;
  const effectiveDisplay = noAccess ? NO_ACCESS_LABEL : (resolved?.display ?? null);

  // Кнопка «→» (просмотр) — открывает форму ссылочного объекта в модальном стеке
  // (симметрично моно-ссылке RefField).
  const canDrillDown = !!value.id && effectiveType != null && resolved?.accessible !== false;
  const handleDrillDown = () => {
    if (effectiveType == null || !value.id) return;
    openTypeEditor({ typeId: effectiveType, id: value.id });
  };
  // Действие «🔍 Просмотр» внутри picker'а (drill-down из строки выбора).
  const pickerRowActions: RowAction<any>[] = [
    {
      label: "View",
      icon: "🔍",
      kind: "default",
      onClick: (item: any) => {
        if (effectiveType != null) openTypeEditor({ typeId: effectiveType, id: String(item.id) });
      },
    },
  ];

  const chooseType = (t: number) => { setPicking(false); onChange({ typeId: t, id: null }); };
  const clearAll = () => onChange(null);

  if (isUnion && effectiveType == null) {
    return (
      <label className="form-field ref-field">
        <span className="form-field__label">
          {label}{required && " *"}
          {anyReference && (
            <span className="tag" style={{ marginLeft: 8, fontWeight: 400, opacity: 0.8 }}>
              any type
            </span>
          )}
        </span>
        <div className="ref-field__row" style={{ display: "flex", gap: 6, alignItems: "center" }}>
          <span className="ref-field__value" style={{ flex: 1, opacity: 0.6 }}>
            <span className="ref-field__placeholder">
              {anyReference ? "No type selected - press [T] (any type)" : "No type selected - press [T]"}
            </span>
          </span>
          <button type="button" className="btn btn--small" title="Select a value type"
                  disabled={readOnly} onClick={() => setPicking(true)}>T</button>
        </div>
        {description && <span className="form-field__hint">{description}</span>}
        {error && <span className="form-field__error">{error}</span>}
        {picking && (
          <TypePickerModal
            refTypeIds={effectiveTypeIds}
            labelOf={(t) => byTypeId(t)?.singularLabel ?? `Type ${t}`}
            iconOf={(t) => byTypeId(t)?.iconHint ?? "📁"}
            onPick={chooseType}
            onClose={() => setPicking(false)}
          />
        )}
      </label>
    );
  }

  return (
    <label className="form-field ref-field">
      <span className="form-field__label">
        {label}{required && " *"}
        {isUnion && targetType && (
          <span className="tag" style={{ marginLeft: 8, fontWeight: 400 }}>
            {targetType.iconHint} {targetType.singularLabel}
          </span>
        )}
      </span>
      <div className="ref-field__row" style={{ display: "flex", gap: 6, alignItems: "center" }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          {refSource && targetType ? (
            <RefAutocompleteInput<ResolvedRef>
              value={value.id}
              displayValue={effectiveDisplay}
              source={refSource}
              onChange={(v) => onChange({ typeId: effectiveType, id: v?.id ?? null })}
              onDrillDown={canDrillDown ? handleDrillDown : undefined}
              pickerRowActions={pickerRowActions}
              clearable={false}
              readOnly={effectiveReadOnly}
              placeholder={`${targetType.singularLabel}: code or name…`}
            />
          ) : (
            <span className="ref-field__placeholder">Type {effectiveType} not registered</span>
          )}
        </div>
        {/* [✕] — очищает значение И сбрасывает выбранный тип (для union).
            При отсутствии доступа поле read-only — кнопку очистки тоже прячем. */}
        {!effectiveReadOnly && (
          <button type="button" className="btn btn--small btn--danger"
                  title={isUnion ? "Clear the value and reset the type" : "Clear value"}
                  onClick={clearAll}>✕</button>
        )}
      </div>
      {description && <span className="form-field__hint">{description}</span>}
      {error && <span className="form-field__error">{error}</span>}
    </label>
  );
}

/** Модальное окно выбора типа из перечня union-вариантов. */
function TypePickerModal({
  refTypeIds, labelOf, iconOf, onPick, onClose,
}: {
  refTypeIds: number[];
  labelOf: (t: number) => string;
  iconOf: (t: number) => string;
  onPick: (t: number) => void;
  onClose: () => void;
}) {
  return (
    <div onClick={onClose}
         style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.35)", zIndex: 1200,
                  display: "flex", alignItems: "center", justifyContent: "center" }}>
      <div onClick={(e) => e.stopPropagation()}
           style={{ background: "var(--surface, #fff)", color: "var(--text, #111)",
                    minWidth: 320, maxWidth: 480, borderRadius: 8, padding: 16,
                    boxShadow: "0 12px 40px rgba(0,0,0,0.25)" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
          <strong>Select a value type</strong>
          <button type="button" className="btn btn--small" onClick={onClose}>✕</button>
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          {refTypeIds.map((t) => (
            <button key={t} type="button" className="btn"
                    style={{ justifyContent: "flex-start", textAlign: "left" }}
                    onClick={() => onPick(t)}>
              <span style={{ marginRight: 8 }}>{iconOf(t)}</span>{labelOf(t)}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
