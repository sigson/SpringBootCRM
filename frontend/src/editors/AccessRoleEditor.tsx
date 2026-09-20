import { useEffect, useMemo, useState } from "react";
import { accessRolesApi, type AccessRoleDto, type AccessTemplateDto } from "./accessRole.api";
import type { TypeDescriptor } from "../types/api";
import { useMetadata } from "../metadata/MetadataProvider";
import {
  Alert, CheckboxField, FormField,
} from "../components/Common";
import { useFieldValidation, validators } from "../components/validation";
import { useApiErrorHandler } from "../components/useApiErrorHandler";
import type { TypeEditorProps } from "./registry";

/**
 * Editor для AccessRole. Як і {@link UserEditor}, не залежить від routing'у.
 *
 * <h3>Конструктор typeFlags — матриця:</h3>
 * Показуємо <b>єдину таблицю</b> з усіма доменними типами одразу
 * (включно з тими, на які доступів ще не призначено — порожні рядки).
 * Користувач просто розставляє галочки де треба; типи, для яких немає жодної
 * галочки, не потрапляють до підсумкового {@code typeFlags}.
 *
 * <p>Зверху над кожним стовпцем — кнопки «✓ Все» / «✕ Жодного» для
 * швидкого масового заповнення стовпця.
 */
export function AccessRoleEditor({ id, prefetchedCode, onClose, onSaved }: TypeEditorProps) {
  const { types } = useMetadata();
  const handleApiError = useApiErrorHandler();

  const [target, setTarget] = useState<AccessRoleDto | null>(null);
  const [loadingTarget, setLoadingTarget] = useState<boolean>(id != null);

  useEffect(() => {
    if (id == null) { setTarget(null); setLoadingTarget(false); return; }
    let cancelled = false;
    setLoadingTarget(true);
    (async () => {
      try {
        const r = await accessRolesApi.get(id);
        if (!cancelled) setTarget(r);
      } catch (err) {
        if (!cancelled) handleApiError(err);
      } finally {
        if (!cancelled) setLoadingTarget(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // Початковий код — з prefetchedCode (отриманий openTypeEditor'ом ДО mount'у).
  // Це усуває double-increment лічильника від StrictMode dev double-effect.
  const [code, setCode] = useState(prefetchedCode ?? "");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [enabled, setEnabled] = useState(true);
  const [tmpl, setTmpl] = useState<AccessTemplateDto>(
    { globalFlags: 0, typeFlags: {} }
  );
  const [submitting, setSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [codeTouched, setCodeTouched] = useState(false);

  // Real-time валідація коду довідника.
  const codeRT = useFieldValidation(code, [validators.referenceCode()]);
  const codeError = fieldErrors.code ?? (codeTouched ? codeRT : null);

  // Hydrate form з target після завантаження.
  useEffect(() => {
    if (target == null) return;
    setCode(target.code ?? "");
    setName(target.name ?? "");
    setDescription(target.description ?? "");
    setEnabled(target.enabled ?? true);
    setTmpl(target.accessTemplate ?? { globalFlags: 0, typeFlags: {} });
  }, [target?.id]);

  async function onSubmit() {
    setSubmitting(true);
    setFieldErrors({});
    try {
      let saved: AccessRoleDto;
      if (target == null) {
        saved = await accessRolesApi.create({
          code: code.trim(), name: name.trim(),
          description: description.trim() || undefined,
          accessTemplate: tmpl, enabled,
        });
      } else {
        saved = await accessRolesApi.update(target.id, {
          name: name.trim(),
          description: description.trim() || undefined,
          accessTemplate: tmpl, enabled,
        });
      }
      onSaved?.(saved.id);
    } catch (err) {
      const { fieldErrors: fe } = handleApiError(err);
      setFieldErrors(fe);
    } finally {
      setSubmitting(false);
    }
  }

  if (loadingTarget) {
    return <div className="empty-state">Loading…</div>;
  }

  return (
    <div>
      {target && (
        <div className="muted" style={{ marginBottom: 8, fontSize: 11 }}>
          ID: <span className="mono">{target.id}</span>
        </div>
      )}
      <div className="form-grid">
        <div className="form-grid form-grid--cols-2">
          <FormField label="Code" value={code} onChange={v => { setCode(v); setCodeTouched(true); setFieldErrors(s => { const n = {...s}; delete n.code; return n; }); }}
                     readOnly={target != null} required autoFocus
                     placeholder="ROL00001, MANAGER..."
                     description={target == null
                       ? "Generated automatically - editable"
                       : undefined}
                     error={codeError} />
          <CheckboxField label="Active" value={enabled} onChange={setEnabled} />
        </div>
        <FormField label="Title" value={name} onChange={v => { setName(v); setFieldErrors(s => { const n = {...s}; delete n.name; return n; }); }}
                   required error={fieldErrors.name} />
        <FormField label="Description" value={description} onChange={setDescription}
                   textarea rows={2} error={fieldErrors.description} />

        <div className="section-label">Global permissions</div>
        <Alert kind="info">
          Global permissions apply to <i>all</i> types at once. Usually used
          rarely - for admin roles with {`{ADMIN/ROOT}_{READ/WRITE}`}. For permissions on
          individual catalogs, use the matrix below.
        </Alert>
        <GlobalFlagsEditor
          value={tmpl.globalFlags}
          onChange={v => setTmpl({ ...tmpl, globalFlags: v })}
        />

        <div className="section-label">Catalog permissions</div>
        <Alert kind="info">
          Tick the boxes in the matrix below. The buttons under each heading
          of a column bulk-enable or reset the whole column. Catalogs for which
          which <b>any</b> is not ticked do not reach the final
          roles.
        </Alert>
        <TypeFlagsMatrix
          types={types}
          value={tmpl.typeFlags}
          onChange={tf => setTmpl({ ...tmpl, typeFlags: tf })}
        />
      </div>

      <div className="hflex" style={{ marginTop: 16, justifyContent: "flex-end", gap: 6 }}>
        <button className="btn" onClick={onClose} disabled={submitting}>
          Cancel
        </button>
        <button className="btn btn--primary" onClick={() => void onSubmit()}
                disabled={submitting || !!codeRT || code.trim() === "" || name.trim() === ""}>
          {submitting ? "Saving…" : "Save"}
        </button>
      </div>
    </div>
  );
}

const GLOBAL_FLAG_DEFS = [
  { bit: 1,  name: "READ",         label: "Basic read" },
  { bit: 2,  name: "WRITE_INSERT", label: "Create (insert)" },
  { bit: 64, name: "WRITE_UPDATE", label: "Edit + deletion (update/delete)" },
  { bit: 4,  name: "ADMIN_READ",   label: "Admin read - bypasses row-level filters" },
  { bit: 8,  name: "ADMIN_WRITE",  label: "Admin write - bypasses field-level READ_ONLY" },
  { bit: 16, name: "ROOT_READ",    label: "Root-read (all types, all fields)" },
  { bit: 32, name: "ROOT_WRITE",   label: "Root-record (full bypass)" },
];

function GlobalFlagsEditor({
  value, onChange,
}: { value: number; onChange: (v: number) => void }) {
  return (
    <div className="vflex" style={{ gap: 4 }}>
      {GLOBAL_FLAG_DEFS.map(f => (
        <label key={f.name} className="hflex" style={{ gap: 6, fontSize: 12 }}>
          <input
            type="checkbox"
            checked={(value & f.bit) !== 0}
            onChange={e => {
              onChange(e.target.checked ? value | f.bit : value & ~f.bit);
            }}
          />
          <span className="mono" style={{ minWidth: 110 }}>{f.name}</span>
          <span className="muted">{f.label}</span>
        </label>
      ))}
    </div>
  );
}

const TYPE_FLAG_BITS = [
  { bit: 1,  name: "READ",         label: "Read",       hint: "Sees records in the catalog" },
  { bit: 2,  name: "WRITE_INSERT", label: "Create",     hint: "Only adding new records (Permission 1)" },
  { bit: 64, name: "WRITE_UPDATE", label: "Edit",   hint: "Edit + deleting existing (Permission 2)" },
  { bit: 4,  name: "ADMIN_READ",   label: "Admin read", hint: "Bypasses row-level filters (Calendar: sees others')" },
  { bit: 8,  name: "ADMIN_WRITE",  label: "Admin write",   hint: "Bypasses field-level read-only (for system fields)" },
];

interface TypeFlagsMatrixProps {
  types: TypeDescriptor[];
  value: Record<string, number>;
  onChange: (v: Record<string, number>) => void;
}

/**
 * Показує весь список доменних типів одразу: рядок — справочник, колонка — право
 * (READ / WRITE_INSERT / WRITE_UPDATE / ADMIN_READ / ADMIN_WRITE). Користувач
 * розставляє галочки.
 *
 * <p>Над заголовком кожного стовпця — pair кнопок «✓ Все» / «✕ Жодного» для
 * швидкого вмикання/вимикання усіх флажків стовпця одразу.
 *
 * <p>Логіка «який тип залишиться в typeFlags» проста: рядки з нульовою сумою
 * бітів НЕ потрапляють у фінальний {@code value}. Таким чином немає різниці
 * між «не додав» та «додав і прибрав всі галочки» — обидва кейси дають
 * відсутність ключа у DTO.
 */
function TypeFlagsMatrix({ types, value, onChange }: TypeFlagsMatrixProps) {
  // Сортуємо за міткою — для стабільного UI порядку.
  const sortedTypes = useMemo(
    () => [...types].sort((a, b) => a.pluralLabel.localeCompare(b.pluralLabel)),
    [types],
  );

  function setBit(typeId: number, bit: number, on: boolean) {
    const key = String(typeId);
    const cur = value[key] ?? 0;
    const next = on ? cur | bit : cur & ~bit;
    const out = { ...value };
    if (next === 0) delete out[key];
    else out[key] = next;
    onChange(out);
  }

  /** Скільки галочок зараз стоїть у стовпці {@code bit}. */
  function columnCount(bit: number): number {
    let n = 0;
    for (const t of sortedTypes) {
      if (((value[String(t.typeId)] ?? 0) & bit) !== 0) n++;
    }
    return n;
  }

  /** Поставити/зняти галочки у всьому стовпці. */
  function setColumn(bit: number, on: boolean) {
    const out: Record<string, number> = { ...value };
    for (const t of sortedTypes) {
      const key = String(t.typeId);
      const cur = out[key] ?? 0;
      const next = on ? cur | bit : cur & ~bit;
      if (next === 0) delete out[key];
      else out[key] = next;
    }
    onChange(out);
  }

  // Підсумкова кількість справочників з ненульовою сумою бітів.
  const grantedTypesCount = useMemo(
    () => sortedTypes.filter(t => (value[String(t.typeId)] ?? 0) !== 0).length,
    [sortedTypes, value],
  );

  return (
    <div className="tf-matrix">
      <table className="data-table data-table--1c tf-matrix__table">
        <thead>
          <tr>
            <th style={{ minWidth: 200 }}>Catalog</th>
            {TYPE_FLAG_BITS.map(b => {
              const count = columnCount(b.bit);
              const all = count === sortedTypes.length;
              const none = count === 0;
              return (
                <th key={b.name} style={{ textAlign: "center", width: 110 }}
                    title={b.hint}>
                  <div className="vflex" style={{ alignItems: "center", gap: 2 }}>
                    <span>{b.label}</span>
                    <div className="hflex" style={{ gap: 2, fontSize: 10 }}>
                      <button
                        type="button"
                        className="btn btn--small"
                        style={{ padding: "1px 5px", minHeight: 0, lineHeight: 1.2 }}
                        onClick={() => setColumn(b.bit, true)}
                        disabled={all}
                        title="Enable for all catalogs"
                      >✓ All</button>
                      <button
                        type="button"
                        className="btn btn--small"
                        style={{ padding: "1px 5px", minHeight: 0, lineHeight: 1.2 }}
                        onClick={() => setColumn(b.bit, false)}
                        disabled={none}
                        title="Reset for all catalogs"
                      >✕ None</button>
                    </div>
                    <span className="muted mono" style={{ fontSize: 10 }}>
                      {count}/{sortedTypes.length}
                    </span>
                  </div>
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {sortedTypes.map(t => {
            const cur = value[String(t.typeId)] ?? 0;
            const hasAny = cur !== 0;
            return (
              <tr key={t.typeId}
                  className={hasAny ? "tf-matrix__row--granted" : undefined}>
                <td>
                  <span style={{ marginRight: 6 }}>{t.iconHint}</span>
                  <span style={{ fontWeight: hasAny ? 600 : 400 }}>
                    {t.pluralLabel}
                  </span>
                  <span className="muted mono" style={{ marginLeft: 6, fontSize: 10 }}>
                    typeId={t.typeId}
                  </span>
                </td>
                {TYPE_FLAG_BITS.map(b => (
                  <td key={b.name} style={{ textAlign: "center" }}>
                    <input
                      type="checkbox"
                      checked={(cur & b.bit) !== 0}
                      onChange={e => setBit(t.typeId, b.bit, e.target.checked)}
                      title={b.hint}
                    />
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
      <div className="muted" style={{ fontSize: 11, marginTop: 6 }}>
        Permissions assigned for {grantedTypesCount} of {sortedTypes.length} catalogs.
        Catalogs without a single tick are not stored in the role.
      </div>
    </div>
  );
}
