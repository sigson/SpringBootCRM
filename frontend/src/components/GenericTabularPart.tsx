import {
  forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState,
} from "react";
import { api } from "../api/client";
import type { FieldDescriptor, TypeDescriptor } from "../types/api";
import { FormField, CheckboxField } from "./Common";
import { RefField } from "./RefField";
import { UnionRefField, type UnionRefValue } from "./UnionRefField";
import { ListView, type Column, type RowAction } from "./ListView";
import {
  clientPagedSource, type ClientQueryColumn, type RowSource,
} from "./rowSource";
import { useApiErrorHandler } from "./useApiErrorHandler";
import { useLiveFieldValidation, ValidationOverlay } from "./ValidationOverlay";
import { validationApi } from "../api/endpoints";
import { useDisplayResolver } from "../metadata/DisplayResolver";
import { buildColumnsFromMetadata } from "./buildColumnsFromMetadata";
import { useWindowStack, nextWindowId } from "../windows/WindowStack";
import type { TabularPartHandle } from "../editors/registry";

/**
 * <h2>Узагальнена таблична частина (owned-колекція) за метаданими.</h2>
 *
 * <p>Жодних type-specific полів: колонки будуються {@link buildColumnsFromMetadata}
 * з {@code TypeDescriptor} самої ТЧ, форма рядка — з її редагованих полів, а
 * CRUD — за конвенцією метаданих:
 * <ul>
 *   <li>GET/POST списку — owner-scoped {@code td.ownerListPath} з підстановкою
 *       {@code {ownerId}} (напр. {@code /api/users/{id}/coefficients});</li>
 *   <li>PUT/DELETE рядка — {@code td.apiBase}/{rowId}.</li>
 * </ul>
 *
 * <p><b>Транзакційна семантика 1С</b> збережена: рядки редагуються лише у пам'яті
 * (staging), реальні зміни застосовуються одним пакетом у {@link TabularPartHandle.flush}
 * ПІСЛЯ збереження власника (валідний {@code ownerId}). Подання рядків — той самий
 * уніфікований {@link ListView}, що й усюди (фільтри/колонки/прокрутка/пошук), а
 * <b>введення/редагування рядка — у модальному вікні</b> ({@code WindowStack}) з
 * полями та кнопками «Скасувати»/«Зберегти» — так само, як редагування звичайних
 * агрегатів. Жодного окремого inline-редактора: уся логіка вводу уніфікована.
 *
 * <p>Розрахована на «прості» ТЧ (набір скалярних/ссилкових реквізитів), що покриває
 * наявні випадки. Union-ссилки у формі рядка <b>підтримуються</b> — редагуються тим
 * самим {@link UnionRefField}, що й у generic-редакторі агрегата (значення зберігається
 * парою ключів {@code <name>TypeId}/{@code <name>Id}).
 */

/** Робочий (in-memory) рядок ТЧ. {@code serverId=null} — ще не існує в БД. */
interface WorkingRow {
  key: string;
  serverId: string | null;
  /** Значення реквізитів за канонічними ключами DTO (напр. coefficientId, factor). */
  values: Record<string, unknown>;
}

function newKey(): string {
  try { return crypto.randomUUID(); }
  catch { return `tmp-${Date.now()}-${Math.random().toString(36).slice(2)}`; }
}

/** Канонічний ключ значення поля у DTO рядка: REF → {@code <name>Id}, інакше {@code <name>}. */
function rowKeyOf(f: FieldDescriptor): string {
  return f.kind === "REF" ? `${f.name}Id` : f.name;
}

/** Union-ссилка: REF з ≥2 цільовими типами (значення — пара {@code <name>TypeId}/{@code <name>Id}). */
function isUnionRef(f: FieldDescriptor): boolean {
  return f.kind === "REF" && Array.isArray(f.refTypeIds) && f.refTypeIds.length > 1;
}

/** DTO-ключі union-ссилки: тип + id (та сама конвенція, що й у generic-редакторі агрегата). */
function unionKeysOf(f: FieldDescriptor): { typeKey: string; idKey: string } {
  return { typeKey: `${f.name}TypeId`, idKey: `${f.name}Id` };
}

/**
 * Усі DTO-ключі значення реквізиту рядка. Для звичайних полів — один ключ
 * ({@link rowKeyOf}); для union-ссилок — пара {@code <name>TypeId}/{@code <name>Id}.
 * Використовується для копіювання значень із сервера та виявлення змін рядка.
 */
function rowKeysOf(f: FieldDescriptor): string[] {
  if (isUnionRef(f)) {
    const { typeKey, idKey } = unionKeysOf(f);
    return [typeKey, idKey];
  }
  return [rowKeyOf(f)];
}

/** Редаговані реквізити ТЧ (без службових/прихованих). */
function editableFields(td: TypeDescriptor): FieldDescriptor[] {
  return td.fields.filter(f => !f.hiddenInForm && !f.readOnly && !f.creatableOnly);
}

function canon(v: unknown): unknown {
  return v === undefined || v === null || v === "" ? null : v;
}

export interface GenericTabularPartProps {
  /** Дескриптор ТЧ (typeId з ownerTypeId === власник). */
  td: TypeDescriptor;
  /** UUID власника; {@code null} — власник ще не збережений (рядки в staging'у). */
  ownerId: string | null;
}

export const GenericTabularPart = forwardRef<TabularPartHandle, GenericTabularPartProps>(
function GenericTabularPart({ td, ownerId }, ref) {
  const handleApiError = useApiErrorHandler();
  const resolver = useDisplayResolver();

  const fields = useMemo(() => editableFields(td), [td]);
  const ownerListPath = useCallback((oid: string) =>
    td.ownerListPath.replace("{ownerId}", oid), [td.ownerListPath]);

  const [originalById, setOriginalById] =
    useState<Record<string, Record<string, unknown>>>({});
  const [working, setWorking] = useState<WorkingRow[]>([]);
  const [loading, setLoading] = useState<boolean>(ownerId != null);
  const [srcVersion, setSrcVersion] = useState(0);
  const bumpSource = () => setSrcVersion(v => v + 1);

  const workingRef = useRef(working);
  workingRef.current = working;
  const originalRef = useRef(originalById);
  originalRef.current = originalById;

  // Модальне введення/редагування рядка (через WindowStack — як у звичайних
  // агрегатів). Inline-форми немає.
  const { open, closeById } = useWindowStack();

  // Реквізити ТЧ з бін-обмеженнями — для «живої» валідації полів рядка.
  const [constrained, setConstrained] = useState<Set<string>>(new Set());
  useEffect(() => {
    let cancelled = false;
    validationApi.constraints(td.slug)
      .then(map => { if (!cancelled) setConstrained(new Set(Object.keys(map ?? {}))); })
      .catch(() => { if (!cancelled) setConstrained(new Set()); });
    return () => { cancelled = true; };
  }, [td.slug]);

  const loadFromServer = useCallback(async (oid: string) => {
    const dtos = await api.get<Record<string, unknown>[]>(ownerListPath(oid));
    const orig: Record<string, Record<string, unknown>> = {};
    const rows: WorkingRow[] = dtos.map(d => {
      const sid = String(d.id);
      const values: Record<string, unknown> = {};
      for (const f of fields) for (const k of rowKeysOf(f)) values[k] = d[k];
      orig[sid] = { ...values };
      return { key: newKey(), serverId: sid, values };
    });
    setOriginalById(orig);
    setWorking(rows);
    bumpSource();
  }, [fields, ownerListPath]);

  useEffect(() => {
    if (ownerId == null) {
      setWorking([]); setOriginalById({}); setLoading(false); bumpSource(); return;
    }
    let cancelled = false;
    setLoading(true);
    (async () => {
      try { if (!cancelled) await loadFromServer(ownerId); }
      catch (err) { if (!cancelled) handleApiError(err); }
      finally { if (!cancelled) setLoading(false); }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ownerId]);

  /** Застосувати значення рядка зі staged-форми: створити новий або оновити наявний. */
  function applyRow(editingKey: string | null, values: Record<string, unknown>) {
    setWorking(prev => {
      if (editingKey == null) {
        return [...prev, { key: newKey(), serverId: null, values }];
      }
      return prev.map(r => r.key === editingKey ? { ...r, values } : r);
    });
    bumpSource();
  }

  /** Відкрити модальне вікно вводу рядка (новий, якщо {@code row==null}). */
  function openRowEditor(row: WorkingRow | null) {
    const winId = nextWindowId();
    open({
      id: winId,
      title: row == null
        ? `New row: ${td.singularLabel}`
        : `Row: ${td.singularLabel}`,
      width: "default",
      content: (
        <TabularRowForm
          fields={fields}
          initial={row?.values ?? {}}
          slug={td.slug}
          constrained={constrained}
          onCancel={() => closeById(winId)}
          onSave={(values) => { applyRow(row?.key ?? null, values); closeById(winId); }}
        />
      ),
    });
  }

  function removeRow(r: WorkingRow) {
    setWorking(prev => prev.filter(x => x.key !== r.key));
    bumpSource();
  }

  function rowChanged(r: WorkingRow): boolean {
    if (r.serverId == null) return true;
    const o = originalById[r.serverId];
    if (!o) return true;
    return fields.some(f => rowKeysOf(f).some(k => canon(r.values[k]) !== canon(o[k])));
  }

  const computePending = useCallback((): boolean => {
    const cur = workingRef.current;
    const orig = originalRef.current;
    const present = new Set(cur.filter(r => r.serverId).map(r => r.serverId!));
    for (const id of Object.keys(orig)) if (!present.has(id)) return true;
    for (const r of cur) {
      if (r.serverId == null) return true;
      const o = orig[r.serverId];
      if (!o) return true;
      if (fields.some(f => rowKeysOf(f).some(k => canon(r.values[k]) !== canon(o[k])))) return true;
    }
    return false;
  }, [fields]);

  useImperativeHandle(ref, (): TabularPartHandle => ({
    hasPendingChanges: () => computePending(),
    flush: async (oid: string) => {
      const cur = workingRef.current;
      const orig = originalRef.current;
      const present = new Set(cur.filter(r => r.serverId).map(r => r.serverId!));
      try {
        // Видалення зниклих рядків.
        for (const id of Object.keys(orig)) {
          if (!present.has(id)) await api.delete(`${td.apiBase}/${id}`);
        }
        // Створення нових / оновлення змінених.
        for (const r of cur) {
          if (r.serverId == null) {
            await api.post(ownerListPath(oid), r.values);
          } else {
            const o = orig[r.serverId];
            if (!o || fields.some(f => rowKeysOf(f).some(k => canon(r.values[k]) !== canon(o[k])))) {
              await api.put(`${td.apiBase}/${r.serverId}`, r.values);
            }
          }
        }
      } catch (err) {
        try { await loadFromServer(oid); } catch { /* best-effort resync */ }
        throw err;
      }
    },
  }), [computePending, fields, loadFromServer, ownerListPath, td.apiBase]);

  const columns = useMemo<Column<WorkingRow>[]>(() => {
    // Будуємо колонки з метаданих ТЧ; рядок — це {@code r.values}, тож
    // адаптуємо акцесори, щоб брати значення з {@code r.values}.
    const meta = buildColumnsFromMetadata<Record<string, unknown>>(td, {
      resolveRef: (rtid, idv) => resolver.displayOf(rtid, idv),
    });
    const adapted: Column<WorkingRow>[] = meta.map(c => ({
      ...c,
      textOf: (r: WorkingRow) => c.textOf(r.values),
      idOf: c.idOf ? (r: WorkingRow) => c.idOf!(r.values) : undefined,
      render: c.render ? (r: WorkingRow) => c.render!(r.values) : undefined,
    }));
    // Колонка стану (новий/змінено) — суто UI, спільна для всіх ТЧ.
    adapted.push({
      id: "__status", header: "Status", width: "110px", filterType: "string",
      textOf: r => rowChanged(r) ? (r.serverId == null ? "new" : "changed") : "",
      render: r => {
        if (r.serverId == null) return <span className="tag">new</span>;
        if (rowChanged(r)) return <span className="tag">changed</span>;
        return <span className="muted">—</span>;
      },
    });
    return adapted;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [td, resolver, originalById]);

  const dataSource = useMemo<RowSource<WorkingRow>>(() => {
    const queryCols: ClientQueryColumn<WorkingRow>[] = columns.map(c => ({
      id: c.id,
      textOf: c.textOf,
      idOf: c.idOf,
      filterType: c.filterType === "unionReference" ? "reference" : c.filterType,
    }));
    return clientPagedSource<WorkingRow>({
      fetchAll: async () => workingRef.current,
      columns: queryCols,
      version: srcVersion,
    });
  }, [columns, srcVersion]);

  const rowActions: RowAction<WorkingRow>[] = [
    { label: "Edit", icon: "✏️", kind: "primary", onClick: r => openRowEditor(r) },
    { label: "Delete", icon: "🗑", kind: "danger", onClick: r => removeRow(r) },
  ];

  // Кнопка «＋ Додати» у тулбарі ListView — відкриває модальне вікно вводу рядка
  // (як «＋ Додати» у звичайних розділах-агрегатах).
  const toolbar = (
    <button className="btn btn--primary" onClick={() => openRowEditor(null)}>
      ＋ Add
    </button>
  );

  return (
    <div style={{ marginTop: 20, borderTop: "1px solid var(--border, #ddd)", paddingTop: 14 }}>
      <div className="section-label" style={{ marginBottom: 4 }}>
        {td.pluralLabel} (tabular part)
      </div>
      <div className="muted" style={{ fontSize: 11, marginBottom: 8 }}>
        Table changes apply only after pressing «Save» on the owner.
        «Cancel» discards them.
      </div>

      {loading ? (
        <div className="empty-state">Loading…</div>
      ) : (
        <ListView<WorkingRow>
          persistKey={`tabular-${td.typeId}`}
          dataSource={dataSource}
          columns={columns}
          rowId={r => r.key}
          rowActions={rowActions}
          onRowDoubleClick={r => openRowEditor(r)}
          emptyText="No rows"
          toolbar={toolbar}
        />
      )}
    </div>
  );
});

// Самодостатній редактор рядка з полями (за метаданими ТЧ) і кнопками
// «Скасувати»/«Зберегти» — уніфікований з вікном редагування агрегата. Працює
// з локальним станом і повертає нормалізовані значення через onSave.

interface TabularRowFormProps {
  fields: FieldDescriptor[];
  initial: Record<string, unknown>;
  /** Slug типу ТЧ — для «живої» валідації реквізитів рядка. */
  slug: string;
  /** Реквізити ТЧ, що мають бін-обмеження. */
  constrained: Set<string>;
  onCancel: () => void;
  onSave: (values: Record<string, unknown>) => void;
}

function TabularRowForm({ fields, initial, slug, constrained, onCancel, onSave }: TabularRowFormProps) {
  const [draft, setDraft] = useState<Record<string, unknown>>(() => ({ ...initial }));
  const [errors, setErrors] = useState<Record<string, string>>({});

  function setDraftField(k: string, v: unknown) {
    setDraft(d => ({ ...d, [k]: v }));
    setErrors(e => (e[k] ? { ...e, [k]: "" } : e));
  }

  /** Записати обидва ключі union-ссилки одним апдейтом і скинути їх помилки. */
  function setDraftUnion(typeKey: string, idKey: string, v: UnionRefValue | null) {
    setDraft(d => ({ ...d, [typeKey]: v?.typeId ?? null, [idKey]: v?.id ?? null }));
    setErrors(e => (e[idKey] || e[typeKey] ? { ...e, [idKey]: "", [typeKey]: "" } : e));
  }

  function validate(): Record<string, string> {
    const e: Record<string, string> = {};
    for (const f of fields) {
      if (isUnionRef(f)) {
        // Union: «обов'язкове» = обрано значення (id-ключ непорожній).
        const { idKey } = unionKeysOf(f);
        if (f.required && canon(draft[idKey]) == null) {
          e[idKey] = `Field «${f.label}» required`;
        }
        continue;
      }
      const k = rowKeyOf(f);
      const v = draft[k];
      if (f.required && canon(v) == null) {
        e[k] = `Field «${f.label}» required`;
      }
      if (f.kind === "NUMBER" && v != null && v !== "" && !Number.isFinite(Number(v))) {
        e[k] = "Enter a number";
      }
    }
    return e;
  }
  const valid = Object.keys(validate()).length === 0;

  function submit() {
    const e = validate();
    if (Object.keys(e).length) { setErrors(e); return; }
    const values: Record<string, unknown> = {};
    for (const f of fields) {
      if (isUnionRef(f)) {
        // Union: пишемо обидва ключі — typeId числом, id рядком (як у DTO агрегата).
        const { typeKey, idKey } = unionKeysOf(f);
        const tRaw = draft[typeKey];
        const iRaw = draft[idKey];
        values[typeKey] = tRaw == null || tRaw === "" ? null : Number(tRaw);
        values[idKey] = iRaw == null || iRaw === "" ? null : String(iRaw);
        continue;
      }
      const k = rowKeyOf(f);
      const v = draft[k];
      values[k] = f.kind === "NUMBER" && v != null && v !== "" ? Number(v) : (v ?? null);
    }
    onSave(values);
  }

  return (
    <div>
      <div className="form-grid">
        {fields.map(f => {
          // Union-ссилка: значення — пара ${name}TypeId/${name}Id, редагується тим
          // самим UnionRefField, що й у generic-редакторі агрегата.
          if (isUnionRef(f)) {
            const { typeKey, idKey } = unionKeysOf(f);
            const tRaw = draft[typeKey];
            const iRaw = draft[idKey];
            return (
              <UnionRefField
                key={f.name}
                label={f.label}
                value={{
                  typeId: tRaw == null ? null : Number(tRaw),
                  id: iRaw == null ? null : String(iRaw),
                }}
                refTypeIds={f.refTypeIds}
                onChange={v => setDraftUnion(typeKey, idKey, v)}
                error={errors[idKey] ?? errors[typeKey]}
                required={f.required}
                description={f.description ?? undefined}
              />
            );
          }
          const k = rowKeyOf(f);
          const v = draft[k];
          if (f.kind === "REF" && f.refTypeId != null) {
            return (
              <RefField
                key={f.name}
                label={f.label}
                value={v == null ? null : String(v)}
                refTypeId={f.refTypeId}
                onChange={id => setDraftField(k, id)}
                error={errors[k]}
                required={f.required}
                description={f.description ?? undefined}
              />
            );
          }
          if (f.kind === "BOOLEAN") {
            return (
              <CheckboxField key={f.name} label={f.label}
                value={Boolean(v)} onChange={b => setDraftField(k, b)}
                description={f.description ?? undefined} />
            );
          }
          return (
            <LiveTextInput
              key={f.name}
              field={f}
              slug={slug}
              live={constrained.has(f.name)}
              value={v == null ? "" : String(v)}
              error={errors[k]}
              onChange={s => setDraftField(k, s)}
            />
          );
        })}
      </div>

      <div className="hflex" style={{ marginTop: 16, justifyContent: "flex-end", gap: 6 }}>
        <button className="btn" onClick={onCancel}>Cancel</button>
        <button className="btn btn--primary" onClick={submit} disabled={!valid}>
          Save
        </button>
      </div>
    </div>
  );
}

/**
 * Скалярний інпут рядка ТЧ з «живою» валідацією та оверлей-панеллю — окремий
 * компонент, щоб коректно викликати hook валідації (правила хуків забороняють
 * виклик у циклі/умові всередині батьківського {@code map}).
 */
function LiveTextInput({ field, slug, live, value, error, onChange }: {
  field: FieldDescriptor; slug: string; live: boolean;
  value: string; error?: string; onChange: (v: string) => void;
}) {
  const [touched, setTouched] = useState(false);
  const liveResult = useLiveFieldValidation({
    slug, field: field.name, value, enabled: live && touched,
  });
  const markTouched = () => { if (!touched) setTouched(true); };
  return (
    <FormField
      label={field.label}
      type={field.kind === "NUMBER" ? "number"
        : field.kind === "EMAIL" ? "email"
        : (field.kind === "DATE" || field.kind === "DATETIME") ? "datetime-local"
        : "text"}
      value={value}
      onChange={s => { markTouched(); onChange(s); }}
      onBlur={markTouched}
      error={error}
      required={field.required}
      description={field.description ?? undefined}
      overlay={<ValidationOverlay v={liveResult} />}
      invalid={liveResult.status === "invalid"}
    />
  );
}
