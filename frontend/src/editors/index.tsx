import { type ReactNode } from "react";
import {
  registerEditor,
  registerPicker,
  registerEditorPayloadAdapter,
  type PickerConfig,
} from "./registry";
import { AccessRoleEditor } from "./AccessRoleEditor";
import type { AccessRoleDto, AccessTemplateDto } from "./accessRole.api";
import type {
  UserDto, TypeDescriptor,
} from "../types/api";
import { humanizeFlags, flagLabel } from "../types/api";
import type { Column } from "../components/ListView";
import { buildColumnsFromMetadata } from "../components/buildColumnsFromMetadata";

/**
 * Центр реєстрації редакторів та picker-конфігів. Модуль не експортує компонентів —
 * лише виконує side-effect реєстрації при імпорті (імпортується з {@code App.tsx},
 * щоб реєстри були заповнені до першого {@code useOpenTypeEditor()}).
 *
 * Колонки таблиць виводяться з {@link TypeDescriptor#fields} через
 * {@link buildColumnsFromMetadata}; тут лежать лише пейдж-специфічні кастомізації
 * (рендер статус-тегів, ролей тощо). Нове {@code @UiField}-поле зʼявляється у
 * таблицях автоматично.
 */

export const USER_TYPE_ID = 9001;

/**
 * Опції рендеру таблиці користувачів (та сама таблиця у двох контекстах: сторінка
 * {@code /users} — з resolver'ом і current user'ом; picker — без них).
 */
export interface UsersTableOptions {
  /** Резолвер display'у ролей (з DisplayResolverProvider). */
  resolveRoleDisplay: (roleId: string) => string | null;
  /** UUID поточного користувача — щоб відмітити рядок «я». */
  currentUserId: string | null;
  /** Загальний REF-резолвер (для audit-колонок «Автор»/«Корректировка»). */
  resolveRef?: (refTypeId: number, id: string) => string | null;
}

/**
 * Будує колонки таблиці користувачів з {@link TypeDescriptor#fields} ({@code @UiField}-поля
 * зʼявляються автоматично). Тут лише перевизначаємо рендер {@code username}
 * (підсвічення «я») та {@code enabled} (тег) і додаємо віртуальну колонку «Роль»
 * (резолвиться через DisplayResolver).
 */
export function buildUsersColumns(
  td: TypeDescriptor,
  opts: UsersTableOptions,
): Column<UserDto>[] {
  const { resolveRoleDisplay, currentUserId, resolveRef } = opts;

  // @ts-ignore
  return buildColumnsFromMetadata<UserDto>(td, {
    resolveRef,
    // Поле `role` (REF) виключаємо — рендеримо власну віртуальну колонку «Роль»
    // нижче (потрібен резолв display'у через DisplayResolver).
    excludeFields: ["access", "role", "passwordHash"],
    customRender: {
      username: (u) => (
        <span className="mono">
          {u.username}
          {u.id === currentUserId && (
            <span className="tag tag--admin" style={{ marginLeft: 6 }}></span>
          )}
        </span>
      ),
      code: (u) => <span className="mono">{u.code}</span>,
      enabled: (u) =>
        u.enabled
          ? <span className="tag tag--success">active</span>
          : <span className="tag tag--danger">disabled</span>,
    },
    customTextOf: {
      enabled: (u) => (u.enabled ? "active" : "disabled"),
    },
    extraColumns: [
      // Віртуальна колонка «Роль» — резолвимо display через DisplayResolver.
      {
        id: "role",
        header: "Role",
        width: "220px",
        filterType: "reference",
        refTypeId: ACCESS_ROLE_TYPE_ID,
        idOf: (u) => u.roleId ?? "",
        textOf: (u) =>
          u.roleId ? (resolveRoleDisplay(u.roleId) ?? "…") : "",
        render: (u) => {
          if (!u.roleId) return <span className="muted">—</span>;
          return (
            <span className="tag" title={u.roleId}>
              {resolveRoleDisplay(u.roleId) ?? "…"}
            </span>
          );
        },
      },
    ],
  });
}

export const ACCESS_ROLE_TYPE_ID = 9100;

/**
 * Колонки таблиці ролей доступу. Знову з метаданих — поля {@code code},
 * {@code name}, {@code description}, {@code enabled} прийдуть автоматично.
 * Додатково — віртуальна колонка «Доступи» зі summary глобальних флагів
 * і кількості перевизначень по типах.
 */
export function buildAccessRolesColumns(
  td: TypeDescriptor,
  resolveRef?: (refTypeId: number, id: string) => string | null,
): Column<AccessRoleDto>[] {
  // @ts-ignore
  return buildColumnsFromMetadata<AccessRoleDto>(td, {
    resolveRef,
    excludeFields: ["accessTemplate"],
    customRender: {
      code: (r) => <span className="mono">{r.code}</span>,
      enabled: (r) =>
        r.enabled
          ? <span className="tag tag--success">active</span>
          : <span className="tag tag--danger">disabled</span>,
    },
    customTextOf: {
      enabled: (r) => (r.enabled ? "active" : "disabled"),
    },
    extraColumns: [
      {
        id: "summary",
        header: "Access",
        width: "260px",
        textOf: (r) => summarizeAccessTemplate(r.accessTemplate),
        render: (r) => renderAccessSummary(r.accessTemplate),
      },
    ],
  });
}

function summarizeAccessTemplate(t: AccessTemplateDto): string {
  const globals = humanizeFlags(t.globalFlags ?? 0);
  const typeCount = Object.keys(t.typeFlags ?? {}).length;
  const parts: string[] = [];
  if (globals.length > 0) parts.push(globals.map(flagLabel).join(", "));
  if (typeCount > 0) parts.push(`+${typeCount} override${typeCount === 1 ? "" : "s"}`);
  return parts.join(" · ") || "—";
}

function renderAccessSummary(t: AccessTemplateDto): ReactNode {
  const globals = humanizeFlags(t.globalFlags ?? 0);
  const typeCount = Object.keys(t.typeFlags ?? {}).length;
  return (
    <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
      {globals.length === 0 && typeCount === 0 && (
        <span className="muted">—</span>
      )}
      {globals.map((g) => (
        <span key={g} className="tag" title={`global ${g}`}>
          {flagLabel(g)}
        </span>
      ))}
      {typeCount > 0 && (
        <span className="tag tag--accent" title="Count type-level overrides">
          +{typeCount} type{typeCount === 1 ? "" : ""}
        </span>
      )}
    </div>
  );
}

export const CALENDAR_EVENT_TYPE_ID = 5001;

let initialized = false;

/**
 * Реєструє всі редактори/picker-конфіги (ідемпотентний). Picker'и реєструються з
 * lazy-колонками ({@code columns: (td) => …}), тож колонки будуються в момент
 * рендеру, коли метадані вже завантажені.
 */
export function initEditors(): void {
  if (initialized) return;
  initialized = true;

  // Кастомну форму редагування мають лише AccessRole (конструктор ролей) та
  // InterfaceLayout (реєструється в listViews); решта — generic field-форма з
  // метаданих. User теж generic (GenericAggregateEditor): write-only пароль —
  // синтетичне PASSWORD-поле; ТЧ «Коефіцієнти» — авто; розбіжності REST-контракту
  // — payload-адаптер нижче.
  registerEditor(ACCESS_ROLE_TYPE_ID, AccessRoleEditor);

  // Generic-редактор шле плоске тіло; адаптуємо під CreateRequest/UpdateRequest.
  registerEditorPayloadAdapter(USER_TYPE_ID, (body, ctx) => {
    const out: Record<string, unknown> = { ...body };
    // На створенні «Активний» може бути undefined → за замовчуванням true.
    if (ctx.id == null && (out.enabled == null)) {
      out.enabled = true;
    }
    // Порожній пароль при оновленні = «не змінювати» → не шлемо ключ (інакше @Size(min=6)
    // відхилить порожній рядок).
    if (ctx.id != null && (out.password == null || out.password === "")) {
      delete out.password;
    }
    // Роль/інтерфейс на оновленні застосовуються лише з явними прапорцями (null без
    // прапорця = «не чіпати»). Виставляємо прапорець, коли значення змінилось.
    if (ctx.id != null) {
      const roleChanged =
        (ctx.original?.roleId ?? null) !== (ctx.data.roleId ?? null);
      if (roleChanged) { out.roleProvided = true; }
      else { delete out.roleId; }

      const ifaceChanged =
        (ctx.original?.interfaceLayoutId ?? null) !== (ctx.data.interfaceLayoutId ?? null);
      if (ifaceChanged) { out.interfaceLayoutProvided = true; }
      else { delete out.interfaceLayoutId; }
    }
    return out;
  });

  // Реєстрація не потрібна: GenericAggregateEditor авто-виявляє ТЧ за метаданими
  // (ownerTypeId === 9001 + ownerListPath) і рендерить через GenericTabularPart.

  // USER picker: повна таблиця користувачів. Резолвер ref'ів приходить у {@code ctx},
  // тож колонка «Роль» і audit-колонки показують display, а не UUID.
  const userPickerConfig: PickerConfig<UserDto> = {
    // fetchAll не задано → generic GET td.apiBase.
    getId: (u) => u.id,
    getDisplay: (u) =>
      u.code && u.name ? `${u.code} — ${u.name}` :
      u.displayName || u.username || u.id,
    columns: (td, ctx) => buildUsersColumns(td, {
      resolveRoleDisplay: (id) => ctx.resolveRef(ACCESS_ROLE_TYPE_ID, id),
      currentUserId: null,
      resolveRef: ctx.resolveRef,
    }),
    pickerTitle: "Pick a user",
    persistKey: "picker-users",
  };
  registerPicker(USER_TYPE_ID, userPickerConfig);

  const accessRolePickerConfig: PickerConfig<AccessRoleDto> = {
    // fetchAll не задано → generic GET td.apiBase.
    getId: (r) => r.id,
    getDisplay: (r) => (r.code ? `${r.code} — ${r.name}` : r.name),
    columns: (td, ctx) => buildAccessRolesColumns(td, ctx.resolveRef),
    pickerTitle: "Pick an access role",
    persistKey: "picker-access-roles",
  };
  registerPicker(ACCESS_ROLE_TYPE_ID, accessRolePickerConfig);
}
