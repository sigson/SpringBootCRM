import type {
  AvailableField, CompositionResult, DataSetPreview, DcsSchema, DcsSettings, DescribedDataSet,
  ExpressionCheck, QueryPackDto, QueryPackSqlResponse, ReportDto, ReportForm,
  ReportTemplate, SchemaParameter, SettingsBundle, SettingsVariant, SqlPreview, UUID,
} from "./types";

/**
 * <h2>Клиент модуля компоновки.</h2>
 *
 * <p>Ходит в три места, и это не случайность, а отражение архитектуры:
 * <ul>
 *   <li>{@code /api/reports} — справочник отчётов хоста (хранение);</li>
 *   <li>{@code /api/dcs} — движок компоновки (формирование, расшифровка, выгрузка);</li>
 *   <li>{@code /api/sqlworkbench/query-pack} — сборка нескольких запросов в связь.</li>
 * </ul>
 * Модуль не импортирует код хоста: токен и базовый origin приходят снаружи, как и у
 * Workbench'а. Поэтому папку можно удалить целиком, не трогая остальной фронтенд.
 */

export class DcsApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

declare global {
  interface Window { __API_BASE__?: string; }
}

function origin(): string {
  const v = (typeof window !== "undefined" ? window.__API_BASE__ : undefined) ?? "";
  return v.replace(/\/+$/, "");
}

function url(path: string): string {
  const base = origin();
  return base ? base + (path.startsWith("/") ? path : "/" + path) : path;
}

export interface DcsClientConfig {
  getToken?: () => string | null | undefined;
  onError?: (e: DcsApiError) => void;
}

export class DcsClient {
  constructor(private cfg: DcsClientConfig = {}) {}

  private async req<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    const token = this.cfg.getToken?.();
    if (token) headers["Authorization"] = `Bearer ${token}`;

    const res = await fetch(url(path), {
      method, headers, credentials: "include",
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
      let message = res.statusText;
      try {
        const e = await res.json();
        message = e.message ?? message;
      } catch { /* тело не JSON — оставляем statusText */ }
      const err = new DcsApiError(res.status, message);
      this.cfg.onError?.(err);
      throw err;
    }
    if (res.status === 204) return undefined as T;
    return (await res.json()) as T;
  }

  /** Скачивание файла выгрузки: ответ бинарный, поэтому мимо {@link req}. */
  private async download(path: string, body: unknown, fallbackName: string): Promise<void> {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    const token = this.cfg.getToken?.();
    if (token) headers["Authorization"] = `Bearer ${token}`;
    const res = await fetch(url(path), {
      method: "POST", headers, credentials: "include", body: JSON.stringify(body),
    });
    if (!res.ok) {
      const err = new DcsApiError(res.status, `Export failed (${res.status})`);
      this.cfg.onError?.(err);
      throw err;
    }
    const blob = await res.blob();
    const name = fileNameOf(res.headers.get("Content-Disposition")) ?? fallbackName;
    const href = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = href;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    // Освобождаем object-url не сразу: Safari отменяет ещё не начавшуюся загрузку.
    setTimeout(() => URL.revokeObjectURL(href), 10_000);
  }

  // ---------------------------------------------------- справочник отчётов

  listReports() { return this.req<ReportDto[]>("GET", "/api/reports"); }
  getReport(id: UUID) { return this.req<ReportDto>("GET", `/api/reports/${id}`); }

  createReport(body: {
    code?: string; name: string; scheme?: DcsSchema; settings?: SettingsBundle;
    templates?: ReportTemplate[]; forms?: ReportForm[]; dataSourceId?: string; enabled?: boolean;
  }) {
    return this.req<ReportDto>("POST", "/api/reports", body);
  }

  updateReport(id: UUID, body: {
    name?: string; scheme?: DcsSchema; settings?: SettingsBundle;
    templates?: ReportTemplate[]; forms?: ReportForm[]; dataSourceId?: string; enabled?: boolean;
  }) {
    return this.req<ReportDto>("PUT", `/api/reports/${id}`, body);
  }

  // ------------------------------------------------------------ компоновка

  capabilities() { return this.req<Record<string, unknown>>("GET", "/api/dcs/capabilities"); }

  composeSaved(id: UUID, settings?: DcsSettings | null, variantId?: string | null) {
    return this.req<CompositionResult>("POST", `/api/dcs/reports/${id}/compose`,
      { variantId: variantId ?? null, settings: settings ?? null });
  }

  compose(scheme: DcsSchema, settings: DcsSettings, dataSourceId?: string | null) {
    return this.req<CompositionResult>("POST", "/api/dcs/compose",
      { scheme, settings, dataSourceId: dataSourceId ?? null });
  }

  drilldown(id: UUID, body: {
    variantId?: string | null; settings?: DcsSettings | null;
    details: Record<string, unknown>; action: "detail" | "groupBy"; field?: string | null;
  }) {
    return this.req<CompositionResult>("POST", `/api/dcs/reports/${id}/drilldown`, body);
  }

  previewSql(scheme: DcsSchema, settings: DcsSettings) {
    return this.req<SqlPreview>("POST", "/api/dcs/preview-sql", { scheme, settings });
  }

  /**
   * Всё, что нужно форме сохранённого отчёта: поля, параметры, настройки по
   * умолчанию, варианты, формы и макеты — одним запросом, чтобы форма открывалась
   * за один round-trip, а не за пять.
   */
  reportMeta(id: UUID) {
    return this.req<{
      fields: AvailableField[];
      parameters: SchemaParameter[];
      defaultSettings: DcsSettings | null;
      variants: SettingsVariant[] | null;
      forms: ReportForm[] | null;
      templates: ReportTemplate[] | null;
    }>("GET", `/api/dcs/reports/${id}/available-fields`);
  }

  availableFields(scheme: DcsSchema, settings: DcsSettings) {
    return this.req<AvailableField[]>("POST", "/api/dcs/available-fields", { scheme, settings });
  }

  describeDataSets(packed: string, dataSourceId?: string | null,
                   parameters?: Record<string, unknown>) {
    return this.req<DescribedDataSet[]>("POST", "/api/dcs/describe-datasets",
      { packed, dataSourceId: dataSourceId ?? null, parameters: parameters ?? {} });
  }

  /** Данные одного набора — предпросмотр из конструктора запроса. */
  previewDataSet(body: {
    packed: string; dataSet: string; dataSourceId?: string | null;
    parameters?: Record<string, unknown>; limit?: number;
  }) {
    return this.req<DataSetPreview>("POST", "/api/dcs/preview-dataset", {
      packed: body.packed,
      dataSet: body.dataSet,
      dataSourceId: body.dataSourceId ?? null,
      parameters: body.parameters ?? {},
      limit: body.limit ?? 20,
    });
  }

  validateExpression(expression: string) {
    return this.req<ExpressionCheck>("POST", "/api/dcs/validate-expression", { expression });
  }

  exportAdHoc(scheme: DcsSchema, settings: DcsSettings, dataSourceId: string | null,
              format: "xlsx" | "csv", name: string) {
    return this.download(`/api/dcs/export?format=${format}`,
      { scheme, settings, dataSourceId }, `${name}.${format}`);
  }

  exportSaved(id: UUID, settings: DcsSettings | null, variantId: string | null,
              format: "xlsx" | "csv", name: string) {
    return this.download(`/api/dcs/reports/${id}/export?format=${format}`,
      { variantId, settings }, `${name}.${format}`);
  }

  // --------------------------------------------------------- пакет запросов

  parsePack(packed: string) {
    return this.req<QueryPackDto>("POST", "/api/sqlworkbench/query-pack/parse", { packed });
  }

  packToSql(pack: QueryPackDto) {
    return this.req<QueryPackSqlResponse>("POST", "/api/sqlworkbench/query-pack/sql", { pack });
  }

  encodePack(pack: QueryPackDto) {
    return this.req<QueryPackSqlResponse>("POST", "/api/sqlworkbench/query-pack/encode", { pack });
  }

  runPack(dsId: string, pack: QueryPackDto, pageSize = 50) {
    return this.req<{ columns: string[]; rows: unknown[][]; elapsedMs: number }>(
      "POST", `/api/sqlworkbench/datasources/${encodeURIComponent(dsId)}/query-pack/run?pageSize=${pageSize}`,
      { pack });
  }

  dataSources() {
    return this.req<{ id: string; name: string; readOnly: boolean; connected: boolean }[]>(
      "GET", "/api/sqlworkbench/datasources");
  }
}

function fileNameOf(header: string | null): string | null {
  if (!header) return null;
  const star = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (star) return decodeURIComponent(star[1]);
  const plain = /filename="?([^";]+)"?/i.exec(header);
  return plain ? plain[1] : null;
}
