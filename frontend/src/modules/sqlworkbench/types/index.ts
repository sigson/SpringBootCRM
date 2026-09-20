// Типы API-гейтвея SpringBootCRM — зеркало app.springbootcrm.dto.Dtos (backend).

export interface DataSourceRequest {
  id: string; name: string; driverClass: string;
  url: string; username: string; password: string; readOnly: boolean;
}
export interface DataSourceInfo {
  id: string; name: string; driverClass: string;
  url: string; username: string; readOnly: boolean; connected: boolean;
}

export interface SchemaInfo { catalog: string | null; schema: string | null; }
export interface TableInfo {
  catalog: string | null; schema: string | null;
  name: string; type: string; remarks: string | null;
}
export interface ColumnInfo {
  name: string; typeName: string; sqlType: number; size: number;
  nullable: boolean; primaryKey: boolean; remarks: string | null;
}
export interface ForeignKeyInfo {
  fkColumn: string; pkTable: string; pkColumn: string; fkName: string | null;
}
export interface TableMetadata {
  table: TableInfo; columns: ColumnInfo[]; foreignKeys: ForeignKeyInfo[];
}

export interface QueryRequest { sql: string; page?: number; pageSize?: number; }
export interface ResultSetDto {
  columns: string[]; columnTypes: string[]; rows: unknown[][];
  page: number; pageSize: number; hasMore: boolean;
  totalRows: number | null; executedSql: string; elapsedMs: number;
}

export interface RowMutation {
  values?: Record<string, unknown>;
  key?: Record<string, unknown>;
}
export interface CrudResult {
  affected: number; generatedKeys: Record<string, unknown>; executedSql: string;
}

export interface BuildSqlResponse { sql: string; sqlPretty: string; }

export interface ApiError { status: number; error: string; message: string; path: string; }

export type WorkbenchActionName =
  | "VIEW_DATASOURCES" | "MANAGE_DATASOURCES" | "READ_METADATA" | "RUN_QUERY"
  | "READ_DATA" | "INSERT_DATA" | "UPDATE_DATA" | "DELETE_DATA" | "BUILD_QUERY";

export type Capabilities = Partial<Record<WorkbenchActionName, boolean>>;
