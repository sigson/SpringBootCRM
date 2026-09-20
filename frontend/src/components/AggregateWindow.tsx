import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import { useMetadata } from "../metadata/MetadataProvider";
import { useDisplayResolver } from "../metadata/DisplayResolver";
import { useHasWriteAccess } from "../auth/permissions";
import { FormField, CheckboxField } from "./Common";
import { RefField } from "./RefField";
import { UnionRefField } from "./UnionRefField";
import { useApiErrorHandler } from "./useApiErrorHandler";
import {
  getEditorPayloadAdapter, getTabularParts, type TabularPartHandle,
} from "../editors/registry";
import { GenericTabularPart } from "./GenericTabularPart";
import { referencesApi, validationApi } from "../api/endpoints";
import { useLiveFieldValidation, ValidationOverlay } from "./ValidationOverlay";
import type { FieldDescriptor, TypeDescriptor } from "../types/api";

/**
 * Generic field-based editor для агрегату — будує форму з метаданих
 * {@code TypeDescriptor.fields}. Fallback, коли для типу немає user-defined
 * редактора в {@code editors/registry}.
 *
 * Помилки сервера маршрутизуються через {@code useApiErrorHandler} (центральна
 * модалка для ACCESS_DENIED/VALIDATION; inline — з {@code envelope.fieldErrors}).
 * При створенні запису довідника поле {@code code} попередньо заповнюється з
 * {@code GET /api/references/{slug}/next-code}.
 */

export interface GenericAggregateEditorProps {
  typeId: number;
  id: string | null;
  mode?: "view" | "edit";
  /**
   * Заздалегідь отриманий код від {@code openTypeEditor}. Якщо переданий,
   * редактор не робить власний запит до {@code next-code} — це усуває
   * double-increment лічильника від StrictMode-double-mount.
   */
  prefetchedCode?: string | null;
  onClose: () => void;
  onSaved?: (savedId?: string) => void;
}

export function GenericAggregateEditor({
  typeId, id, mode = "edit", prefetchedCode, onClose, onSaved,
}: GenericAggregateEditorProps) {
  const { byTypeId, types } = useMetadata();
  const resolver = useDisplayResolver();
  const hasWrite = useHasWriteAccess();
  const handleApiError = useApiErrorHandler();

  const td = byTypeId(typeId);
  const writable = mode === "edit" && hasWrite(typeId);

  const [data, setData] = useState<Record<string, unknown> | null>(null);
  const [original, setOriginal] = useState<Record<string, unknown> | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [serverFieldErrors, setServerFieldErrors] = useState<Record<string, string>>({});
  const [displayOnly, setDisplayOnly] = useState(false);
  // Реквізити з бін-обмеженнями валідації — лише для них вмикаємо «живу» перевірку.
  const [constrainedFields, setConstrainedFields] = useState<Set<string>>(new Set());

  // Owned-колекції (ТЧ) для цього типу: авто-виявлені з метаданих (ownerTypeId ===
  // typeId + ownerListPath → GenericTabularPart) плюс явно зареєстровані
  // (registerTabularPart). Кожна має imperative-handle flush(ownerId), що
  // застосовується після збереження власника (1С-семантика staging'у).
  const ownedTds: TypeDescriptor[] = types.filter(
    t => t.isTabularPart && t.ownerTypeId === typeId && t.ownerListPath);
  const registeredParts = getTabularParts(typeId);
  const tabularCount = ownedTds.length + registeredParts.length;
  const tabularRefs = useRef<(TabularPartHandle | null)[]>([]);
  if (tabularRefs.current.length !== tabularCount) {
    tabularRefs.current = Array.from({ length: tabularCount },
      (_, i) => tabularRefs.current[i] ?? null);
  }

  // Основний шлях коду — prefetch у {@code openTypeEditor}. Якщо prefetch не дійшов,
  // редактор сам один раз дотягує next-code для нового запису довідника й підставляє
  // у порожнє «code». Guard через ref гарантує єдиний запит під StrictMode double-invoke.
  const codeFetchGuard = useRef(false);
  useEffect(() => {
    if (id != null) return;
    if (!td || !td.isReference) return;
    if (prefetchedCode) return;             // opener уже надав код — не дублюємо запит
    if (codeFetchGuard.current) return;
    codeFetchGuard.current = true;
    let cancelled = false;
    referencesApi.nextCode(td.slug)
      .then(r => {
        if (cancelled || !r?.code) return;
        setData(prev => {
          // не перетираємо вже введений користувачем код
          if (prev && (prev.code == null || prev.code === "")) {
            return { ...prev, code: r.code };
          }
          return prev;
        });
      })
      .catch(() => { /* graceful: лишаємо поле порожнім, користувач введе вручну */ });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, typeId, prefetchedCode]);

  // Які реквізити мають бін-обмеження — щоб вмикати «живу» валідацію лише для них.
  useEffect(() => {
    if (!td) return;
    let cancelled = false;
    validationApi.constraints(td.slug)
      .then(map => { if (!cancelled) setConstrainedFields(new Set(Object.keys(map ?? {}))); })
      .catch(() => { if (!cancelled) setConstrainedFields(new Set()); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [typeId]);

  useEffect(() => {
    if (!td) { setLoading(false); return; }
    if (id == null) {
      // Створення нового — порожня форма; код від prefetch'а (або порожній).
      const initial: Record<string, unknown> = prefetchedCode
        ? { code: prefetchedCode }
        : {};
      setData(initial);
      setOriginal(initial);
      setLoading(false);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const fetched = await api.get<Record<string, unknown>>(`${td.apiBase}/${id}`);
        if (!cancelled) {
          setData(fetched);
          setOriginal(fetched);
        }
      } catch (err) {
        if (!cancelled) {
          // Fallback: показуємо лише display з resolver'а (доступ до GET може бути обмежений)
          const r = resolver.resolve(typeId, id);
          if (r) {
            setData({ display: r.display, code: r.code, name: r.name });
            setDisplayOnly(true);
          } else {
            handleApiError(err);
          }
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [typeId, id]);

  if (!td) {
    return (
      <div>
        <p className="empty-state">Unknown aggregate type</p>
        <div className="hflex" style={{ marginTop: 12, justifyContent: "flex-end" }}>
          <button className="btn" onClick={onClose}>Close</button>
        </div>
      </div>
    );
  }
  if (loading) {
    return <div className="empty-state">Loading…</div>;
  }
  if (!data) return null;

  if (displayOnly) {
    return (
      <div>
        <div className="form-grid">
          <FormField label="Display" value={String(data.display ?? "—")}
                     onChange={() => {}} readOnly />
          {data.code != null && (
            <FormField label="Code" value={String(data.code)} onChange={() => {}} readOnly />
          )}
          {data.name != null && (
            <FormField label="Name" value={String(data.name)} onChange={() => {}} readOnly />
          )}
          {id != null && (
            <div className="muted" style={{ fontSize: 11 }}>
              ID: <span className="mono">{id}</span>
            </div>
          )}
        </div>
        <div className="hflex" style={{ marginTop: 16, justifyContent: "flex-end", gap: 6 }}>
          <button className="btn" onClick={onClose}>Close</button>
        </div>
      </div>
    );
  }

  async function onSave() {
    if (!td || !data || !writable) return;
    setSubmitting(true);
    setServerFieldErrors({});
    try {
      const adapter = getEditorPayloadAdapter(typeId);
      const hasTabularChanges = tabularRefs.current.some(h => h?.hasPendingChanges());

      // На оновлення йде повний набір редагованих полів (PUT = повна заміна
      // редагованої частини), щоб незмінені обов'язкові поля не зникали з тіла.
      // Якщо змінились лише ТЧ — все одно зберігаємо (щоб дати flush валідний ownerId).
      if (id != null && !hasChanges(td, data, original) && !hasTabularChanges) {
        onClose();
        return;
      }

      let savedId: string | null = id;
      if (id == null) {
        // Створення — повна форма (включно з readOnly-полями, що мають значення,
        // напр. prefetch'нутий «Код» довідника), пропущена через адаптер типу.
        const body = adapter
          ? adapter({ ...data }, { id, data, original })
          : data;
        const created = await api.post<{ id?: string }>(`${td.apiBase}`, body);
        savedId = created?.id ?? null;
      } else {
        const editable = buildEditablePayload(td, data, original);
        const body = adapter
          ? adapter(editable, { id, data, original })
          : editable;
        await api.put(`${td.apiBase}/${id}`, body);
      }

      // Власник збережений — застосовуємо staged-зміни табличних частин з
      // валідним ownerId (для нового запису — savedId, повернений create'ом).
      if (savedId != null) {
        for (const h of tabularRefs.current) {
          if (h?.hasPendingChanges()) await h.flush(savedId);
        }
      }

      setOriginal(data);
      onSaved?.(savedId ?? undefined);
    } catch (err) {
      const { fieldErrors } = handleApiError(err);
      setServerFieldErrors(fieldErrors);
    } finally {
      setSubmitting(false);
    }
  }

  function setField(name: string, value: unknown) {
    setData(prev => prev ? { ...prev, [name]: value } : prev);
    setServerFieldErrors(prev => {
      if (!Object.prototype.hasOwnProperty.call(prev, name)) return prev;
      const next = { ...prev };
      delete next[name];
      return next;
    });
  }

  return (
    <div>
      {id != null && (
        <div className="muted" style={{ marginBottom: 8, fontSize: 11 }}>
          <strong>{td.singularLabel}</strong> · ID:{" "}
          <span className="mono">{id}</span>
          {!writable && (
            <span className="tag" style={{ marginLeft: 8 }}>
              {mode === "view" ? "view" : "read-only"}
            </span>
          )}
        </div>
      )}
      <div className="form-grid">
        {td.fields
          .filter(f => !f.hiddenInForm)
          .map(f => {
            // Union-ссылка: значение хранится парой ключей ${name}TypeId/${name}Id
            // (как в DTO/Request, напр. CalendarEvent.subject → subjectTypeId/subjectId),
            // редактируется стандартным UnionRefField. Так union-поля тоже становятся
            // полностью generic — без специализированного редактора типа.
            if (f.kind === "REF" && ((f.refTypeIds && f.refTypeIds.length > 1) || f.anyReference)) {
              const tRaw = data[`${f.name}TypeId`];
              const iRaw = data[`${f.name}Id`];
              return (
                <UnionRefField
                  key={f.name}
                  label={f.label}
                  value={{
                    typeId: tRaw == null ? null : Number(tRaw),
                    id: iRaw == null ? null : String(iRaw),
                  }}
                  refTypeIds={f.refTypeIds}
                  anyReference={f.anyReference}
                  onChange={v => setData(prev => prev ? {
                    ...prev,
                    [`${f.name}TypeId`]: v?.typeId ?? null,
                    [`${f.name}Id`]: v?.id ?? null,
                  } : prev)}
                  readOnly={fieldReadOnly(f, id, writable)}
                  required={f.required}
                  description={f.description}
                  error={serverFieldErrors[f.name]
                    ?? serverFieldErrors[`${f.name}Id`]
                    ?? serverFieldErrors[`${f.name}TypeId`] ?? null}
                />
              );
            }
            return (
              <FieldEditor
                key={f.name}
                field={f}
                slug={td.slug}
                live={constrainedFields.has(f.name)}
                value={fieldRawValue(f, data)}
                error={serverFieldErrors[f.name]
                  ?? serverFieldErrors[refKeyOf(f, data, original)] ?? null}
                readOnly={fieldReadOnly(f, id, writable)}
                onChange={v => setField(
                  f.kind === "REF" ? refKeyOf(f, data, original) : f.name, v)}
              />
            );
          })}
      </div>

      {/* Owned-колекції (табличні частини). Авто-виявлені з метаданих — generic;
          далі — явно зареєстровані (за наявності). Рядки нового запису staging'аться
          в пам'яті і застосовуються flush()-ем ПІСЛЯ збереження власника. */}
      {ownedTds.map((otd, i) => (
        <GenericTabularPart
          key={`g-${otd.typeId}`}
          td={otd}
          ownerId={id}
          ref={(h: TabularPartHandle | null) => { tabularRefs.current[i] = h; }}
        />
      ))}
      {registeredParts.map((Part, j) => (
        <Part
          key={`r-${j}`}
          ownerId={id}
          ref={(h: TabularPartHandle | null) => {
            tabularRefs.current[ownedTds.length + j] = h;
          }}
        />
      ))}

      <div className="hflex" style={{ marginTop: 16, justifyContent: "flex-end", gap: 6 }}>
        <button className="btn" onClick={onClose} disabled={submitting}>
          {writable ? "Cancel" : "Close"}
        </button>
        {writable && (
          <button className="btn btn--primary"
                  onClick={() => void onSave()} disabled={submitting}>
            {submitting ? "Saving…" : "Save"}
          </button>
        )}
      </div>
    </div>
  );
}

/**
 * Канонічний ключ значення моно-ссилкового поля. DTO/Request домену зберігають
 * моно-ссилку як {@code <name>Id} (напр. поле-власник серіалізується як
 * {@code ownerId}), тоді як саме поле сутності зветься {@code <name>} (owner).
 * Підтримуємо обидві угоди: якщо у даних/оригіналі є {@code <name>Id} —
 * працюємо з ним (типовий випадок), інакше з {@code <name>}.
 */
function refKeyOf(
  f: FieldDescriptor,
  data: Record<string, unknown> | null,
  original: Record<string, unknown> | null,
): string {
  if (f.kind !== "REF") return f.name;
  const idKey = `${f.name}Id`;
  const has = (o: Record<string, unknown> | null) =>
    !!o && Object.prototype.hasOwnProperty.call(o, idKey);
  if (has(data) || has(original)) return idKey;
  if (data && Object.prototype.hasOwnProperty.call(data, f.name)) return f.name;
  return idKey;   // дефолт — угода <name>Id
}

/** Сире значення поля для рендеру (для моно-ссилки — по канонічному ключу). */
function fieldRawValue(f: FieldDescriptor, data: Record<string, unknown>): unknown {
  if (f.kind === "REF") {
    const idKey = `${f.name}Id`;
    return data[f.name] ?? data[idKey];
  }
  return data[f.name];
}

/** Нормалізація для порівняння: null/undefined/'' вважаємо рівними («порожньо»). */
function canon(v: unknown): unknown {
  if (v === undefined || v === null || v === "") return null;
  return v;
}

/** Чи відрізняються редаговані поля поточних даних від оригіналу. */
/**
 * Чи поле read-only у поточному режимі форми. {@code creatableOnly}-поля
 * (insert-only: code/username/INIT_ONCE) редаговані при створенні ({@code id==null})
 * і read-only при редагуванні.
 */
function fieldReadOnly(
  f: FieldDescriptor, id: string | null, writable: boolean,
): boolean {
  if (!writable) return true;
  if (f.readOnly) return true;
  if (f.creatableOnly && id != null) return true;   // лише при створенні
  return false;
}

/** Чи бере поле участь у PUT (update) тілі: редаговане саме в режимі редагування. */
function editableInEditMode(f: FieldDescriptor): boolean {
  if (f.hiddenInForm) return false;
  if (f.readOnly) return false;
  if (f.creatableOnly) return false;   // у PUT такі поля не йдуть
  return true;
}

function hasChanges(
  td: { fields: FieldDescriptor[] },
  data: Record<string, unknown>,
  original: Record<string, unknown> | null,
): boolean {
  for (const f of td.fields) {
    if (!editableInEditMode(f)) continue;
    if (f.kind === "REF" && f.refTypeIds && f.refTypeIds.length > 1) {
      if (canon(data[`${f.name}TypeId`]) !== canon(original?.[`${f.name}TypeId`])) return true;
      if (canon(data[`${f.name}Id`]) !== canon(original?.[`${f.name}Id`])) return true;
      continue;
    }
    if (f.kind === "REF") {
      const k = refKeyOf(f, data, original);
      if (canon(data[k]) !== canon(original?.[k])) return true;
      continue;
    }
    if (canon(data[f.name]) !== canon(original?.[f.name])) return true;
  }
  return false;
}

/**
 * Повне тіло запиту для оновлення: усі поля, редаговані саме в режимі
 * редагування ({@link editableInEditMode}) з поточними значеннями. Це робить PUT
 * повною заміною редагованої частини агрегата — обов'язкові поля завжди присутні.
 * {@code creatableOnly}-поля (insert-only) у PUT не входять.
 */
function buildEditablePayload(
  td: { fields: FieldDescriptor[] },
  data: Record<string, unknown>,
  original: Record<string, unknown> | null,
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const f of td.fields) {
    if (!editableInEditMode(f)) continue;
    if (f.kind === "REF" && f.refTypeIds && f.refTypeIds.length > 1) {
      out[`${f.name}TypeId`] = data[`${f.name}TypeId`] ?? null;
      out[`${f.name}Id`] = data[`${f.name}Id`] ?? null;
      continue;
    }
    if (f.kind === "REF") {
      const k = refKeyOf(f, data, original);
      out[k] = data[k] ?? null;
      continue;
    }
    out[f.name] = data[f.name] ?? null;
  }
  return out;
}

interface FieldEditorProps {
  field: FieldDescriptor;
  value: unknown;
  error: string | null;
  readOnly: boolean;
  onChange: (v: unknown) => void;
  /** Slug типу — для запиту інтерактивної валідації на бекенд. */
  slug: string;
  /** Чи має поле бін-обмеження (вмикає «живу» валідацію). */
  live: boolean;
}

function FieldEditor({ field, value, error, readOnly, onChange, slug, live }: FieldEditorProps) {
  // «Жива» валідація: вмикається лише для скалярних текст/числових полів, що
  // мають обмеження, і лише після першого «торкання» (щоб не лаяти порожнє поле
  // одразу при відкритті). Hook викликаємо беззастережно (правила хуків), а
  // вмикання керуємо прапором enabled.
  const [touched, setTouched] = useState(false);
  const supportsLive =
    field.kind === "TEXT" || field.kind === "CODE" || field.kind === "EMAIL"
    || field.kind === "NUMBER" || field.kind === "TEXTAREA";
  const stringValue = value == null ? "" : String(value);
  const liveResult = useLiveFieldValidation({
    slug,
    field: field.name,
    value: stringValue,
    enabled: live && supportsLive && !readOnly && touched,
  });
  const overlay = supportsLive ? <ValidationOverlay v={liveResult} /> : undefined;
  const invalid = supportsLive && liveResult.status === "invalid";
  const markTouched = () => { if (!touched) setTouched(true); };
  const handleChange = (v: unknown) => { markTouched(); onChange(v); };

  switch (field.kind) {
    case "TEXT":
    case "CODE":
    case "EMAIL":
      return (
        <FormField
          label={field.label}
          type={field.kind === "EMAIL" ? "email" : "text"}
          value={value == null ? "" : String(value)}
          onChange={handleChange}
          onBlur={markTouched}
          readOnly={readOnly}
          required={field.required}
          description={field.description}
          placeholder={field.placeholder ?? undefined}
          error={error}
          overlay={overlay}
          invalid={invalid}
        />
      );
    case "PASSWORD":
      return (
        <FormField
          label={field.label}
          type="password"
          value={value == null ? "" : String(value)}
          onChange={onChange}
          readOnly={readOnly}
          description={field.description ?? "Leave empty to keep it unchanged"}
          error={error}
        />
      );
    case "NUMBER":
      return (
        <FormField
          label={field.label}
          type="number"
          value={value == null ? "" : String(value)}
          onChange={s => handleChange(s === "" ? null : Number(s))}
          onBlur={markTouched}
          readOnly={readOnly}
          required={field.required}
          description={field.description}
          error={error}
          overlay={overlay}
          invalid={invalid}
        />
      );
    case "TEXTAREA":
      return (
        <FormField
          label={field.label}
          value={value == null ? "" : String(value)}
          onChange={handleChange}
          onBlur={markTouched}
          textarea rows={3}
          readOnly={readOnly}
          required={field.required}
          description={field.description}
          error={error}
          overlay={overlay}
          invalid={invalid}
        />
      );
    case "BOOLEAN":
      return (
        <CheckboxField
          label={field.label}
          value={Boolean(value)}
          onChange={onChange}
          readOnly={readOnly}
          description={field.description}
        />
      );
    case "DATE":
      // Date-only поле (Java LocalDate). Backend очікує ISO `yyyy-MM-dd` без
      // часу/таймзони, тож тут — нативний date-picker, а не datetime-local.
      return (
        <FormField
          label={field.label}
          type="date"
          value={value == null ? "" : String(value).slice(0, 10)}
          onChange={s => onChange(s === "" ? null : s)}
          readOnly={readOnly}
          required={field.required}
          description={field.description}
          error={error}
        />
      );
    case "DATETIME":
      return (
        <FormField
          label={field.label}
          type="datetime-local"
          value={value == null ? "" : String(value).slice(0, 16)}
          onChange={s => onChange(s.length >= 16 ? s + ":00Z" : s)}
          readOnly={readOnly}
          required={field.required}
          description={field.description}
          error={error}
        />
      );
    case "REF":
      // Union-ссылка / any-reference редактируется в <UnionRefField> (перехватывается выше
      // в основном рендере). Сюда такие поля не доходят, но проверяем для надёжности.
      if ((field.refTypeIds && field.refTypeIds.length > 1) || field.anyReference) {
        return (
          <span className="form-field__hint muted">
            «{field.label}» — {field.anyReference ? "reference to any type" : `union-reference (${field.refTypeIds.length} types)`}; is edited in a dedicated editor.
          </span>
        );
      }
      if (field.refTypeId == null) {
        return <span className="form-field__error">REF-field without refTypeId: {field.name}</span>;
      }
      return (
        <RefField
          label={field.label}
          value={value == null ? null : String(value)}
          refTypeId={field.refTypeId}
          onChange={onChange}
          readOnly={readOnly}
          required={field.required}
          description={field.description}
          error={error}
        />
      );
    default:
      return <span className="form-field__error">Unknown field type: {field.kind}</span>;
  }
}
