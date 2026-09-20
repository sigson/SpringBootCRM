import type {
  BuildSqlResponse, Capabilities, CrudResult, DataSourceInfo, DataSourceRequest,
  QueryRequest, ResultSetDto, RowMutation, SchemaInfo, TableInfo, TableMetadata,
} from "../types";
import type { QueryModel } from "../querymodel/model";

export interface WorkbenchClientConfig {
  /** Базовый путь API. В standalone: "/api/sqlworkbench". При встраивании: путь прокси host'а, напр. "/host/sqlworkbench". */
  baseUrl: string;
  /** Провайдер токена (Bearer). Host-приложение возвращает свой JWT. */
  getToken?: () => string | null | undefined | Promise<string | null | undefined>;
  /** Глобальный обработчик ошибок. */
  onError?: (err: ApiException) => void;
}

export class ApiException extends Error {
  constructor(public status: number, message: string, public path?: string) {
    super(message);
  }
}

/**
 * Origin бекенду. Модуль навмисно НЕ імпортує код host-додатка, тож читає той
 * самий runtime-глобал {@code window.__API_BASE__}, що й host (див. src/api/config.ts).
 * Порожньо → same-origin (відносні шляхи).
 */
function backendOrigin(): string {
  const v = (typeof window !== "undefined" ? window.__API_BASE__ : undefined) ?? "";
  return v.replace(/\/+$/, "");
}

/** Абсолютний URL до бекенду для відносного шляху. */
function resolveUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) return path;
  const base = backendOrigin();
  if (!base) return path;
  return base + (path.startsWith("/") ? path : "/" + path);
}

/** Типизированный клиент API-гейтвея SpringBootCRM. */
export class WorkbenchClient {
  constructor(private cfg: WorkbenchClientConfig) {}

  private async req<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    const token = this.cfg.getToken ? await this.cfg.getToken() : null;
    if (token) headers["Authorization"] = `Bearer ${token}`;

    const res = await fetch(resolveUrl(this.cfg.baseUrl + path), {
      method, headers,
      credentials: "include",
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });

    if (!res.ok) {
      let msg = res.statusText;
      let p: string | undefined;
      try {
        const e = await res.json();
        msg = e.message ?? msg; p = e.path;
      } catch {  }
      const ex = new ApiException(res.status, msg, p);
      this.cfg.onError?.(ex);
      throw ex;
    }
    if (res.status === 204) return undefined as T;
    return (await res.json()) as T;
  }

  private q(params: Record<string, unknown>): string {
    const sp = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) if (v != null && v !== "") sp.set(k, String(v));
    const s = sp.toString();
    return s ? `?${s}` : "";
  }

  capabilities() { return this.req<Capabilities>("GET", "/capabilities"); }

  listDataSources() { return this.req<DataSourceInfo[]>("GET", "/datasources"); }
  registerDataSource(req: DataSourceRequest) { return this.req<DataSourceInfo>("POST", "/datasources", req); }
  removeDataSource(id: string) { return this.req<void>("DELETE", `/datasources/${encodeURIComponent(id)}`); }
  testDataSource(id: string) { return this.req<{ id: string; ok: boolean }>("POST", `/datasources/${encodeURIComponent(id)}/test`); }

  schemas(dsId: string) { return this.req<SchemaInfo[]>("GET", `/datasources/${dsId}/metadata/schemas`); }
  tables(dsId: string, catalog?: string, schema?: string) {
    return this.req<TableInfo[]>("GET", `/datasources/${dsId}/metadata/tables${this.q({ catalog, schema })}`);
  }
  table(dsId: string, table: string, catalog?: string, schema?: string) {
    return this.req<TableMetadata>("GET", `/datasources/${dsId}/metadata/tables/${encodeURIComponent(table)}${this.q({ catalog, schema })}`);
  }

  query(dsId: string, req: QueryRequest) {
    return this.req<ResultSetDto>("POST", `/datasources/${dsId}/query`, req);
  }

  /**
   * Граф бизнес-объектов host'а для ссылочного режима конструктора. Эндпоинт
   * {@code /api/metadata/graph} живёт на host'е (не под baseUrl модуля), поэтому
   * запрос идёт по абсолютному пути с тем же Bearer-токеном.
   */
  metadataGraph() {
    return this.absReq<import("../querymodel/referenceModel").GraphResponse>(
      "GET", "/api/metadata/graph");
  }

  /** Запрос по абсолютному (host-)пути, мимо baseUrl модуля. */
  private async absReq<T>(method: string, absolutePath: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    const token = this.cfg.getToken ? await this.cfg.getToken() : null;
    if (token) headers["Authorization"] = `Bearer ${token}`;
    const res = await fetch(resolveUrl(absolutePath), {
      method, headers,
      credentials: "include",
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
      let msg = res.statusText;
      try { const e = await res.json(); msg = e.message ?? msg; } catch {  }
      const ex = new ApiException(res.status, msg, absolutePath);
      this.cfg.onError?.(ex);
      throw ex;
    }
    if (res.status === 204) return undefined as T;
    return (await res.json()) as T;
  }

  buildSql(model: QueryModel, pretty = true) {
    return this.req<BuildSqlResponse>("POST", `/query-builder/sql?pretty=${pretty}`, model);
  }
  runBuilder(dsId: string, model: QueryModel, page = 0, pageSize = 100) {
    return this.req<ResultSetDto>("POST", `/datasources/${dsId}/query-builder/run${this.q({ page, pageSize })}`, model);
  }

  readRows(dsId: string, table: string, opts: { catalog?: string; schema?: string; page?: number; pageSize?: number; orderBy?: string } = {}) {
    return this.req<ResultSetDto>("GET", `/datasources/${dsId}/tables/${encodeURIComponent(table)}/rows${this.q(opts)}`);
  }
  insertRow(dsId: string, table: string, body: RowMutation, schema?: string, catalog?: string) {
    return this.req<CrudResult>("POST", `/datasources/${dsId}/tables/${encodeURIComponent(table)}/rows${this.q({ schema, catalog })}`, body);
  }
  updateRow(dsId: string, table: string, body: RowMutation, schema?: string, catalog?: string) {
    return this.req<CrudResult>("PUT", `/datasources/${dsId}/tables/${encodeURIComponent(table)}/rows${this.q({ schema, catalog })}`, body);
  }
  deleteRow(dsId: string, table: string, body: RowMutation, schema?: string, catalog?: string) {
    return this.req<CrudResult>("DELETE", `/datasources/${dsId}/tables/${encodeURIComponent(table)}/rows${this.q({ schema, catalog })}`, body);
  }
}
