import { useState } from "react";
import type { FilterValue } from "./filterTypes";
import { encodeUnionRef, decodeUnionRef } from "./filterTypes";
import { useMetadata } from "../metadata/MetadataProvider";
import { useReferenceSource } from "./useReferenceSource";
import { RefAutocompleteInput } from "./RefAutocompleteInput";

/**
 * Поле введення значення фільтра для <b>union</b>-ссилкової колонки
 * (filterType="unionReference") — повний аналог {@code UnionRefField} з форми
 * редагування, але для системи фільтрів.
 *
 * <p>Поведінка (1С-style, як і всюди для union-ссилок):
 * <ol>
 *   <li>Якщо тип ще не обрано — показується кнопка <b>[Т]</b>; клік відкриває
 *       список допустимих типів (refTypeIds).</li>
 *   <li>Після вибору типу — звичайний ref-autocomplete picker цього типу.</li>
 *   <li><b>[✕]</b> очищає значення І скидає обраний тип (знову потрібен [Т]).</li>
 * </ol>
 *
 * <p>Значення кодується у {@link FilterValue.raw} як {@code "<typeId>:<uuid>"}
 * (+ {@code typeId} окремо для відновлення стану). Бекенд за цим префіксом додає
 * дискримінатор {@code <col>_type_id = <typeId>} поряд з {@code <col>_id = <uuid>}.
 */
interface UnionFilterValueInputProps {
  value: FilterValue;
  refTypeIds: number[];
  onChange: (v: FilterValue) => void;
  disabled?: boolean;
  autoFocus?: boolean;
}

export function UnionFilterValueInput({
  value, refTypeIds, onChange, disabled, autoFocus,
}: UnionFilterValueInputProps) {
  const { byTypeId } = useMetadata();
  const [picking, setPicking] = useState(false);

  const decoded = decodeUnionRef(value.raw);
  const isUnion = refTypeIds.length > 1;
  // Якщо моно — тип фіксований. Якщо union — беремо з value (typeId або префікс raw).
  const effectiveType = isUnion
    ? (value.typeId ?? decoded?.typeId ?? null)
    : (refTypeIds[0] ?? null);

  const refSource = useReferenceSource(effectiveType);
  const targetType = effectiveType != null ? byTypeId(effectiveType) : null;
  const currentId = decoded?.id ?? (effectiveType != null && value.raw && !value.raw.includes(":") ? value.raw : null);

  const chooseType = (t: number) => {
    setPicking(false);
    onChange({ raw: "", display: "", typeId: t });
  };
  const clearAll = () => onChange({ raw: "", display: "", typeId: undefined });

  if (isUnion && effectiveType == null) {
    return (
      <div style={{ display: "flex", gap: 4, alignItems: "center" }}>
        <span className="muted" style={{ fontSize: 11, flex: 1 }}>no type selected</span>
        <button type="button" className="btn btn--small" title="Choose a value type"
                disabled={disabled} onClick={() => setPicking(true)}>T</button>
        {picking && (
          <UnionTypeMenu
            refTypeIds={refTypeIds}
            labelOf={(t) => byTypeId(t)?.singularLabel ?? `Type ${t}`}
            iconOf={(t) => byTypeId(t)?.iconHint ?? "📁"}
            onPick={chooseType}
            onClose={() => setPicking(false)}
          />
        )}
      </div>
    );
  }

  return (
    <div style={{ display: "flex", gap: 4, alignItems: "center", minWidth: 0 }}>
      {isUnion && targetType && (
        <span className="tag" title={targetType.singularLabel}
              style={{ whiteSpace: "nowrap" }}>{targetType.iconHint}</span>
      )}
      <div style={{ flex: 1, minWidth: 0 }}>
        {refSource && targetType ? (
          <RefAutocompleteInput
            value={currentId}
            displayValue={value.display || null}
            source={refSource}
            onChange={(picked) => onChange(picked
              ? { raw: encodeUnionRef(effectiveType, picked.id), display: picked.display, typeId: effectiveType ?? undefined }
              : { raw: "", display: "", typeId: effectiveType ?? undefined })}
            clearable={false}
            disabled={disabled}
            autoFocus={autoFocus}
            placeholder={`${targetType.singularLabel}…`}
          />
        ) : (
          <span className="muted" style={{ fontSize: 11 }}>type {effectiveType} is not registered</span>
        )}
      </div>
      {isUnion && !disabled && (
        <button type="button" className="btn btn--small btn--danger"
                title="Clear the value and reset the type" onClick={clearAll}>✕</button>
      )}
    </div>
  );
}

/** Випадаюче меню вибору типу union-варіанта. */
function UnionTypeMenu({
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
         style={{ position: "fixed", inset: 0, zIndex: 1300 }}>
      <div onClick={(e) => e.stopPropagation()}
           style={{ position: "absolute", right: 16, top: "30%",
                    background: "var(--surface,#fff)", color: "var(--text,#111)",
                    minWidth: 220, borderRadius: 8, padding: 8,
                    boxShadow: "0 12px 40px rgba(0,0,0,0.25)" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 6 }}>
          <strong style={{ fontSize: 13 }}>Value type</strong>
          <button type="button" className="btn btn--small" onClick={onClose}>✕</button>
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
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
