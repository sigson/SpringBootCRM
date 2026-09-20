package app.modules.sqlworkbench.service;

import app.modules.sqlworkbench.dto.Dtos.QueryRequest;
import app.modules.sqlworkbench.dto.Dtos.ResultSetDto;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * Выполнение SELECT-запросов с постраничной выборкой — порт
 * com.sqleo.environment.ctrl.content.TaskRetrieve + ContentModel (MAX_BLOCK_RECORDS).
 *
 * Только read-операции; модифицирующий SQL отклоняется (для него есть CrudService).
 */
@Service("sqlworkbenchQueryService")
public class QueryService {

    public static final int DEFAULT_PAGE_SIZE = 100; // == ContentModel.MAX_BLOCK_RECORDS
    private static final Set<String> WRITE_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE", "MERGE", "GRANT", "REVOKE");

    private final DataSourceRegistry registry;

    public QueryService(DataSourceRegistry registry) { this.registry = registry; }

    public ResultSetDto runSelect(String dsId, QueryRequest req) {
        return runSelect(dsId, req, List.of());
    }

    /**
     * Тот же SELECT, но с позиционными параметрами ({@code ?}) через {@link PreparedStatement}.
     *
     * <p>Нужен всем генераторам SQL поверх модуля (конструктор пакетов, движок
     * компоновки отчётов): значения отборов и параметров отчёта не склеиваются в текст
     * запроса, а уходят биндами — это снимает риск инъекции и сохраняет типы.
     * Пустой список параметров даёт обычный {@link Statement} — план не меняется.
     */
    public ResultSetDto runSelect(String dsId, QueryRequest req, List<Object> params) {
        String sql = req.sql() == null ? "" : req.sql().trim();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
        assertReadOnly(sql);

        int page = req.page() == null ? 0 : Math.max(0, req.page());
        int pageSize = req.pageSize() == null ? DEFAULT_PAGE_SIZE : Math.max(1, req.pageSize());
        int offset = page * pageSize;

        long start = System.currentTimeMillis();
        try (Connection c = registry.connection(dsId);
             PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) st.setObject(i + 1, params.get(i));
            // Фетчим pageSize+1, чтобы определить наличие следующей страницы (как блоки в SQLeo).
            st.setMaxRows(offset + pageSize + 1);
            st.setFetchSize(pageSize);

            try (ResultSet rs = st.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<String> columns = new ArrayList<>(n);
                List<String> types = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    columns.add(md.getColumnLabel(i));
                    types.add(md.getColumnTypeName(i));
                }

                // Прокрутка до offset
                int skipped = 0;
                while (skipped < offset && rs.next()) skipped++;

                List<List<Object>> rows = new ArrayList<>();
                boolean hasMore = false;
                while (rs.next()) {
                    if (rows.size() == pageSize) { hasMore = true; break; }
                    List<Object> row = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) row.add(normalize(rs.getObject(i)));
                    rows.add(row);
                }
                long elapsed = System.currentTimeMillis() - start;
                return new ResultSetDto(columns, types, rows, page, pageSize, hasMore, null, sql, elapsed);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Query execution failed: " + e.getMessage(), e);
        }
    }

    private void assertReadOnly(String sql) {
        String head = sql.replaceFirst("(?is)^\\s*(with\\s+.*?\\)\\s*)?", "");
        String firstWord = head.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        if (WRITE_KEYWORDS.contains(firstWord)) {
            throw new IllegalArgumentException(
                    "Only SELECT is allowed through the query endpoint. Use the CRUD API to change data.");
        }
    }

    /** Приведение JDBC-значений к JSON-дружественным типам. */
    static Object normalize(Object v) {
        if (v == null) return null;
        if (v instanceof byte[] b) return "0x" + HexFormat.of().formatHex(b);
        if (v instanceof Clob clob) {
            try { return clob.getSubString(1, (int) Math.min(clob.length(), 1 << 20)); }
            catch (SQLException e) { return "[CLOB]"; }
        }
        if (v instanceof java.sql.Timestamp || v instanceof java.sql.Date || v instanceof java.sql.Time) {
            return v.toString();
        }
        return v;
    }
}
