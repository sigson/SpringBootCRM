package app.modules.sqlworkbench.querymodel;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Порт com.sqleo.querybuilder.syntax.SQLFormatter + QuerySpecification.toString().
 * Сериализует {@link QueryModel} в текст SQL (плоский или с переносами строк).
 */
public final class SqlFormatter {

    private SqlFormatter() {}

    public static String toSql(QueryModel m) { return toSql(m, false); }

    public static String toSql(QueryModel m, boolean wrap) {
        String nl = wrap ? "\n" : " ";
        StringBuilder sb = new StringBuilder();

        sb.append("SELECT ");
        if (m.quantifier == QueryModel.Quantifier.DISTINCT) sb.append("DISTINCT ");

        if (m.asterisk || m.select.isEmpty()) {
            sb.append("*");
        } else {
            sb.append(m.select.stream().map(SqlFormatter::col)
                    .collect(Collectors.joining("," + (wrap ? nl + "       " : " "))));
        }

        sb.append(nl).append("FROM ").append(fromClause(m, nl));

        if (!m.where.isEmpty()) {
            sb.append(nl).append("WHERE ").append(conditions(m.where));
        }
        if (!m.groupBy.isEmpty()) {
            sb.append(nl).append("GROUP BY ").append(String.join(", ", m.groupBy));
        }
        if (!m.having.isEmpty()) {
            sb.append(nl).append("HAVING ").append(conditions(m.having));
        }
        if (!m.orderBy.isEmpty()) {
            sb.append(nl).append("ORDER BY ").append(m.orderBy.stream()
                    .map(s -> s.expression + (s.ascending ? " ASC" : " DESC"))
                    .collect(Collectors.joining(", ")));
        }
        if (m.limit != null) {
            sb.append(nl).append("LIMIT ").append(m.limit);
        }
        return sb.toString();
    }

    private static String col(QueryModel.ColumnRef c) {
        String base = c.expression ? c.name
                : (c.table != null && !c.table.isBlank() ? c.table + "." + c.name : c.name);
        return c.alias != null && !c.alias.isBlank() ? base + " AS " + c.alias : base;
    }

    /**
     * Аналог QuerySpecification: FROM = таблицы, не участвующие в join'ах, через запятую,
     * плюс цепочка JOIN ... ON ... Если join'ов нет — просто список таблиц.
     */
    private static String fromClause(QueryModel m, String nl) {
        if (m.joins.isEmpty()) {
            return m.from.stream().map(SqlFormatter::table).collect(Collectors.joining(", "));
        }
        // Стартовая таблица: левая из первого join'а.
        StringBuilder sb = new StringBuilder();
        QueryModel.Join first = m.joins.get(0);
        sb.append(tableByRef(m, first.leftTable));
        for (QueryModel.Join j : m.joins) {
            sb.append(nl).append("  ").append(j.type.sql).append(" ")
              .append(tableByRef(m, j.rightTable));
            if (j.type != QueryModel.JoinType.CROSS) {
                sb.append(" ON ")
                  .append(j.leftTable).append(".").append(j.leftColumn)
                  .append(" ").append(j.operator).append(" ")
                  .append(j.rightTable).append(".").append(j.rightColumn);
            }
        }
        return sb.toString();
    }

    private static String tableByRef(QueryModel m, String ref) {
        return m.from.stream()
                .filter(t -> t.reference().equals(ref))
                .findFirst().map(SqlFormatter::table).orElse(ref);
    }

    private static String table(QueryModel.TableRef t) {
        return t.alias != null && !t.alias.isBlank()
                ? t.identifier() + " AS " + t.alias : t.identifier();
    }

    private static String conditions(List<QueryModel.Condition> conds) {
        StringBuilder sb = new StringBuilder();
        boolean firstC = true;
        for (QueryModel.Condition c : conds) {
            if (!firstC) sb.append(" ").append(c.append != null ? c.append : "AND").append(" ");
            firstC = false;
            sb.append(c.left).append(" ").append(c.operator).append(" ").append(c.right);
        }
        return sb.toString();
    }
}
