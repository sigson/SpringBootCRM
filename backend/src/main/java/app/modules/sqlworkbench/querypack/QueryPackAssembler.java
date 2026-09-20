package app.modules.sqlworkbench.querypack;

import app.modules.sqlworkbench.querypack.QueryPack.LinkCondition;
import app.modules.sqlworkbench.querypack.QueryPack.LinkType;
import app.modules.sqlworkbench.querypack.QueryPack.PackLink;
import app.modules.sqlworkbench.querypack.QueryPack.PackedQuery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <h2>Сборка пакета запросов в один исполнимый SQL по настройке связей.</h2>
 *
 * <p>Каждый набор пакета становится общим табличным выражением (CTE), а связи —
 * цепочкой JOIN'ов между ними:
 *
 * <pre>
 * WITH Sales AS (SELECT …), Rates AS (SELECT …)
 * SELECT Sales.customer_id AS customer, Sales.amount * Rates.rate AS amount_base
 *   FROM Sales
 *   LEFT OUTER JOIN Rates ON Sales.currency = Rates.currency
 *  WHERE Sales.amount &gt; 0
 *  ORDER BY amount_base DESC
 * </pre>
 *
 * <p>CTE выбран сознательно: тексты наборов попадают в итоговый запрос <b>без разбора</b>
 * (модулю не нужен полноценный SQL-парсер), остаются такими, какими их написал
 * пользователь, и любая СУБД сама решает, разворачивать их или материализовать.
 *
 * <h3>Почему CTE называется не так, как набор</h3>
 * Каждое выражение получает служебное имя с префиксом {@value #CTE_PREFIX}, а к имени
 * набора возвращается алиасом ({@code FROM qp_Deals AS Deals}). Это защита от совпадения
 * имени набора с именем реальной таблицы: H2 в таком случае молча берёт таблицу вместо
 * выражения, и запрос либо падает на «лишней» колонке, либо — что хуже — успешно
 * возвращает данные не из того источника. Алиас снимает неоднозначность, а ссылки в
 * выражениях пользователя ({@code Deals.amount}) писать по-прежнему можно по имени набора.
 *
 * <h3>Порядок соединения</h3>
 * Стартовый набор — источник первой связи (а если связей нет — первый набор пакета).
 * Дальше связи применяются в том порядке, в котором становится известна одна из их
 * сторон; связь, подключающая уже известный приёмник к неизвестному источнику,
 * применяется «зеркально» (LEFT ↔ RIGHT), чтобы смысл соединения не менялся.
 * Наборы, не упомянутые ни в одной связи, подключаются CROSS JOIN'ом — с
 * предупреждением в {@link Assembled#warnings()}, потому что декартово произведение
 * почти всегда означает забытую связь.
 */
public final class QueryPackAssembler {

    private QueryPackAssembler() {}

    /** Префикс служебного имени CTE; см. пояснение в описании класса. */
    static final String CTE_PREFIX = "qp_";

    /** Имя набора → имя его CTE. */
    private static String cte(String setName) { return CTE_PREFIX + setName; }

    /** Результат сборки: SQL плюс диагностика для UI. */
    public record Assembled(
            String sql,
            /** Наборы в порядке подключения — тот же порядок использует вывод {@code *}. */
            List<String> joinedQueries,
            /** Непустые предупреждения не блокируют выполнение, но показываются в UI. */
            List<String> warnings) {}

    public static Assembled assemble(QueryPack pack) { return assemble(pack, false); }

    public static Assembled assemble(QueryPack pack, boolean pretty) {
        if (pack == null || pack.enabledQueries().isEmpty()) {
            throw new IllegalArgumentException("The query pack contains no enabled queries");
        }
        List<PackedQuery> queries = pack.enabledQueries();
        List<String> warnings = new ArrayList<>();
        validateNames(queries);

        String nl = pretty ? "\n" : " ";
        StringBuilder sb = new StringBuilder();

        // ---- WITH: по одному CTE на набор ----
        if (queries.size() > 1 || !pack.enabledLinks().isEmpty()) {
            sb.append("WITH ");
            for (int i = 0; i < queries.size(); i++) {
                PackedQuery q = queries.get(i);
                if (i > 0) sb.append(',').append(nl).append(pretty ? "     " : "");
                sb.append(cte(q.name)).append(" AS (").append(nl)
                  .append(stripTrailingSemicolon(q.sql)).append(nl).append(')');
            }
            sb.append(nl);
        }

        JoinPlan plan = plan(pack, queries, warnings);

        // ---- SELECT ----
        sb.append("SELECT ");
        if (pack.distinct) sb.append("DISTINCT ");
        if (pack.select == null || pack.select.isEmpty()) {
            List<String> stars = plan.order.stream().map(n -> n + ".*").toList();
            sb.append(String.join(", ", stars));
        } else {
            sb.append(String.join("," + (pretty ? nl + "       " : " "), pack.select));
        }

        // ---- FROM / JOIN ----
        if (queries.size() == 1 && pack.enabledLinks().isEmpty()) {
            // Единственный набор без связей: оборачивать в CTE незачем — подзапрос короче.
            sb.append(nl).append("FROM (").append(nl)
              .append(stripTrailingSemicolon(queries.get(0).sql)).append(nl)
              .append(") ").append(queries.get(0).name);
        } else {
            sb.append(nl).append("FROM ").append(cte(plan.order.get(0)))
              .append(" AS ").append(plan.order.get(0));
            for (JoinStep step : plan.steps) {
                sb.append(nl).append(pretty ? "  " : "").append(step.type.sql).append(' ')
                  .append(cte(step.table)).append(" AS ").append(step.table);
                if (step.type != LinkType.CROSS && step.on != null && !step.on.isBlank()) {
                    sb.append(" ON ").append(step.on);
                }
            }
        }

        appendJoined(sb, nl, "WHERE", pack.where, " AND ");
        appendJoined(sb, nl, "GROUP BY", pack.groupBy, ", ");
        appendJoined(sb, nl, "HAVING", pack.having, " AND ");
        appendJoined(sb, nl, "ORDER BY", pack.orderBy, ", ");
        if (pack.limit != null && pack.limit > 0) {
            sb.append(nl).append("LIMIT ").append(pack.limit);
        }
        return new Assembled(sb.toString(), plan.order, warnings);
    }

    // ------------------------------------------------------------- планирование

    private record JoinStep(LinkType type, String table, String on) {}

    private record JoinPlan(List<String> order, List<JoinStep> steps) {}

    private static JoinPlan plan(QueryPack pack, List<PackedQuery> queries, List<String> warnings) {
        Set<String> available = new LinkedHashSet<>();
        for (PackedQuery q : queries) available.add(q.name);

        List<PackLink> links = new ArrayList<>();
        for (PackLink l : pack.enabledLinks()) {
            if (l.source == null || l.target == null) continue;
            if (!available.contains(l.source) || !available.contains(l.target)) {
                warnings.add("Link " + l.source + " → " + l.target
                        + " is ignored: one of the datasets is missing or disabled");
                continue;
            }
            if (l.source.equals(l.target)) {
                warnings.add("Link " + l.source + " → " + l.target + " is ignored: a dataset cannot link to itself");
                continue;
            }
            links.add(l);
        }

        List<String> order = new ArrayList<>();
        List<JoinStep> steps = new ArrayList<>();
        Set<String> joined = new HashSet<>();

        String start = links.isEmpty() ? queries.get(0).name : links.get(0).source;
        order.add(start);
        joined.add(start);

        List<PackLink> pending = new ArrayList<>(links);
        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            for (int i = 0; i < pending.size(); i++) {
                PackLink l = pending.get(i);
                boolean haveSource = joined.contains(l.source);
                boolean haveTarget = joined.contains(l.target);
                if (haveSource == haveTarget) continue;   // обе или ни одной — пока не применима

                String added = haveSource ? l.target : l.source;
                LinkType type = haveSource ? nn(l.type) : nn(l.type).mirrored();
                steps.add(new JoinStep(type, added, onClause(l)));
                order.add(added);
                joined.add(added);
                pending.remove(i);
                progress = true;
                break;
            }
        }

        // Связи, обе стороны которых уже соединены, добавляются как дополнительные
        // условия к последнему JOIN'у — иначе они просто потерялись бы.
        for (PackLink l : pending) {
            if (joined.contains(l.source) && joined.contains(l.target)) {
                String extra = onClause(l);
                if (extra != null && !steps.isEmpty()) {
                    JoinStep last = steps.get(steps.size() - 1);
                    steps.set(steps.size() - 1,
                            new JoinStep(last.type(), last.table(),
                                    last.on() == null ? extra : last.on() + " AND " + extra));
                    warnings.add("Link " + l.source + " → " + l.target
                            + " closes a cycle and was applied as an extra ON condition");
                }
            }
        }

        // Наборы без связей — декартово произведение.
        for (PackedQuery q : queries) {
            if (joined.add(q.name)) {
                steps.add(new JoinStep(LinkType.CROSS, q.name, null));
                order.add(q.name);
                warnings.add("Dataset " + q.name + " takes part in no link and is joined with CROSS JOIN");
            }
        }
        return new JoinPlan(order, steps);
    }

    /**
     * Текст ON-условия с квалификацией безточечных имён: слева именем источника,
     * справа — именем приёмника. Так связь пишется коротко ({@code ON currency = currency}),
     * оставаясь однозначной в SQL.
     */
    static String onClause(PackLink l) {
        if (l.rawCondition != null && !l.rawCondition.isBlank()) return l.rawCondition.trim();
        if (l.conditions == null || l.conditions.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (LinkCondition c : l.conditions) {
            if (c == null || isBlank(c.sourceExpr) || isBlank(c.targetExpr)) continue;
            parts.add(qualify(c.sourceExpr, l.source) + " "
                    + (isBlank(c.operator) ? "=" : c.operator.trim()) + " "
                    + qualify(c.targetExpr, l.target));
        }
        return parts.isEmpty() ? null : String.join(" AND ", parts);
    }

    /**
     * Дописывает префикс набора к простому идентификатору. Выражения (со скобками,
     * точкой, кавычками, операторами или литералами) остаются как есть.
     */
    static String qualify(String expr, String setName) {
        String e = expr.trim();
        if (setName == null || setName.isBlank()) return e;
        return QueryPackCodec.NAME.matcher(e).matches() ? setName + "." + e : e;
    }

    private static void validateNames(List<PackedQuery> queries) {
        Set<String> seen = new HashSet<>();
        for (PackedQuery q : queries) {
            if (q.name == null || !QueryPackCodec.NAME.matcher(q.name).matches()) {
                throw new IllegalArgumentException(
                        "Invalid dataset name: «" + q.name + "». Allowed: latin letters, digits and _, "
                                + "not starting with a digit");
            }
            if (!seen.add(q.name)) {
                throw new IllegalArgumentException("Duplicate dataset name: " + q.name);
            }
            if (q.sql == null || q.sql.isBlank()) {
                throw new IllegalArgumentException("Dataset " + q.name + " has an empty query");
            }
        }
    }

    private static void appendJoined(StringBuilder sb, String nl, String keyword,
                                     List<String> items, String sep) {
        if (items == null || items.isEmpty()) return;
        List<String> clean = items.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        if (clean.isEmpty()) return;
        sb.append(nl).append(keyword).append(' ').append(String.join(sep, clean));
    }

    private static String stripTrailingSemicolon(String sql) {
        String s = sql.strip();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).strip();
        return s;
    }

    private static LinkType nn(LinkType t) { return t == null ? LinkType.LEFT : t; }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
