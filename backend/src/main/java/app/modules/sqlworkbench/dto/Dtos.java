package app.modules.sqlworkbench.dto;

import java.util.List;
import java.util.Map;

/**
 * Контейнер всех DTO API-гейтвея (records).
 * Сгруппированы в одном файле для компактности интеграции в host-продукт.
 */
public final class Dtos {
    private Dtos() {}

    // ---- Datasources / соединения (порт MaskDatasource/ConnectionAssistant) ----
    public record DataSourceRequest(
            String id, String name, String driverClass,
            String url, String username, String password, boolean readOnly) {}

    public record DataSourceInfo(
            String id, String name, String driverClass,
            String url, String username, boolean readOnly, boolean connected) {}

    // ---- Метаданные (порт ViewMetadata: DatabaseMetaData) ----
    public record SchemaInfo(String catalog, String schema) {}
    public record TableInfo(String catalog, String schema, String name, String type, String remarks) {}
    public record ColumnInfo(String name, String typeName, int sqlType, int size,
                             boolean nullable, boolean primaryKey, String remarks) {}
    public record ForeignKeyInfo(String fkColumn, String pkTable, String pkColumn, String fkName) {}
    public record TableMetadata(TableInfo table, List<ColumnInfo> columns, List<ForeignKeyInfo> foreignKeys) {}

    // ---- Выполнение запросов / результат (порт ContentModel/TaskRetrieve) ----
    public record QueryRequest(String sql, Integer page, Integer pageSize) {}
    public record ResultSetDto(
            List<String> columns, List<String> columnTypes,
            List<List<Object>> rows, int page, int pageSize,
            boolean hasMore, Long totalRows, String executedSql, long elapsedMs) {}

    // ---- CRUD строк (порт UpdateModel/TaskUpdate) ----
    public record RowMutation(Map<String, Object> values, Map<String, Object> key) {}
    public record CrudResult(int affected, Map<String, Object> generatedKeys, String executedSql) {}

    // ---- Конструктор запросов ----
    public record BuildSqlResponse(String sql, String sqlPretty) {}

    // ---- Пакет запросов (несколько запросов в одной строке + связи) ----
    /** Запрос на сборку: либо готовая структура, либо упакованная строка. */
    public record QueryPackRequest(
            app.modules.sqlworkbench.querypack.QueryPack pack, String packed) {}
    public record QueryPackSqlResponse(
            String sql, String sqlPretty,
            /** Наборы в порядке подключения к цепочке соединений. */
            List<String> joinedQueries,
            /** Непустой список не блокирует выполнение — это подсказки о забытых связях. */
            List<String> warnings,
            /** Нормализованная упакованная строка (результат round-trip'а). */
            String packed) {}

    // ---- Стандартный ответ об ошибке ----
    public record ApiError(int status, String error, String message, String path) {}
}
