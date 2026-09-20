package app.modules.sqlworkbench.service;

import app.modules.sqlworkbench.dto.Dtos.*;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Генерический CRUD-гейтвей к таблицам БД — порт
 * com.sqleo.environment.ctrl.content.UpdateModel + TaskUpdate + ContentModel.
 *
 * Это центральная возможность переработанного продукта: бэкенд формирует
 * INSERT/UPDATE/DELETE по имени таблицы и набору значений, использует
 * PreparedStatement (как execute() в TaskUpdate) и постраничную выборку контента.
 *
 * Имена таблиц/колонок экранируются и валидируются по метаданным, чтобы
 * исключить SQL-инъекции в идентификаторах.
 */
@Service("sqlworkbenchCrudService")
public class CrudService {

    private final DataSourceRegistry registry;
    private final MetadataService metadata;

    public CrudService(DataSourceRegistry registry, MetadataService metadata) {
        this.registry = registry;
        this.metadata = metadata;
    }

    /** Постраничное чтение содержимого таблицы (ContentModel block-выборка). */
    public ResultSetDto read(String dsId, String catalog, String schema, String table,
                             int page, int pageSize, String orderBy) {
        TableMetadata meta = metadata.table(dsId, catalog, schema, table);
        String qualified = qualify(schema, table);
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(qualified);
        if (orderBy != null && !orderBy.isBlank()) {
            String oc = validateColumn(meta, stripDir(orderBy));
            sql.append(" ORDER BY ").append(oc).append(orderBy.toUpperCase().endsWith(" DESC") ? " DESC" : " ASC");
        }
        QueryRequest qr = new QueryRequest(sql.toString(), page, pageSize);
        return new QueryService(registry).runSelect(dsId, qr);
    }

    /** INSERT (UpdateModel.getInsertSyntax + TaskUpdate.execute). */
    public CrudResult insert(String dsId, String catalog, String schema, String table, RowMutation m) {
        assertWritable(dsId);
        TableMetadata meta = metadata.table(dsId, catalog, schema, table);
        Map<String, Object> values = m.values();
        if (values == null || values.isEmpty())
            throw new IllegalArgumentException("No values to insert");

        List<String> cols = values.keySet().stream()
                .map(c -> validateColumn(meta, c)).collect(Collectors.toList());
        String placeholders = cols.stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + qualify(schema, table) + " (" +
                String.join(", ", cols) + ") VALUES (" + placeholders + ")";

        try (Connection c = registry.connection(dsId);
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, new ArrayList<>(values.values()), 1);
            int affected = ps.executeUpdate();
            Map<String, Object> keys = readGeneratedKeys(ps);
            return new CrudResult(affected, keys, sql);
        } catch (SQLException e) {
            throw new IllegalStateException("INSERT failed: " + e.getMessage(), e);
        }
    }

    /** UPDATE по ключу (UpdateModel.getUpdateSyntax). */
    public CrudResult update(String dsId, String catalog, String schema, String table, RowMutation m) {
        assertWritable(dsId);
        TableMetadata meta = metadata.table(dsId, catalog, schema, table);
        if (m.values() == null || m.values().isEmpty())
            throw new IllegalArgumentException("No values to update");
        Map<String, Object> key = effectiveKey(meta, m);

        List<String> setCols = m.values().keySet().stream()
                .map(col -> validateColumn(meta, col)).collect(Collectors.toList());
        String setClause = setCols.stream().map(c -> c + " = ?").collect(Collectors.joining(", "));
        List<String> keyCols = key.keySet().stream()
                .map(col -> validateColumn(meta, col)).collect(Collectors.toList());
        String whereClause = keyCols.stream().map(c -> c + " = ?").collect(Collectors.joining(" AND "));

        String sql = "UPDATE " + qualify(schema, table) + " SET " + setClause + " WHERE " + whereClause;
        List<Object> params = new ArrayList<>(m.values().values());
        params.addAll(key.values());

        try (Connection c = registry.connection(dsId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params, 1);
            int affected = ps.executeUpdate();
            return new CrudResult(affected, Map.of(), sql);
        } catch (SQLException e) {
            throw new IllegalStateException("UPDATE failed: " + e.getMessage(), e);
        }
    }

    /** DELETE по ключу (UpdateModel.getDeleteSyntax). */
    public CrudResult delete(String dsId, String catalog, String schema, String table, RowMutation m) {
        assertWritable(dsId);
        TableMetadata meta = metadata.table(dsId, catalog, schema, table);
        Map<String, Object> key = effectiveKey(meta, m);
        List<String> keyCols = key.keySet().stream()
                .map(col -> validateColumn(meta, col)).collect(Collectors.toList());
        String whereClause = keyCols.stream().map(c -> c + " = ?").collect(Collectors.joining(" AND "));
        String sql = "DELETE FROM " + qualify(schema, table) + " WHERE " + whereClause;

        try (Connection c = registry.connection(dsId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, new ArrayList<>(key.values()), 1);
            int affected = ps.executeUpdate();
            return new CrudResult(affected, Map.of(), sql);
        } catch (SQLException e) {
            throw new IllegalStateException("DELETE failed: " + e.getMessage(), e);
        }
    }

    // ---- helpers ----

    private void assertWritable(String dsId) {
        if (registry.isReadOnly(dsId))
            throw new IllegalStateException("Data source '" + dsId + "' is open in read-only mode");
    }

    /** Если ключ не передан явно — собираем по PK из метаданных (UpdateModel.setRowIdentifier). */
    private Map<String, Object> effectiveKey(TableMetadata meta, RowMutation m) {
        if (m.key() != null && !m.key().isEmpty()) return m.key();
        List<String> pk = meta.columns().stream().filter(ColumnInfo::primaryKey)
                .map(ColumnInfo::name).toList();
        if (pk.isEmpty())
            throw new IllegalArgumentException("The table has no primary key - pass 'key' explicitly");
        Map<String, Object> key = new LinkedHashMap<>();
        for (String c : pk) {
            if (m.values() == null || !m.values().containsKey(c))
                throw new IllegalArgumentException("No value for the key column: " + c);
            key.put(c, m.values().get(c));
        }
        return key;
    }

    private String validateColumn(TableMetadata meta, String col) {
        boolean known = meta.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(col));
        if (!known) throw new IllegalArgumentException("Unknown column: " + col);
        return quoteIdent(col);
    }

    private String qualify(String schema, String table) {
        // table уже сверена с метаданными в вызывающем коде через MetadataService.table()
        return schema != null && !schema.isBlank()
                ? quoteIdent(schema) + "." + quoteIdent(table) : quoteIdent(table);
    }

    /** Экранирование идентификатора (порт SQLFormatter.ensureQuotes). */
    private String quoteIdent(String id) {
        if (!id.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("Invalid identifier: " + id);
        return "\"" + id + "\"";
    }

    private String stripDir(String orderBy) {
        return orderBy.replaceAll("(?i)\\s+(asc|desc)\\s*$", "").trim();
    }

    private void bind(PreparedStatement ps, List<Object> params, int from) throws SQLException {
        int idx = from;
        for (Object p : params) {
            if (p == null) ps.setObject(idx, null);
            else ps.setObject(idx, p);
            idx++;
        }
    }

    private Map<String, Object> readGeneratedKeys(PreparedStatement ps) {
        Map<String, Object> keys = new LinkedHashMap<>();
        try (ResultSet gk = ps.getGeneratedKeys()) {
            if (gk != null && gk.next()) {
                ResultSetMetaData md = gk.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++)
                    keys.put(md.getColumnLabel(i), QueryService.normalize(gk.getObject(i)));
            }
        } catch (SQLException ignored) {}
        return keys;
    }
}
