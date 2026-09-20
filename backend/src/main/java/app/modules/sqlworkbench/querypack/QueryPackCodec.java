package app.modules.sqlworkbench.querypack;

import app.modules.sqlworkbench.querypack.QueryPack.LinkCondition;
import app.modules.sqlworkbench.querypack.QueryPack.LinkType;
import app.modules.sqlworkbench.querypack.QueryPack.PackLink;
import app.modules.sqlworkbench.querypack.QueryPack.PackedQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h2>Текстовая упаковка пакета запросов: «несколько запросов в одной строке».</h2>
 *
 * <p>Читает и пишет структуру {@link QueryPack} в виде обычного SQL-текста, где
 * служебная разметка спрятана в SQL-комментарии {@code --#}. Благодаря этому
 * упакованная строка остаётся читаемой (и почти исполнимой) в любом SQL-редакторе,
 * хранится в одном текстовом реквизите и переносится копипастой.
 *
 * <pre>
 * --#pack Продажи с курсами
 * --#query Sales
 * SELECT d.id, d.amount, d.currency, d.customer_id
 *   FROM deals d
 * --#query Rates
 * SELECT r.currency, r.rate FROM exchange_rates r
 * --#link Sales -&gt; Rates LEFT ON currency = currency
 * --#select Sales.customer_id AS customer, Sales.amount * Rates.rate AS amount_base
 * --#where Sales.amount &gt; 0
 * --#order amount_base DESC
 * --#limit 1000
 * </pre>
 *
 * <p>Директивы:
 * <ul>
 *   <li>{@code --#pack <имя>} — имя пакета (необязательно);</li>
 *   <li>{@code --#query <имя> [| заголовок]} — начало тела запроса; всё до следующей
 *       директивы считается его SQL-текстом;</li>
 *   <li>{@code --#link <источник> -> <приёмник> [INNER|LEFT|RIGHT|FULL|CROSS] ON <усл> [AND <усл>]…}
 *       — настройка связи; безточечное имя колонки слева относится к источнику,
 *       справа — к приёмнику;</li>
 *   <li>{@code --#select}, {@code --#where}, {@code --#group}, {@code --#having},
 *       {@code --#order} — части итогового запроса (директива может повторяться,
 *       элементы разделяются запятой для select/group/order и {@code AND} для where/having);</li>
 *   <li>{@code --#limit <n>}, {@code --#distinct}.</li>
 * </ul>
 *
 * <p>Если разметки в строке нет вовсе, текст трактуется как единственный запрос с
 * именем {@code Query1} — то есть обычный одиночный SELECT остаётся валидным пакетом.
 */
public final class QueryPackCodec {

    private QueryPackCodec() {}

    /** Имя набора: латиница/цифры/подчёркивание, не начинается с цифры. */
    static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*--\\s*#\\s*(\\w+)\\s*(.*)$");

    /** Разбор {@code A -> B [TYPE] ON …} (стрелка допускается как {@code ->}, {@code =>} или {@code :}). */
    private static final Pattern LINK = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(?:->|=>|:)\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*"
                    + "([A-Za-z]+(?:\\s+OUTER)?)?\\s*(?:\\bON\\b\\s*(.*))?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COND = Pattern.compile(
            "^\\s*(.+?)\\s*(<>|!=|>=|<=|=|>|<)\\s*(.+?)\\s*$");

    private static final Pattern AND_SPLIT = Pattern.compile("(?i)\\s+AND\\s+");

    private static final String NL = "\n";

    // ------------------------------------------------------------------ decode

    /** Разбирает упакованную строку. {@code null}/пусто → пустой пакет. */
    public static QueryPack decode(String packed) {
        QueryPack pack = new QueryPack();
        if (packed == null || packed.isBlank()) return pack;

        PackedQuery current = null;
        StringBuilder body = new StringBuilder();
        boolean sawDirective = false;

        for (String line : packed.split("\\r?\\n", -1)) {
            Matcher m = DIRECTIVE.matcher(line);
            if (!m.matches()) {
                body.append(line).append(NL);
                continue;
            }
            sawDirective = true;
            String keyword = m.group(1).toLowerCase(Locale.ROOT);
            String arg = m.group(2) == null ? "" : m.group(2).trim();

            // Любая директива закрывает тело текущего запроса.
            if (current != null) {
                current.sql = trimBody(body);
                current = null;
            }
            body.setLength(0);

            switch (keyword) {
                case "pack" -> pack.name = emptyToNull(arg);
                case "query", "dataset", "set" -> {
                    current = new PackedQuery();
                    int bar = arg.indexOf('|');
                    String nm = bar >= 0 ? arg.substring(0, bar).trim() : arg;
                    if (bar >= 0) current.title = emptyToNull(arg.substring(bar + 1).trim());
                    current.name = nm.isBlank() ? "Query" + (pack.queries.size() + 1) : nm;
                    pack.queries.add(current);
                }
                case "link", "join" -> {
                    PackLink link = decodeLink(arg);
                    if (link != null) pack.links.add(link);
                }
                case "select" -> addCsv(pack.select, arg);
                case "where" -> addAnded(pack.where, arg);
                case "group", "groupby" -> addCsv(pack.groupBy, arg);
                case "having" -> addAnded(pack.having, arg);
                case "order", "orderby" -> addCsv(pack.orderBy, arg);
                case "limit" -> pack.limit = parseIntOrNull(arg);
                case "distinct" -> pack.distinct = arg.isBlank() || Boolean.parseBoolean(arg);
                default -> { /* неизвестная директива — игнорируем, сохраняя совместимость вперёд */ }
            }
        }

        if (current != null) {
            current.sql = trimBody(body);
        } else if (!sawDirective) {
            // Обычный одиночный SELECT без разметки — тоже валидный пакет.
            String sql = trimBody(body);
            if (!sql.isBlank()) pack.queries.add(new PackedQuery("Query1", sql));
        }

        pack.queries.removeIf(q -> q.sql == null || q.sql.isBlank());
        return pack;
    }

    private static PackLink decodeLink(String arg) {
        Matcher m = LINK.matcher(arg);
        if (!m.matches()) return null;
        PackLink link = new PackLink();
        link.source = m.group(1);
        link.target = m.group(2);
        link.type = LinkType.parse(m.group(3), LinkType.LEFT);
        String on = m.group(4);
        if (on != null && !on.isBlank()) {
            List<LinkCondition> conds = new ArrayList<>();
            boolean structured = true;
            for (String part : AND_SPLIT.split(on.trim())) {
                Matcher c = COND.matcher(part);
                if (!c.matches()) { structured = false; break; }
                conds.add(new LinkCondition(c.group(1).trim(), c.group(2), c.group(3).trim()));
            }
            if (structured && !conds.isEmpty()) link.conditions = conds;
            else link.rawCondition = on.trim();
        }
        return link;
    }

    // ------------------------------------------------------------------ encode

    /** Собирает упакованную строку обратно — результат снова читается {@link #decode}. */
    public static String encode(QueryPack pack) {
        if (pack == null) return "";
        StringBuilder sb = new StringBuilder();
        if (pack.name != null && !pack.name.isBlank()) {
            sb.append("--#pack ").append(pack.name.trim()).append(NL);
        }
        for (PackedQuery q : pack.queries == null ? List.<PackedQuery>of() : pack.queries) {
            sb.append("--#query ").append(q.name == null ? "" : q.name);
            if (q.title != null && !q.title.isBlank()) sb.append(" | ").append(q.title.trim());
            sb.append(NL);
            if (q.disabled) sb.append("--#disabled").append(NL);
            sb.append(q.sql == null ? "" : q.sql.strip()).append(NL);
        }
        for (PackLink l : pack.links == null ? List.<PackLink>of() : pack.links) {
            if (l.source == null || l.target == null) continue;
            sb.append("--#link ").append(l.source).append(" -> ").append(l.target)
              .append(' ').append(l.type == null ? LinkType.LEFT : l.type);
            String on = onText(l);
            if (on != null) sb.append(" ON ").append(on);
            sb.append(NL);
        }
        appendList(sb, "select", pack.select, ", ");
        appendList(sb, "where", pack.where, " AND ");
        appendList(sb, "group", pack.groupBy, ", ");
        appendList(sb, "having", pack.having, " AND ");
        appendList(sb, "order", pack.orderBy, ", ");
        if (pack.limit != null) sb.append("--#limit ").append(pack.limit).append(NL);
        if (pack.distinct) sb.append("--#distinct").append(NL);
        return sb.toString();
    }

    /** Текст ON-условия связи ({@code null}, если связь без условий). */
    static String onText(PackLink l) {
        if (l.rawCondition != null && !l.rawCondition.isBlank()) return l.rawCondition.trim();
        if (l.conditions == null || l.conditions.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (LinkCondition c : l.conditions) {
            if (c == null || c.sourceExpr == null || c.targetExpr == null) continue;
            parts.add(c.sourceExpr.trim() + " " + (c.operator == null ? "=" : c.operator) + " " + c.targetExpr.trim());
        }
        return parts.isEmpty() ? null : String.join(" AND ", parts);
    }

    // ------------------------------------------------------------------ helpers

    private static void appendList(StringBuilder sb, String keyword, List<String> items, String sep) {
        if (items == null || items.isEmpty()) return;
        sb.append("--#").append(keyword).append(' ').append(String.join(sep, items)).append(NL);
    }

    private static void addCsv(List<String> target, String arg) {
        for (String p : splitTopLevel(arg, ',')) {
            String v = p.strip();
            if (!v.isEmpty()) target.add(v);
        }
    }

    private static void addAnded(List<String> target, String arg) {
        String v = arg.strip();
        if (!v.isEmpty()) target.add(v);
    }

    /**
     * Разделение по символу верхнего уровня: не режет внутри скобок и кавычек
     * (иначе {@code COALESCE(a, b)} в списке select'а развалился бы на две части).
     */
    static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        int depth = 0;
        char quote = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                cur.append(ch);
                if (ch == quote) quote = 0;
                continue;
            }
            switch (ch) {
                case '\'', '"' -> { quote = ch; cur.append(ch); }
                case '(' -> { depth++; cur.append(ch); }
                case ')' -> { depth--; cur.append(ch); }
                default -> {
                    if (ch == sep && depth == 0) { out.add(cur.toString()); cur.setLength(0); }
                    else cur.append(ch);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String trimBody(StringBuilder body) {
        String s = body.toString().strip();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).strip();
        return s;
    }

    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static Integer parseIntOrNull(String s) {
        try { return Integer.valueOf(s.trim()); } catch (RuntimeException e) { return null; }
    }
}
