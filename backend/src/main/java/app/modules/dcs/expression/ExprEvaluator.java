package app.modules.dcs.expression;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <h2>Вычислитель выражений компоновки.</h2>
 *
 * <p>Чистый интерпретатор дерева {@link Expr}: всё, что зависит от места вычисления
 * (текущая строка, множество записей группировки, параметры), приходит из
 * {@link EvalContext}.
 *
 * <p>Имена функций распознаются и по-русски, и по-английски и не зависят от регистра.
 * Числа считаются в {@link BigDecimal} — денежные итоги не должны накапливать ошибку
 * double'а; деление выполняется с 10 знаками и半 округлением.
 *
 * <p>Неизвестная функция — ошибка {@link EvalException} с именем функции: лучше явно
 * сказать «нет такой», чем молча вернуть null и отдать неверный итог.
 */
public final class ExprEvaluator {

    /** Ошибка вычисления — показывается пользователю рядом с выражением. */
    public static class EvalException extends RuntimeException {
        public EvalException(String message) { super(message); }
    }

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final int DIV_SCALE = 10;

    private final EvalContext ctx;

    public ExprEvaluator(EvalContext ctx) { this.ctx = ctx; }

    public Object eval(Expr e) {
        if (e == null) return null;
        return switch (e) {
            case Expr.Lit l -> l.value();
            case Expr.Field f -> ctx.field(f.path());
            case Expr.Param p -> ctx.parameter(p.name());
            case Expr.Unary u -> unary(u);
            case Expr.Binary b -> binary(b);
            case Expr.Call c -> call(c);
            case Expr.Case c -> caseExpr(c);
            case Expr.In i -> in(i);
            case Expr.Between b -> between(b);
            case Expr.IsNull n -> {
                boolean isNull = eval(n.value()) == null;
                yield n.negated() != isNull;
            }
        };
    }

    /** Вычисление с приведением к логическому значению — для отборов и условий. */
    public boolean evalBoolean(Expr e) { return truthy(eval(e)); }

    // ---------------------------------------------------------------- операции

    private Object unary(Expr.Unary u) {
        Object v = eval(u.operand());
        return switch (u.op()) {
            case "-" -> {
                BigDecimal n = toNumber(v);
                yield n == null ? null : n.negate();
            }
            case "NOT" -> !truthy(v);
            default -> throw new EvalException("Unsupported unary operation: " + u.op());
        };
    }

    private Object binary(Expr.Binary b) {
        // Логические — с коротким замыканием.
        if ("AND".equals(b.op())) return truthy(eval(b.left())) && truthy(eval(b.right()));
        if ("OR".equals(b.op()))  return truthy(eval(b.left())) || truthy(eval(b.right()));

        Object l = eval(b.left());
        Object r = eval(b.right());

        switch (b.op()) {
            case "+" -> {
                // «+» над строками — конкатенация, как в языке выражений 1С.
                if (l instanceof String || r instanceof String) {
                    return asText(l) + asText(r);
                }
                return arith(l, r, BigDecimal::add);
            }
            case "-" -> { return arith(l, r, BigDecimal::subtract); }
            case "*" -> { return arith(l, r, BigDecimal::multiply); }
            case "/" -> {
                BigDecimal rn = toNumber(r);
                BigDecimal ln = toNumber(l);
                if (ln == null || rn == null || rn.signum() == 0) return null;   // деление на 0 → пусто
                return ln.divide(rn, DIV_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
            }
            case "%" -> {
                BigDecimal rn = toNumber(r);
                BigDecimal ln = toNumber(l);
                if (ln == null || rn == null || rn.signum() == 0) return null;
                return ln.remainder(rn, MC);
            }
            case "=" -> { return compareEq(l, r); }
            case "<>" -> { return !compareEq(l, r); }
            case ">" -> { return cmp(l, r) > 0; }
            case ">=" -> { return cmp(l, r) >= 0; }
            case "<" -> { return cmp(l, r) < 0; }
            case "<=" -> { return cmp(l, r) <= 0; }
            case "LIKE" -> { return like(asText(l), asText(r)); }
            default -> throw new EvalException("Unsupported operation: " + b.op());
        }
    }

    private Object caseExpr(Expr.Case c) {
        for (Expr.Branch br : c.branches()) {
            if (truthy(eval(br.when()))) return eval(br.then());
        }
        return c.otherwise() == null ? null : eval(c.otherwise());
    }

    private Object in(Expr.In i) {
        Object v = eval(i.value());
        boolean found = false;
        for (Expr opt : i.options()) {
            Object o = eval(opt);
            if (o instanceof List<?> list) {
                for (Object item : list) if (compareEq(v, item)) { found = true; break; }
            } else if (compareEq(v, o)) {
                found = true;
            }
            if (found) break;
        }
        return i.negated() != found;
    }

    private Object between(Expr.Between b) {
        Object v = eval(b.value());
        if (v == null) return false;
        return cmp(v, eval(b.low())) >= 0 && cmp(v, eval(b.high())) <= 0;
    }

    // --------------------------------------------------------------- функции

    private Object call(Expr.Call c) {
        String fn = canonical(c.name());
        List<Expr> args = c.args();

        // --- агрегаты по записям текущей области ---
        switch (fn) {
            case "SUM" -> { return aggregateSum(arg(args, 0)); }
            case "COUNT" -> { return BigDecimal.valueOf(countNonNull(arg(args, 0))); }
            case "COUNTDISTINCT" -> { return BigDecimal.valueOf(distinctValues(arg(args, 0)).size()); }
            case "MIN" -> { return extremum(arg(args, 0), true); }
            case "MAX" -> { return extremum(arg(args, 0), false); }
            case "AVG" -> {
                List<Object> values = collect(arg(args, 0));
                List<BigDecimal> nums = new ArrayList<>();
                for (Object v : values) {
                    BigDecimal n = toNumber(v);
                    if (n != null) nums.add(n);
                }
                if (nums.isEmpty()) return null;
                BigDecimal sum = BigDecimal.ZERO;
                for (BigDecimal n : nums) sum = sum.add(n);
                return sum.divide(BigDecimal.valueOf(nums.size()), DIV_SCALE, RoundingMode.HALF_UP)
                          .stripTrailingZeros();
            }
            default -> { }
        }

        // --- контекст компоновки ---
        switch (fn) {
            case "LEVEL" -> { return BigDecimal.valueOf(ctx.level()); }
            case "RECORDNUMBER" -> { return BigDecimal.valueOf(ctx.recordNumber()); }
            case "EVALEXPRESSION" -> {
                String expression = asText(eval(arg(args, 0)));
                String grouping = args.size() > 1 ? asText(eval(args.get(1))) : null;
                String area = args.size() > 2 ? asText(eval(args.get(2))) : null;
                return ctx.evaluateInScope(expression, grouping, area);
            }
            default -> { }
        }

        // --- скалярные функции над значением ---
        Object a0 = args.isEmpty() ? null : eval(args.get(0));
        switch (fn) {
            case "PRESENTATION", "STRING" -> { return asText(a0); }
            case "NUMBER" -> { return toNumber(a0); }
            case "ISNULL" -> { return a0 != null ? a0 : (args.size() > 1 ? eval(args.get(1)) : null); }
            case "FORMAT" -> { return asText(a0); }   // маску применяет вывод, значение не искажаем
            case "TRIM" -> { return a0 == null ? null : asText(a0).strip(); }
            case "UPPER" -> { return a0 == null ? null : asText(a0).toUpperCase(Locale.ROOT); }
            case "LOWER" -> { return a0 == null ? null : asText(a0).toLowerCase(Locale.ROOT); }
            case "STRLEN" -> { return a0 == null ? BigDecimal.ZERO : BigDecimal.valueOf(asText(a0).length()); }
            case "SUBSTRING" -> {
                if (a0 == null) return null;
                String s = asText(a0);
                int from = args.size() > 1 ? intOf(eval(args.get(1)), 1) : 1;
                int len = args.size() > 2 ? intOf(eval(args.get(2)), s.length()) : s.length();
                int start = Math.max(0, Math.min(s.length(), from - 1));
                int end = Math.max(start, Math.min(s.length(), start + Math.max(0, len)));
                return s.substring(start, end);
            }
            case "YEAR" -> { return datePart(a0, "year"); }
            case "QUARTER" -> { return datePart(a0, "quarter"); }
            case "MONTH" -> { return datePart(a0, "month"); }
            case "DAY" -> { return datePart(a0, "day"); }
            case "BEGINOFPERIOD" -> { return periodBoundary(a0, args.size() > 1 ? asText(eval(args.get(1))) : "day", true); }
            case "ENDOFPERIOD" -> { return periodBoundary(a0, args.size() > 1 ? asText(eval(args.get(1))) : "day", false); }
            case "ROUND" -> {
                BigDecimal n = toNumber(a0);
                int digits = args.size() > 1 ? intOf(eval(args.get(1)), 0) : 0;
                return n == null ? null : n.setScale(digits, RoundingMode.HALF_UP);
            }
            default -> throw new EvalException("Unknown function: " + c.name());
        }
    }

    // ------------------------------------------------------- агрегатные помощники

    private Expr arg(List<Expr> args, int i) {
        if (args.size() <= i) throw new EvalException("Aggregate function requires an argument");
        return args.get(i);
    }

    /**
     * Значения аргумента по всем записям области. Каждая запись подставляется как
     * текущая строка, поэтому агрегировать можно не только поле, но и выражение:
     * {@code Сумма(Sales.qty * Sales.price)}.
     */
    private List<Object> collect(Expr argument) {
        List<Map<String, Object>> rows = ctx.rows();
        List<Object> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            ExprEvaluator rowEval = new ExprEvaluator(new RowContext(ctx, row));
            out.add(rowEval.eval(argument));
        }
        return out;
    }

    private BigDecimal aggregateSum(Expr argument) {
        BigDecimal sum = null;
        for (Object v : collect(argument)) {
            BigDecimal n = toNumber(v);
            if (n == null) continue;
            sum = sum == null ? n : sum.add(n);
        }
        return sum;
    }

    private long countNonNull(Expr argument) {
        return collect(argument).stream().filter(java.util.Objects::nonNull).count();
    }

    private Set<Object> distinctValues(Expr argument) {
        Set<Object> seen = new LinkedHashSet<>();
        for (Object v : collect(argument)) if (v != null) seen.add(normalizeKey(v));
        return seen;
    }

    private Object extremum(Expr argument, boolean min) {
        Object best = null;
        for (Object v : collect(argument)) {
            if (v == null) continue;
            if (best == null) { best = v; continue; }
            int c = cmp(v, best);
            if (min ? c < 0 : c > 0) best = v;
        }
        return best;
    }

    /** Контекст-обёртка: поля берутся из конкретной строки, остальное — из внешнего. */
    private record RowContext(EvalContext outer, Map<String, Object> row) implements EvalContext {
        @Override public Object field(String path) {
            if (row.containsKey(path)) return row.get(path);
            return outer.field(path);
        }
        @Override public Object parameter(String name) { return outer.parameter(name); }
        @Override public List<Map<String, Object>> rows() { return List.of(row); }
        @Override public int level() { return outer.level(); }
        @Override public int recordNumber() { return outer.recordNumber(); }
        @Override public Object evaluateInScope(String e, String g, String a) {
            return outer.evaluateInScope(e, g, a);
        }
    }

    // ------------------------------------------------------------- приведения

    /** Нормализация в «истину»: null → false, число ≠ 0, непустая строка кроме «ложь»/«false». */
    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof BigDecimal n) return n.signum() != 0;
        if (v instanceof Number n) return n.doubleValue() != 0;
        String s = v.toString().trim();
        return !(s.isEmpty() || s.equalsIgnoreCase("false") || s.equalsIgnoreCase("ложь"));
    }

    /** Число из любого значения; {@code null}, если это не число. */
    public static BigDecimal toNumber(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    public static String asText(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        return v.toString();
    }

    private static int intOf(Object v, int fallback) {
        BigDecimal n = toNumber(v);
        return n == null ? fallback : n.intValue();
    }

    /** Ключ для сравнения на равенство: числа сводятся к масштабу, остальное — к строке. */
    private static Object normalizeKey(Object v) {
        BigDecimal n = toNumber(v);
        if (n != null && !(v instanceof String)) return n.stripTrailingZeros();
        return v instanceof String ? v : String.valueOf(v);
    }

    private static boolean compareEq(Object l, Object r) {
        if (l == null || r == null) return l == r;
        BigDecimal ln = toNumber(l);
        BigDecimal rn = toNumber(r);
        if (ln != null && rn != null && !(l instanceof String && r instanceof String)) {
            return ln.compareTo(rn) == 0;
        }
        return asText(l).equals(asText(r));
    }

    /** Сравнение: числа как числа, даты как даты, всё прочее — лексикографически. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int cmp(Object l, Object r) {
        if (l == null && r == null) return 0;
        if (l == null) return -1;
        if (r == null) return 1;
        BigDecimal ln = toNumber(l);
        BigDecimal rn = toNumber(r);
        if (ln != null && rn != null) return ln.compareTo(rn);
        if (l instanceof Comparable && l.getClass() == r.getClass()) {
            return ((Comparable) l).compareTo(r);
        }
        return asText(l).compareTo(asText(r));
    }

    private static Object arith(Object l, Object r, java.util.function.BinaryOperator<BigDecimal> op) {
        BigDecimal ln = toNumber(l);
        BigDecimal rn = toNumber(r);
        if (ln == null && rn == null) return null;
        // Пустое слагаемое трактуем как 0 — иначе один NULL обнулял бы всю сумму.
        return op.apply(ln == null ? BigDecimal.ZERO : ln, rn == null ? BigDecimal.ZERO : rn);
    }

    /** Шаблон {@code ПОДОБНО}: {@code %} — любая подстрока, {@code _} — один символ. */
    private static boolean like(String value, String pattern) {
        StringBuilder rx = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '%' -> rx.append(".*");
                case '_' -> rx.append('.');
                default -> rx.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        rx.append("$");
        return java.util.regex.Pattern.compile(rx.toString(),
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                .matcher(value).matches();
    }

    // --------------------------------------------------------------- даты

    private static LocalDateTime toDateTime(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime dt) return dt;
        if (v instanceof LocalDate d) return d.atStartOfDay();
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        if (v instanceof java.util.Date d) {
            return LocalDateTime.ofInstant(d.toInstant(), java.time.ZoneId.systemDefault());
        }
        if (v instanceof java.time.Instant i) {
            return LocalDateTime.ofInstant(i, java.time.ZoneId.systemDefault());
        }
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try { return LocalDateTime.parse(s.replace(' ', 'T')); } catch (RuntimeException ignored) { }
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s).atStartOfDay(); }
        catch (RuntimeException ignored) { return null; }
    }

    private static Object datePart(Object v, String part) {
        LocalDateTime dt = toDateTime(v);
        if (dt == null) return null;
        return switch (part) {
            case "year" -> BigDecimal.valueOf(dt.getYear());
            case "quarter" -> BigDecimal.valueOf(dt.get(IsoFields.QUARTER_OF_YEAR));
            case "month" -> BigDecimal.valueOf(dt.getMonthValue());
            case "day" -> BigDecimal.valueOf(dt.getDayOfMonth());
            default -> null;
        };
    }

    private static Object periodBoundary(Object v, String kind, boolean begin) {
        LocalDateTime dt = toDateTime(v);
        if (dt == null) return null;
        String k = kind == null ? "day" : canonicalPeriod(kind);
        LocalDate d = dt.toLocalDate();
        LocalDate from = switch (k) {
            case "year" -> d.withDayOfYear(1);
            case "quarter" -> d.withDayOfMonth(1).withMonth(((d.getMonthValue() - 1) / 3) * 3 + 1);
            case "month" -> d.withDayOfMonth(1);
            case "week" -> d.minusDays(d.getDayOfWeek().getValue() - 1L);
            default -> d;
        };
        if (begin) return from.atStartOfDay();
        LocalDate to = switch (k) {
            case "year" -> from.plusYears(1);
            case "quarter" -> from.plusMonths(3);
            case "month" -> from.plusMonths(1);
            case "week" -> from.plusWeeks(1);
            default -> from.plusDays(1);
        };
        return to.atStartOfDay().minus(1, ChronoUnit.MILLIS);
    }

    private static String canonicalPeriod(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "год", "year" -> "year";
            case "квартал", "quarter" -> "quarter";
            case "месяц", "month" -> "month";
            case "неделя", "week" -> "week";
            default -> "day";
        };
    }

    /** Каноническое имя функции: регистр и язык написания значения не имеют. */
    public static String canonical(String name) {
        String n = name.trim().toUpperCase(Locale.ROOT);
        return switch (n) {
            case "СУММА" -> "SUM";
            case "КОЛИЧЕСТВО" -> "COUNT";
            case "КОЛИЧЕСТВОРАЗЛИЧНЫХ" -> "COUNTDISTINCT";
            case "МИНИМУМ" -> "MIN";
            case "МАКСИМУМ" -> "MAX";
            case "СРЕДНЕЕ" -> "AVG";
            case "ПРЕДСТАВЛЕНИЕ" -> "PRESENTATION";
            case "СТРОКА" -> "STRING";
            case "ЧИСЛО" -> "NUMBER";
            case "ЕСТЬNULL", "ЕСТЬNULL_" -> "ISNULL";
            case "ФОРМАТ" -> "FORMAT";
            case "СОКРЛП" -> "TRIM";
            case "ВРЕГ" -> "UPPER";
            case "НРЕГ" -> "LOWER";
            case "ДЛИНАСТРОКИ" -> "STRLEN";
            case "ПОДСТРОКА" -> "SUBSTRING";
            case "ГОД" -> "YEAR";
            case "КВАРТАЛ" -> "QUARTER";
            case "МЕСЯЦ" -> "MONTH";
            case "ДЕНЬ" -> "DAY";
            case "НАЧАЛОПЕРИОДА" -> "BEGINOFPERIOD";
            case "КОНЕЦПЕРИОДА" -> "ENDOFPERIOD";
            case "ОКРУГЛ", "ОКРУГЛИТЬ" -> "ROUND";
            case "УРОВЕНЬ" -> "LEVEL";
            case "НОМЕРПОПОРЯДКУ" -> "RECORDNUMBER";
            case "ВЫЧИСЛИТЬВЫРАЖЕНИЕ" -> "EVALEXPRESSION";
            default -> n;
        };
    }

    /** Является ли вызов простым агрегатом — этим пользуется проталкивание GROUP BY в SQL. */
    public static boolean isAggregateFunction(String name) {
        return switch (canonical(name)) {
            case "SUM", "COUNT", "COUNTDISTINCT", "MIN", "MAX", "AVG" -> true;
            default -> false;
        };
    }
}
