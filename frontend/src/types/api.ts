export type UUID = string;

export interface UserDto {
  /** "full" — the record is visible in full (self or admin); "basic" — personal fields are masked. */
  level: "full" | "basic";
  id: UUID;
  /** Довідниковий код (усі довідники мають code+name). */
  code: string;
  /** Довідникова назва (рекомендоване відображення в picker'ах). */
  name: string;
  username: string;
  email: string | null;
  displayName: string | null;
  enabled: boolean;
  /** Одна роль доступу (nullable — користувач може не мати ролі). */
  roleId: UUID | null;
  /** typeId цільового типу ролі (для universal reference resolver). */
  roleTypeId: number;
  accessGlobalFlags: number | null;
  accessTypeFlags: Record<string, number> | null;
  /** Призначений кастомізований інтерфейс (typeId=9300; null — дефолтний режим). */
  interfaceLayoutId?: string | null;
  /** UUID автора/коректора для прихованих колонок. */
  createdBy?: string | null;
  updatedBy?: string | null;
  /** Дата створення (ISO Instant) — джерело синтетичної колонки «Дата запису». */
  createdAt?: string | null;
}

export interface AuthResponse {
  token: string;
  user: UserDto;
}

export type FieldKind =
  | "TEXT" | "CODE" | "NUMBER" | "BOOLEAN"
  | "DATE" | "DATETIME" | "TEXTAREA"
  | "EMAIL" | "PASSWORD" | "REF";

export interface FieldDescriptor {
  name: string;
  label: string;
  kind: FieldKind;
  /** Legacy: первый/единственный целевой тип (для моно-ссылок). */
  refTypeId: number | null;
  /** Полный перечень целевых типов: 1 — моно-ссылка, ≥2 — union-ссылка. */
  refTypeIds: number[];
  /**
   * true — поле помечено маркером AnyReference (union-ссылка на любой тип). UI
   * распознаёт это как сигнал предложить выбор среди ВСЕХ подходящих типов;
   * refTypeIds при этом уже раскрыт бэкендом под полный перечень подходящих типов.
   */
  anyReference?: boolean;
  required: boolean;
  readOnly: boolean;
  /**
   * Поле можна задати лише при СТВОРЕННІ (insert дозволено, update — ні:
   * INIT_ONCE / insert-only). Generic-редактор робить таке поле редагованим у
   * формі створення (id == null) і read-only у формі редагування.
   */
  creatableOnly: boolean;
  hiddenInTable: boolean;
  hiddenInForm: boolean;
  maxLength: number | null;
  placeholder: string | null;
  description: string | null;
}

export interface TypeDescriptor {
  typeId: number;
  slug: string;
  singularLabel: string;
  pluralLabel: string;
  iconHint: string;
  apiBase: string;
  isReference: boolean;
  userCreatable: boolean;
  adminOnly: boolean;
  /** true — це таблична частина агрегата (редагується в контексті власника). */
  isTabularPart: boolean;
  /** typeId агрегата-власника для табличної частини (інакше null). */
  ownerTypeId: number | null;
  /**
   * Owner-scoped шлях списку/створення рядків ТЧ (плейсхолдер {@code {ownerId}}),
   * напр. {@code /api/users/{ownerId}/coefficients}. Порожній рядок — owner-scoped
   * рендеру немає. PUT/DELETE окремого рядка — через {@code apiBase}/{id}.
   */
  ownerListPath: string;
  displayPattern: string;
  fields: FieldDescriptor[];
  /**
   * Вид представлення типу (дзеркало backend TypeDescriptor.representation):
   *   • "STANDARD" — звичайний агрегат: список і вікно генеруються з {@link fields}
   *     (або переозначуються по {@code typeId} хуком);
   *   • "NONSTANDARD" — «вільний контролер» (SQL Workbench, генерація даних):
   *     гарантовано нестандартне представлення, без {@code fields}/generic-apiBase.
   *     Фронтенд мусить мати зареєстрований хук представлення по {@code typeId};
   *     інакше відкриття типу дає модалку 404.
   */
  representation: "STANDARD" | "NONSTANDARD";
}

export interface RefKey {
  typeId: number;
  id: string;
}

export interface ResolvedRef {
  typeId: number;
  id: string;
  display: string;
  code: string | null;
  name: string | null;
  accessible: boolean;
  /** UUID автора (createdBy) — для прихованої колонки «Автор». */
  createdBy?: string | null;
  /** UUID останнього коректора (updatedBy) — для колонки «Корректировка». */
  updatedBy?: string | null;
}

/** Категорія помилки. Дзеркалить domain.core.web.ErrorEnvelope.Kind. */
export type ErrorKind =
  | "ACCESS_DENIED"
  | "VALIDATION"
  | "NOT_FOUND"
  | "CONFLICT"
  | "BAD_REQUEST"
  | "UNAUTHORIZED"
  | "INTERNAL";

/** Структурований опис, яких прав не вистачило для дії. */
export interface PermissionRequirement {
  /** typeId агрегата ({@code null} для глобального права). */
  typeId: number | null;
  /** Людинозрозуміла назва типу (заповнюється backend'ом). */
  typeLabel: string | null;
  /** Маска {@code AccessFlags}, яку повинен мати користувач. */
  requiredFlags: number;
  /** Семантика: {@code REPO} / {@code FIELD} / {@code GLOBAL} / {@code INSTANCE}. */
  scope: "REPO" | "FIELD" | "GLOBAL" | "INSTANCE";
  /** Ім'я поля для {@code FIELD}-scope, інакше null. */
  fieldName: string | null;
}

/** Field-level помилка валідації. */
export interface FieldErrorDto {
  /** Ім'я поля; {@code null} для cross-field / form-wide помилок. */
  field: string | null;
  /** Повідомлення для відображення. */
  message: string;
}

/**
 * Єдиний JSON-shape усіх error-відповідей сервера.
 * Дзеркалить domain.core.web.ErrorEnvelope (з {@code @JsonInclude.NON_NULL}:
 * незаповнені поля приходять як {@code undefined}).
 */
export interface ErrorEnvelope {
  kind: ErrorKind;
  status: number;
  message: string;
  path?: string;
  /** Заповнено для {@code ACCESS_DENIED}. */
  requirements?: PermissionRequirement[];
  /** Заповнено для {@code VALIDATION}. */
  fieldErrors?: FieldErrorDto[];
}

/** @deprecated використовуйте {@link ErrorEnvelope}; alias для немігрованого коду. */
export interface ApiError extends ErrorEnvelope {
  /** legacy alias для {@code message}. */
  error?: string;
  /** legacy alias для першого {@code fieldErrors[0].field}. */
  field?: string;
}

export const AccessFlagBits = {
  READ:         1 << 0,
  WRITE_INSERT: 1 << 1,
  ADMIN_READ:   1 << 2,
  ADMIN_WRITE:  1 << 3,
  ROOT_READ:    1 << 4,
  ROOT_WRITE:   1 << 5,
  WRITE_UPDATE: 1 << 6,
} as const;

/**
 * Розкриває маску {@code AccessFlags} у людинозрозумілий список:
 *   {@code humanizeFlags(0x42)} → {@code ["WRITE_INSERT", "WRITE_UPDATE"]}
 */
export function humanizeFlags(mask: number): string[] {
  const out: string[] = [];
  if (mask & AccessFlagBits.READ)         out.push("READ");
  if (mask & AccessFlagBits.WRITE_INSERT) out.push("WRITE_INSERT");
  if (mask & AccessFlagBits.ADMIN_READ)   out.push("ADMIN_READ");
  if (mask & AccessFlagBits.ADMIN_WRITE)  out.push("ADMIN_WRITE");
  if (mask & AccessFlagBits.ROOT_READ)    out.push("ROOT_READ");
  if (mask & AccessFlagBits.ROOT_WRITE)   out.push("ROOT_WRITE");
  if (mask & AccessFlagBits.WRITE_UPDATE) out.push("WRITE_UPDATE");
  return out;
}

/** Людинозрозуміла назва біту: {@code READ → «Читання»}, тощо. */
export function flagLabel(name: string): string {
  switch (name) {
    case "READ":         return "Read";
    case "WRITE_INSERT": return "Create";
    case "WRITE_UPDATE": return "Edit / Delete";
    case "ADMIN_READ":   return "Admin: read";
    case "ADMIN_WRITE":  return "Admin: write";
    case "ROOT_READ":    return "Root: read";
    case "ROOT_WRITE":   return "Root: record";
    default:             return name;
  }
}
