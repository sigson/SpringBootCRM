package app.modules.sqlworkbench.querymodel;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Серверная модель запроса — порт com.sqleo.querybuilder.QueryModel /
 * QuerySpecification / QueryTokens из оригинального SQLeo VQB.
 *
 * Дерево полностью сериализуемо в JSON и обратно, благодаря чему визуальный
 * конструктор на фронтенде и бэкенд оперируют одной и той же структурой.
 * {@link SqlFormatter} превращает модель в текст SQL (аналог toString()/SQLFormatter).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryModel {

    /** SELECT-квантор. */
    public enum Quantifier { ALL, DISTINCT }

    /** Тип соединения (порт QueryTokens.Join.INNER/LEFT_OUTER/...). */
    public enum JoinType {
        INNER("INNER JOIN"),
        LEFT_OUTER("LEFT OUTER JOIN"),
        RIGHT_OUTER("RIGHT OUTER JOIN"),
        FULL_OUTER("FULL OUTER JOIN"),
        CROSS("CROSS JOIN");

        public final String sql;
        JoinType(String sql) { this.sql = sql; }
    }

    /** Ссылка на таблицу с необязательным алиасом и схемой. */
    public static class TableRef {
        public String schema;
        public String name;
        public String alias;

        public String reference() {
            return alias != null && !alias.isBlank() ? alias : identifier();
        }
        public String identifier() {
            return schema != null && !schema.isBlank() ? schema + "." + name : name;
        }
    }

    /** Ссылка на столбец (table.column AS alias). */
    public static class ColumnRef {
        public String table;   // reference таблицы (alias|identifier)
        public String name;    // имя столбца или выражение
        public String alias;
        public boolean expression; // true => name это произвольное выражение, не table.column
    }

    /** Условие (порт QueryTokens.Condition): left <op> right, c необязательным append (AND/OR). */
    public static class Condition {
        public String append;     // AND | OR | null
        public String left;       // выражение слева (table.col или литерал/функция)
        public String operator = "=";
        public String right;      // выражение справа
    }

    /** Соединение двух таблиц (порт QueryTokens.Join). */
    public static class Join {
        public JoinType type = JoinType.INNER;
        public String leftTable;   // reference
        public String leftColumn;
        public String operator = "=";
        public String rightTable;  // reference
        public String rightColumn;
    }

    /** Сортировка (порт QueryTokens.Sort). */
    public static class Sort {
        public String expression;
        public boolean ascending = true;
    }

    // ---- собственно поля QuerySpecification ----
    public String schema;
    public Quantifier quantifier = Quantifier.ALL;
    public boolean asterisk = false;
    public List<ColumnRef> select = new ArrayList<>();
    public List<TableRef> from = new ArrayList<>();
    public List<Join> joins = new ArrayList<>();
    public List<Condition> where = new ArrayList<>();
    public List<String> groupBy = new ArrayList<>();
    public List<Condition> having = new ArrayList<>();
    public List<Sort> orderBy = new ArrayList<>();
    public Integer limit;
}
