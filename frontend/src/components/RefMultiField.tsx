import { useCallback } from "react";
import type { ResolvedRef } from "../types/api";
import type { RowAction } from "./ListView";
import { useDisplayResolver, NO_ACCESS_LABEL } from "../metadata/DisplayResolver";
import { useMetadata } from "../metadata/MetadataProvider";
import { nextWindowId, useWindowStack } from "../windows/WindowStack";
import { useOpenTypeEditor } from "../editors/openTypeEditor";
import { useReferenceSource } from "./useReferenceSource";
import { RefAutocompleteInput } from "./RefAutocompleteInput";
import { ReferencePicker } from "./ReferencePicker";

/**
 * Поле множинного вибору ссилкових значень (1С-стиль).
 *
 * <p>Призначення: коли поле зберігає <em>масив</em> UUID посилань — призначені
 * користувачу ролі доступу, теги документу, перелік виконавців тощо.
 *
 * <h3>UI (перевага picker'а над автокомплітом):</h3>
 * <ul>
 *   <li><b>Chips</b> — кожен обраний елемент відображається як «таблетка» з
 *       display'ем; кліком на «✕» — видалення;</li>
 *   <li><b>Кнопка «📂 Обрати з довідника…»</b> — головна та найбільш помітна
 *       дія. Відкриває {@link ReferencePicker} у multi-режимі з повноцінною
 *       таблицею (з фільтрами, налаштуванням колонок, тощо — буквально та
 *       сама таблиця, що й на сторінці справочника) — звичний «вибір з
 *       довідника»;</li>
 *   <li><b>Швидкий ввід кодом/назвою</b> (під картинкою «📂») — компактний
 *       autocomplete для тих, хто пам'ятає код потрібного значення.
 *       Допоміжний, не основний шлях.</li>
 * </ul>
 *
 * <p>Drill-down «→» з chip'у тепер також відкриває повноцінний редактор
 * того ж типу (через {@link useOpenTypeEditor}), без виходу зі стеку.
 */

interface RefMultiFieldProps {
  label: string;
  /** Поточний масив UUID-посилань. */
  values: string[];
  /** TypeId доменного типу елементів. */
  refTypeId: number;
  /** Викликається при будь-якій зміні (нова повна множина). */
  onChange: (newIds: string[]) => void;
  readOnly?: boolean;
  description?: string | null;
  error?: string | null;
  /** Підказка-плейсхолдер у inline-полі вводу. */
  placeholder?: string;
}

export function RefMultiField({
  label, values, refTypeId, onChange,
  readOnly, description, error, placeholder,
}: RefMultiFieldProps) {
  const { byTypeId } = useMetadata();
  const resolver = useDisplayResolver();
  const refSource = useReferenceSource(refTypeId);
  const openTypeEditor = useOpenTypeEditor();
  const { open, closeById } = useWindowStack();

  const targetType = byTypeId(refTypeId);

  const handleAdd = useCallback((picked: { id: string; display: string } | null) => {
    if (!picked) return;
    if (values.includes(picked.id)) return;
    onChange([...values, picked.id]);
  }, [values, onChange]);

  const handleRemove = useCallback((id: string) => {
    onChange(values.filter(v => v !== id));
  }, [values, onChange]);

  function openMultiPicker() {
    if (!refSource) return;
    const winId = nextWindowId();

    // Дозволяємо drill-down по «🔍 Перегляд» прямо з picker'а.
    const extraRowActions: RowAction<any>[] = [
      {
        label: "View", icon: "🔍", kind: "default",
        onClick: (item: any) => openTypeEditor({
          typeId: refTypeId, id: String(item.id),
        }),
      },
    ];

    open({
      id: winId,
      title: `Select: ${targetType?.pluralLabel ?? "value"}`,
      width: "wide",
      content: (
        <ReferencePicker<any>
          source={refSource as any}
          multi={true}
          initialSelectedIds={values}
          extraRowActions={extraRowActions}
          onConfirm={(picked) => {
            onChange(picked.map(p => p.id));
            closeById(winId);
          }}
          onCancel={() => closeById(winId)}
        />
      ),
    });
  }

  if (!targetType || !refSource) {
    return (
      <label className="form-field ref-multi">
        <span className="form-field__label">{label}</span>
        <div className="ref-multi__row">
          <span className="ref-field__placeholder">
            Type {refTypeId} is not registered
          </span>
        </div>
        {error && <span className="form-field__error">{error}</span>}
      </label>
    );
  }

  return (
    <label className="form-field ref-multi">
      <span className="form-field__label">{label}</span>

      {/* Chips з обраних значень. Drill-down «→» по chip'у — як у RefField. */}
      {values.length > 0 ? (
        <ul className="ref-multi__chips">
          {values.map(id => {
            const resolved = resolver.resolve(refTypeId, id);
            // Немає доступу до елемента — показуємо «[Нет доступа]» і ховаємо дії
            // «→»/«✕» (взаємодіяти все одно нема сенсу). Видалити з набору все ще
            // можна лише якщо поле в цілому редаговане і доступ є.
            const noAccess = resolved?.accessible === false;
            return (
              <li key={id} className="ref-multi__chip" title={id}>
                {!noAccess && resolved?.code && (
                  <span className="ref-multi__chip-code mono">{resolved.code}</span>
                )}
                <span className="ref-multi__chip-text">
                  {noAccess ? NO_ACCESS_LABEL : (resolved?.name ?? resolved?.display ?? "…")}
                </span>
                {!readOnly && !noAccess && (
                  <>
                    <button
                      type="button"
                      className="ref-multi__chip-open"
                      onClick={() => openTypeEditor({
                        typeId: refTypeId, id,
                      })}
                      title="Open item"
                      aria-label="Open"
                    >→</button>
                    <button
                      type="button"
                      className="ref-multi__chip-remove"
                      onClick={() => handleRemove(id)}
                      title="Remove from list"
                      aria-label="Delete"
                    >✕</button>
                  </>
                )}
              </li>
            );
          })}
        </ul>
      ) : (
        <div className="ref-multi__empty muted">— nothing selected —</div>
      )}

      {!readOnly && (
        <div className="ref-multi__add-row" style={{ gap: 8, alignItems: "stretch" }}>
          {}
          <button
            type="button"
            className="btn btn--primary"
            onClick={openMultiPicker}
            title="Open the catalog table and tick the checkboxes"
            style={{ whiteSpace: "nowrap" }}
          >📂 Pick from catalog…</button>

          {}
          <div style={{ flex: 1 }}>
            <RefAutocompleteInput<ResolvedRef>
              key={`add-${values.length}`}
              value={null}
              displayValue={null}
              source={refSource as any}
              onChange={handleAdd}
              clearable={false}
              excludeIds={values}
              placeholder={placeholder
                ?? `Quick code/name entry…`}
            />
          </div>
        </div>
      )}

      {description && <span className="form-field__hint">{description}</span>}
      {error && <span className="form-field__error">{error}</span>}
    </label>
  );
}
