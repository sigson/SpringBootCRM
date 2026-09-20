import { api } from "./client";
import type {
  AuthResponse,
  RefKey,
  ResolvedRef,
  TypeDescriptor,
  UUID,
  UserDto,
} from "../types/api";

export const authApi = {
  login: (username: string, password: string) =>
    api.post<AuthResponse>("/api/auth/login", { username, password }),
  register: (username: string, email: string, displayName: string, password: string) =>
    api.post<AuthResponse>("/api/auth/register",
      { username, email, displayName, password }),
  logout: () => api.post<void>("/api/auth/logout"),
  /**
   * Поточний користувач — канонічна self-проекція ({@link UserDto}) через
   * {@code /api/users/me}. {@code silentOn401: true}: на першому mount'і
   * AuthProvider'а сесії може ще не бути, тож 401 має пройти тихо.
   */
  me: () => api.get<UserDto>("/api/users/me", { silentOn401: true }),
};

export const usersApi = {
  list: () => api.get<UserDto[]>("/api/users"),
  get: (id: UUID) => api.get<UserDto>(`/api/users/${id}`),
  create: (req: { code?: string; name?: string; username: string; email: string;
                   displayName: string; password: string; enabled: boolean;
                   roleId?: UUID | null }) =>
    api.post<UserDto>("/api/users", req),
  updateAdmin: (id: UUID, req: { name?: string; email?: string; displayName?: string;
                                  password?: string; enabled?: boolean;
                                  /** Нове значення ролі (null = зняти, якщо roleProvided). */
                                  roleId?: UUID | null;
                                  /** true — оновити роль; відсутній/false — не чіпати. */
                                  roleProvided?: boolean;
                                  /** Нове значення інтерфейсу (null = зняти, якщо interfaceLayoutProvided). */
                                  interfaceLayoutId?: UUID | null;
                                  /** true — оновити призначений інтерфейс; відсутній/false — не чіпати. */
                                  interfaceLayoutProvided?: boolean }) =>
    api.put<UserDto>(`/api/users/${id}`, req),
  patchSelf: (req: { email?: string; displayName?: string; password?: string }) =>
    api.patch<UserDto>("/api/users/me", req),
  delete: (id: UUID) => api.delete<void>(`/api/users/${id}`),
};

// Конверт сторінкової відповіді — частина протоколу (не бізнес-об'єкт).
// Використовується generic-механізмами (references.listPaged тощо).

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  total: number;
}

/**
 * Спільний форматер query-параметрів для paged-endpoint'ів. Сервер очікує:
 * <ul>
 *   <li>{@code search} — глобальний пошук;</li>
 *   <li>{@code cf_<columnId>} — quick-фільтри по колонках;</li>
 *   <li>{@code af_<columnId>_<op>} — advanced-фільтри (для in/nin — pipe);</li>
 *   <li>{@code sortBy}/{@code sortDir} — сортування.</li>
 * </ul>
 *
 * <p>Виділено в один хелпер, щоб контракт парсингу був однаковий для всіх
 * клієнтських викликів і збігався з серверним розборщиком (calendar + references).
 */
function appendPagedQuery(
  params: URLSearchParams,
  opts?: { search?: string;
           columnFilters?: Record<string, string>;
           advanced?: { columnId: string; op: string;
                        value?: unknown; values?: unknown[] }[];
           sort?: { columnId: string; dir: "asc" | "desc" } | null;
           sorts?: { columnId: string; dir: "asc" | "desc" }[] }
): void {
  if (!opts) return;
  if (opts.search && opts.search.trim()) params.set("search", opts.search.trim());
  if (opts.columnFilters) {
    for (const [k, v] of Object.entries(opts.columnFilters)) {
      if (v && v.trim()) params.set(`cf_${k}`, v.trim());
    }
  }
  if (opts.advanced) {
    for (const f of opts.advanced) {
      if (!f.columnId || !f.op) continue;
      const key = `af_${f.columnId}_${f.op}`;
      if (Array.isArray(f.values)) {
        const joined = f.values
          .map(v => {
            if (v == null) return "";
            if (typeof v === "object" && "raw" in (v as object)) {
              return String((v as { raw: unknown }).raw ?? "");
            }
            return String(v);
          })
          .filter(Boolean)
          .join("|");
        if (joined) params.set(key, joined);
      } else if (f.value != null) {
        const v = f.value;
        const raw = (typeof v === "object" && "raw" in (v as object))
          ? String((v as { raw: unknown }).raw ?? "")
          : String(v);
        if (raw !== "" || f.op === "empty" || f.op === "notEmpty") {
          params.set(key, raw);
        }
      } else if (f.op === "empty" || f.op === "notEmpty") {
        params.set(key, "");
      }
    }
  }
  // Сортування: пріоритет opts.sorts → opts.sort. Сервер парсить comma-separated.
  const sortList = (opts.sorts && opts.sorts.length > 0)
    ? opts.sorts
    : (opts.sort ? [opts.sort] : []);
  if (sortList.length > 0) {
    params.set("sortBy", sortList.map(s => s.columnId).join(","));
    params.set("sortDir", sortList.map(s => s.dir).join(","));
  }
}

export const metadataApi = {
  types: () => api.get<TypeDescriptor[]>("/api/metadata/types"),
  typeBySlug: (slug: string) => api.get<TypeDescriptor>(`/api/metadata/types/${slug}`),
};

export const referencesApi = {
  /** Batch-resolve UUID-посилань на читабельні строки. */
  resolve: (keys: RefKey[]) =>
    api.post<ResolvedRef[]>("/api/references/resolve", keys),
  /** Список доступних значень одного типу (для picker'а). */
  list: (slug: string, query?: string) =>
    api.get<ResolvedRef[]>(
      `/api/references/${slug}${query ? `?q=${encodeURIComponent(query)}` : ""}`),
  /** Сторінковий список (уніфікований chunked-API для списків/пікерів). */
  listPaged: (slug: string, page: number, size: number,
              opts?: { search?: string;
                       columnFilters?: Record<string, string>;
                       advanced?: { columnId: string; op: string;
                                    value?: unknown; values?: unknown[] }[];
                       sort?: { columnId: string; dir: "asc" | "desc" } | null;
                       sorts?: { columnId: string; dir: "asc" | "desc" }[];
                       /** false → бекенд не рахує total (count(...) пропускається). */
                       withCount?: boolean }) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    appendPagedQuery(params, opts);
    if (opts?.withCount === false) params.set("count", "false");
    return api.get<PageResponse<ResolvedRef>>(
      `/api/references/${slug}/page?${params.toString()}`);
  },
  /**
   * Сторінковий список <b>повних рядків</b> обʼєкта (для generic-{@code ObjectList}):
   * серверна фільтрація/пошук/багатоколоночне сортування/сторінкування. Повертає
   * проєкцію по всіх UI-полях типу ({@code row[field.name]}), а не {@code ResolvedRef}.
   * Контракт query-параметрів — той самий, що в {@link listPaged}.
   */
  rowsPaged: (slug: string, page: number, size: number,
              opts?: { search?: string;
                       columnFilters?: Record<string, string>;
                       advanced?: { columnId: string; op: string;
                                    value?: unknown; values?: unknown[] }[];
                       sort?: { columnId: string; dir: "asc" | "desc" } | null;
                       sorts?: { columnId: string; dir: "asc" | "desc" }[];
                       /** false → бекенд не рахує total (count(...) пропускається). */
                       withCount?: boolean;
                       /** Keyset-якір: значення сорт-колонки останнього рядка попередньої сторінки. */
                       afterValue?: string | null;
                       /** Keyset-якір: id останнього рядка попередньої сторінки. */
                       afterId?: string | null }) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    appendPagedQuery(params, opts);
    if (opts?.withCount === false) params.set("count", "false");
    if (opts?.afterId) {
      // afterValue може бути порожнім рядком (NULL сорт-колонки) — передаємо лише при валідному id.
      if (opts.afterValue != null) params.set("afterValue", opts.afterValue);
      params.set("afterId", opts.afterId);
    }
    return api.get<PageResponse<Record<string, unknown>>>(
      `/api/references/${slug}/rows?${params.toString()}`);
  },
  /**
   * Наступний код для нового елемента довідника (підставляється у поле «Код»;
   * користувач може його змінити). Лічильник інкрементується гарантовано, навіть
   * якщо запис не створиться.
   */
  nextCode: (slug: string) =>
    api.get<{ code: string }>(`/api/references/${slug}/next-code`),
};

/** Результат валідації одного реквізиту (дзеркало backend FieldResult). */
export interface FieldValidationResult {
  field: string;
  valid: boolean;
  /** Текст першої помилки (відсутній, якщо valid). */
  message?: string | null;
  /** Доп-інфо обмежень поля: {min,max,minInclusive,maxInclusive,maxLength,...}. */
  meta?: Record<string, unknown> | null;
}

export interface ObjectValidationResult {
  valid: boolean;
  results: FieldValidationResult[];
}

export const validationApi = {
  /** Перевірити один реквізит (інтерактивно, по дебаунсу). */
  field: (slug: string, field: string, value: unknown) =>
    api.post<FieldValidationResult>(`/api/validation/${slug}/field`, { field, value }),
  /** Повна перевірка всіх реквізитів об'єкта (як на сервері при збереженні). */
  object: (slug: string, values: Record<string, unknown>) =>
    api.post<ObjectValidationResult>(`/api/validation/${slug}/object`, values),
  /** Карта {поле -> meta} обмежень типу (для попередніх підказок UI). */
  constraints: (slug: string) =>
    api.get<Record<string, Record<string, unknown>>>(`/api/validation/${slug}/constraints`),
};

export interface PurgeReport {
  total: number;
  usersDeleted: number;
  eventsDeleted: number;
  /** Скільки видалено за кожним типом: {назваМножини -> кількість}. */
  byType: Record<string, number>;
}

/** Тип, доступний для генерації (для динамічної побудови форми). */
export interface GenTarget {
  typeId: number;
  slug: string;
  singularLabel: string;
  pluralLabel: string;
  iconHint: string;
  /** REFERENCE | REGISTER | TABULAR */
  kind: "REFERENCE" | "REGISTER" | "TABULAR";
  /** Для TABULAR — назва типу-власника (множина), інакше null. */
  ownerLabel: string | null;
}

export const dataGenApi = {
  /** Flag-маркер у назві згенерованих об'єктів (для UI-підказки). */
  flag: () => api.get<{ flag: string }>("/api/admin/datagen/flag"),
  /** Перелік типів (довідники/регістри/табличні частини), доступних для генерації. */
  targets: () => api.get<GenTarget[]>("/api/admin/datagen/targets"),
  /** Універсальна генерація N записів типу (для ТЧ N — на кожного власника). */
  generate: (req: { typeId: number; count: number; benchmark?: boolean; noLogging?: boolean }) =>
    api.post<{ created: number; typeId: number; benchmark?: boolean; noLogging?: boolean }>(
      "/api/admin/datagen/generate", req),
  /** «Очистити весь тип»: видаляє ВСІ рядки типу (і реальні, і згенеровані). */
  purgeType: (req: { typeId: number }) =>
    api.post<{ deleted: number; typeId: number }>("/api/admin/datagen/purge-type", req),
  /** Згенерувати N користувачів з дефолтними роллю, паролем та інтерфейсом. */
  generateUsers: (req: {
    count: number;
    defaultRoleId?: UUID | null;
    defaultPassword?: string;
    defaultInterfaceId?: UUID | null;
  }) =>
    api.post<{ created: number; kind: string }>("/api/admin/datagen/users", req),
  /** Зачистити всі згенеровані дані. */
  purge: () => api.post<PurgeReport>("/api/admin/datagen/purge"),
};
