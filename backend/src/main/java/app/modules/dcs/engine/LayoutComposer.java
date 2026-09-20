package app.modules.dcs.engine;

import app.modules.dcs.expression.Expr;
import app.modules.dcs.expression.ExprEvaluator;
import app.modules.dcs.expression.ExprParser;
import app.modules.dcs.model.DcsSchema;
import app.modules.dcs.model.DcsSettings;
import app.modules.sqlworkbench.querypack.QueryPack;
import app.modules.sqlworkbench.querypack.QueryPackAssembler;
import app.modules.sqlworkbench.querypack.QueryPackCodec;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <h2>Компоновщик макета: схема + настройки → исполнимый SQL.</h2>
 *
 * <p>Ровно тот этап, который в 1С называется «компоновщик макета». Здесь решается,
 * какие поля вообще нужны запросу, куда уходит отбор и что считает СУБД, а что —
 * процессор.
 *
 * <h3>Связь с Workbench'ем</h3>
 * Наборы данных схемы хранятся упакованной строкой; компоновщик отдаёт её
 * {@code QueryPackCodec}'у, накладывает сверху настройку связей и получает от
 * {@code QueryPackAssembler} один SQL с CTE и JOIN'ами. То есть «подружить СКД с
 * Workbench'ем» здесь буквально: движок отчётов не пишет JOIN'ы сам, а пользуется
 * сборщиком пакетов как библиотекой.
 *
 * <h3>Параметры вместо склейки</h3>
 * Ни одно пользовательское значение не попадает в текст SQL. Значения отборов
 * подставляются маркерами {@code &_fN}, параметры схемы — своими именами {@code &Имя};
 * финальный проход {@link #bindParameters} меняет все маркеры на {@code ?} слева направо
 * и собирает значения в том же порядке. Инъекция становится невозможной по построению,
 * а не по дисциплине вызывающего.
 *
 * <h3>Два режима агрегации</h3>
 * Если все ресурсы — простые сворачиваемые агрегаты ({@code Сумма}, {@code Количество},
 * {@code Минимум}, {@code Максимум}), детальные записи не выводятся и отбор целиком ушёл
 * в SQL, включается режим {@link Composed#aggregatedInSql()}: СУБД группирует по
 * измерениям, а процессору остаётся сворачивать готовые итоги вверх по иерархии. Иначе
 * запрос отдаёт детальные строки и всё считается в процессоре — это всегда корректно,
 * но дороже.
 */
public final class LayoutComposer {

    /** Результат компоновки макета. */
    public record Composed(
            String sql,
            List<Object> parameters,
            /** Поля-колонки набора, реально выбранные запросом. */
            List<FieldCatalog.FieldInfo> sourceFields,
            /** Измерения, по которым СУБД сгруппировала (пусто в детальном режиме). */
            List<FieldCatalog.FieldInfo> groupedDimensions,
            /**
             * {@code true} — ресурсы уже посчитаны СУБД, и процессор должен не
             * агрегировать исходные значения, а <b>сворачивать</b> готовые.
             */
            boolean aggregatedInSql,
            /**
             * Весь отбор ушёл в SQL. Тогда повторная проверка в процессоре не нужна —
             * а в режиме SQL-агрегации ещё и невозможна: отбираемых колонок в
             * сгруппированных строках уже нет.
             */
            boolean filterPushedDown,
            /** Ресурс → выражение свёртки для режима SQL-агрегации. */
            Map<String, String> rollupExpressions,
            List<String> warnings,
            /** Предел выборки; превышение помечает результат усечённым. */
            int maxRows) {}

    private static final int DEFAULT_MAX_ROWS = 100_000;

    private final DcsSchema schema;
    private final DcsSettings settings;
    private final FieldCatalog catalog;
    private final List<String> warnings = new ArrayList<>();

    public LayoutComposer(DcsSchema schema, DcsSettings settings, FieldCatalog catalog) {
        this.schema = schema;
        this.settings = settings;
        this.catalog = catalog;
    }

    // ------------------------------------------------------------------ compose

    public Composed compose() {
        QueryPack pack = buildPack();

        Set<String> usedIds = new UsageCollector(schema, settings, catalog).collect();

        List<FieldCatalog.FieldInfo> sourceFields = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : usedIds) {
            FieldCatalog.FieldInfo f = catalog.resolve(id);
            if (f != null && f.kind() == FieldCatalog.Kind.SOURCE && seen.add(f.id())) {
                sourceFields.add(f);
            }
        }
        // Обязательные поля включаются, даже если их никто не выбрал.
        for (FieldCatalog.FieldInfo f : catalog.byKind(FieldCatalog.Kind.SOURCE)) {
            if (f.mandatory() && seen.add(f.id())) sourceFields.add(f);
        }
        if (sourceFields.isEmpty()) {
            throw new IllegalStateException(
                    "The settings select no dataset field: there is nothing to query. "
                            + "Add at least one field to the selection or to a grouping.");
        }

        // Отбор: что можно — в SQL, полное дерево всё равно проверит процессор.
        Map<String, Object> markerValues = new LinkedHashMap<>();
        List<String> pushedDown = new ArrayList<>();
        boolean fullyPushed = pushDownFilter(settings.filter, pushedDown, markerValues);
        pack.where.addAll(pushedDown);

        // Поля, по которым группируем, и режим агрегации.
        List<FieldCatalog.FieldInfo> dimensions = groupingDimensions();
        AggregationPlan plan = planAggregation(dimensions, fullyPushed, usedIds);

        if (plan.inSql()) {
            for (FieldCatalog.FieldInfo d : dimensions) {
                pack.select.add(d.sqlRef() + " AS " + d.alias());
                pack.groupBy.add(d.sqlRef());
            }
            plan.selectExpressions().forEach(pack.select::add);
            sourceFields = new ArrayList<>(dimensions);
        } else {
            for (FieldCatalog.FieldInfo f : sourceFields) {
                pack.select.add(f.sqlRef() + " AS " + f.alias());
            }
        }

        // «Игнорировать значения NULL» — отсечение на стороне СУБД.
        for (FieldCatalog.FieldInfo f : sourceFields) {
            if (f.ignoreNull()) pack.where.add(f.sqlRef() + " IS NOT NULL");
        }

        int maxRows = settings.outputParameters != null && settings.outputParameters.maxRows != null
                ? Math.max(1, settings.outputParameters.maxRows)
                : DEFAULT_MAX_ROWS;
        // +1 строка, чтобы отличить «ровно предел» от «было больше».
        pack.limit = maxRows + 1;

        QueryPackAssembler.Assembled assembled = QueryPackAssembler.assemble(pack, true);
        warnings.addAll(assembled.warnings());

        Bound bound = bindParameters(assembled.sql(), markerValues);

        return new Composed(bound.sql(), bound.values(), sourceFields,
                plan.inSql() ? dimensions : List.of(),
                plan.inSql(), fullyPushed, plan.rollups(), List.copyOf(warnings), maxRows);
    }

    /** Пакет запросов: тексты наборов из упакованной строки, связи — из настройки. */
    private QueryPack buildPack() {
        QueryPack pack = QueryPackCodec.decode(schema.packed);
        if (pack.queries.isEmpty()) {
            throw new IllegalStateException(
                    "The composition schema has no datasets: add at least one query on the «Datasets» tab");
        }
        // Пакет мог принести собственные части запроса из ручных директив — компоновка
        // строит их заново из настроек, поэтому старые сбрасываем.
        pack.select = new ArrayList<>();
        pack.where = new ArrayList<>();
        pack.groupBy = new ArrayList<>();
        pack.having = new ArrayList<>();
        pack.orderBy = new ArrayList<>();

        if (schema.links != null && !schema.links.isEmpty()) {
            pack.links = new ArrayList<>();
            for (DcsSchema.DataSetLink l : schema.links) {
                if (l == null || l.disabled || l.source == null || l.target == null) continue;
                QueryPack.PackLink pl = new QueryPack.PackLink();
                pl.source = l.source;
                pl.target = l.target;
                pl.type = QueryPack.LinkType.parse(l.linkType, QueryPack.LinkType.LEFT);
                pl.rawCondition = l.rawCondition;
                pl.conditions = new ArrayList<>();
                if (l.conditions != null) {
                    for (DcsSchema.LinkCondition c : l.conditions) {
                        if (c == null || c.sourceExpr == null || c.targetExpr == null) continue;
                        pl.conditions.add(new QueryPack.LinkCondition(
                                stripSetPrefix(c.sourceExpr, l.source),
                                c.operator,
                                stripSetPrefix(c.targetExpr, l.target)));
                    }
                }
                pack.links.add(pl);
            }
        }
        return pack;
    }

    /**
     * Конструктор хранит условия связи полными путями ({@code Sales.item}), а сборщик
     * пакетов сам квалифицирует безточечные имена именем своей стороны. Снимаем
     * «свой» префикс, чтобы не получить {@code Sales.Sales.item}.
     */
    private static String stripSetPrefix(String expr, String setName) {
        String e = expr.trim();
        String prefix = setName + ".";
        return e.regionMatches(true, 0, prefix, 0, prefix.length())
                ? e.substring(prefix.length())
                : e;
    }

    // ------------------------------------------------------------------- отбор

    /**
     * Проталкивает в SQL конъюнктивные условия верхнего уровня, ссылающиеся только на
     * колонки наборов. Возвращает {@code true}, если в SQL ушёл <b>весь</b> отбор, —
     * только тогда безопасно включать агрегацию в СУБД.
     */
    private boolean pushDownFilter(DcsSettings.FilterGroup group,
                                   List<String> out, Map<String, Object> markers) {
        if (group == null || group.items == null || group.items.isEmpty()) return true;
        if (!"and".equalsIgnoreCase(group.combinator)) return false;

        boolean all = true;
        for (DcsSettings.FilterNode node : group.items) {
            if (node == null || node.disabled) continue;
            if (node.isGroup()) { all = false; continue; }
            FieldCatalog.FieldInfo f = catalog.resolve(node.left);
            if (f == null || f.kind() != FieldCatalog.Kind.SOURCE) { all = false; continue; }
            String sql = conditionSql(f, node, markers);
            if (sql == null) { all = false; continue; }
            out.add(sql);
        }
        return all;
    }

    /** Одно условие отбора в SQL; {@code null} — вид сравнения непереводим. */
    private String conditionSql(FieldCatalog.FieldInfo f, DcsSettings.FilterNode node,
                                Map<String, Object> markers) {
        String col = f.sqlRef();
        Object raw = resolveRight(node.right);

        switch (node.op == null ? "" : node.op) {
            case DcsSettings.CompareOp.FILLED -> { return col + " IS NOT NULL"; }
            case DcsSettings.CompareOp.NOT_FILLED -> { return col + " IS NULL"; }
            case DcsSettings.CompareOp.EQ -> { return col + " = " + marker(markers, coerce(raw, f)); }
            case DcsSettings.CompareOp.NE -> {
                // NULL в SQL «не равен» ничему, поэтому отрицание должно пропускать пустые.
                return "(" + col + " <> " + marker(markers, coerce(raw, f)) + " OR " + col + " IS NULL)";
            }
            case DcsSettings.CompareOp.GT -> { return col + " > " + marker(markers, coerce(raw, f)); }
            case DcsSettings.CompareOp.GE -> { return col + " >= " + marker(markers, coerce(raw, f)); }
            case DcsSettings.CompareOp.LT -> { return col + " < " + marker(markers, coerce(raw, f)); }
            case DcsSettings.CompareOp.LE -> { return col + " <= " + marker(markers, coerce(raw, f)); }
            case DcsSettings.CompareOp.CONTAINS -> {
                return "LOWER(CAST(" + col + " AS VARCHAR)) LIKE "
                        + marker(markers, "%" + lower(raw) + "%");
            }
            case DcsSettings.CompareOp.NOT_CONTAINS -> {
                return "(LOWER(CAST(" + col + " AS VARCHAR)) NOT LIKE "
                        + marker(markers, "%" + lower(raw) + "%") + " OR " + col + " IS NULL)";
            }
            case DcsSettings.CompareOp.BEGINS_WITH -> {
                return "LOWER(CAST(" + col + " AS VARCHAR)) LIKE " + marker(markers, lower(raw) + "%");
            }
            case DcsSettings.CompareOp.IN, DcsSettings.CompareOp.NOT_IN -> {
                List<Object> values = asList(raw);
                if (values.isEmpty()) {
                    // Пустой список: «в списке» не выполняется никогда, «не в списке» — всегда.
                    return DcsSettings.CompareOp.IN.equals(node.op) ? "1 = 0" : "1 = 1";
                }
                List<String> ph = new ArrayList<>();
                for (Object v : values) ph.add(marker(markers, coerce(v, f)));
                String in = col + (DcsSettings.CompareOp.IN.equals(node.op) ? " IN (" : " NOT IN (")
                        + String.join(", ", ph) + ")";
                return DcsSettings.CompareOp.IN.equals(node.op) ? in : "(" + in + " OR " + col + " IS NULL)";
            }
            case DcsSettings.CompareOp.BETWEEN -> {
                List<Object> bounds = asList(raw);
                if (bounds.size() < 2) return null;
                return col + " BETWEEN " + marker(markers, coerce(bounds.get(0), f))
                        + " AND " + marker(markers, coerce(bounds.get(1), f));
            }
            default -> { return null; }
        }
    }

    /** Разворачивает {@code &Параметр} в значение из параметров данных. */
    private Object resolveRight(Object right) {
        if (right instanceof String s && s.startsWith("&")) {
            return parameterValue(s.substring(1));
        }
        return right;
    }

    private Object parameterValue(String name) {
        if (settings.dataParameters != null && settings.dataParameters.containsKey(name)) {
            return settings.dataParameters.get(name);
        }
        DcsSchema.Parameter p = schema.parameter(name);
        return p == null ? null : p.value;
    }

    private static String lower(Object v) {
        return v == null ? "" : v.toString().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> l) return (List<Object>) l;
        if (raw instanceof Object[] a) return List.of(a);
        return List.of(raw);
    }

    /** Приведение значения отбора к типу поля — иначе строгие драйверы отвергнут bind. */
    static Object coerce(Object value, FieldCatalog.FieldInfo field) {
        if (value == null) return null;
        String type = field.valueType() == null ? "" : field.valueType().toLowerCase(Locale.ROOT);
        try {
            return switch (type) {
                case "number", "decimal", "integer" -> value instanceof BigDecimal
                        ? value : new BigDecimal(value.toString().trim());
                case "boolean" -> value instanceof Boolean
                        ? value : Boolean.valueOf(value.toString().trim());
                case "date", "datetime" -> toTimestamp(value);
                default -> value;
            };
        } catch (RuntimeException e) {
            // Не смогли привести — отдаём как есть: пусть решает СУБД, а не молчаливый null.
            return value;
        }
    }

    private static Object toTimestamp(Object value) {
        if (value instanceof Timestamp || value instanceof java.sql.Date) return value;
        if (value instanceof LocalDateTime dt) return Timestamp.valueOf(dt);
        if (value instanceof LocalDate d) return Timestamp.valueOf(d.atStartOfDay());
        String s = value.toString().trim();
        if (s.length() <= 10) return Timestamp.valueOf(LocalDate.parse(s).atStartOfDay());
        return Timestamp.valueOf(LocalDateTime.parse(s.replace(' ', 'T')));
    }

    // -------------------------------------------------------------- агрегация

    private record AggregationPlan(boolean inSql, List<String> selectExpressions,
                                   Map<String, String> rollups) {}

    private List<FieldCatalog.FieldInfo> groupingDimensions() {
        List<FieldCatalog.FieldInfo> dims = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        collectGroupingFields(settings.structure, dims, seen);
        return dims;
    }

    private void collectGroupingFields(List<DcsSettings.StructureNode> nodes,
                                       List<FieldCatalog.FieldInfo> out, Set<String> seen) {
        if (nodes == null) return;
        for (DcsSettings.StructureNode n : nodes) {
            if (n == null || n.disabled) continue;
            if (n.field != null && !n.field.isBlank()) {
                FieldCatalog.FieldInfo f = catalog.resolve(n.field);
                if (f != null && f.kind() == FieldCatalog.Kind.SOURCE && seen.add(f.id())) out.add(f);
            }
            collectGroupingFields(n.children, out, seen);
            collectGroupingFields(n.rows, out, seen);
            collectGroupingFields(n.columns, out, seen);
        }
    }

    /**
     * Решает, можно ли доверить агрегацию СУБД. Условия намеренно строгие: любая
     * неоднозначность толкает в детальный режим, который считает то же самое, просто
     * медленнее. Ошибиться в сторону «медленно, но верно» здесь дешевле.
     */
    private AggregationPlan planAggregation(List<FieldCatalog.FieldInfo> dimensions,
                                            boolean filterFullyPushed, Set<String> usedIds) {
        List<String> selects = new ArrayList<>();
        Map<String, String> rollups = new LinkedHashMap<>();

        if (dimensions.isEmpty()) return new AggregationPlan(false, selects, rollups);
        if (!filterFullyPushed) {
            warnings.add("The filter cannot be fully pushed into SQL, so totals are computed "
                    + "over detail records");
            return new AggregationPlan(false, selects, rollups);
        }
        if (hasDetailGrouping(settings.structure)) return new AggregationPlan(false, selects, rollups);

        // Вычисляемое или пользовательское поле в выводе означает построчный расчёт.
        for (String id : usedIds) {
            FieldCatalog.FieldInfo f = catalog.resolve(id);
            if (f != null && (f.kind() == FieldCatalog.Kind.CALCULATED || f.kind() == FieldCatalog.Kind.USER)) {
                return new AggregationPlan(false, selects, rollups);
            }
        }

        List<FieldCatalog.FieldInfo> resources = catalog.byKind(FieldCatalog.Kind.RESOURCE);
        if (resources.isEmpty()) return new AggregationPlan(false, selects, rollups);

        for (FieldCatalog.FieldInfo r : resources) {
            Expr parsed = ExprParser.parseQuietly(r.expression());
            if (!(parsed instanceof Expr.Call call) || call.args().size() != 1) {
                return new AggregationPlan(false, selects, rollups);
            }
            String fn = ExprEvaluator.canonical(call.name());
            String sqlFn;
            String rollupFn;
            switch (fn) {
                // Сворачиваемые: итог по итогам равен итогу по записям.
                case "SUM" -> { sqlFn = "SUM"; rollupFn = "Сумма"; }
                case "COUNT" -> { sqlFn = "COUNT"; rollupFn = "Сумма"; }
                case "MIN" -> { sqlFn = "MIN"; rollupFn = "Минимум"; }
                case "MAX" -> { sqlFn = "MAX"; rollupFn = "Максимум"; }
                // Среднее и «количество различных» по группам свернуть нельзя.
                default -> { return new AggregationPlan(false, selects, rollups); }
            }
            String inner = sqlOf(call.args().get(0));
            if (inner == null) return new AggregationPlan(false, selects, rollups);
            selects.add(sqlFn + "(" + inner + ") AS " + r.id());
            rollups.put(r.id(), rollupFn + "(" + r.id() + ")");
        }
        return new AggregationPlan(true, selects, rollups);
    }

    private boolean hasDetailGrouping(List<DcsSettings.StructureNode> nodes) {
        if (nodes == null) return false;
        for (DcsSettings.StructureNode n : nodes) {
            if (n == null || n.disabled) continue;
            if (!DcsSettings.StructureNode.KIND_CHART.equals(n.kind)
                    && (n.field == null || n.field.isBlank())) return true;
            if (hasDetailGrouping(n.children) || hasDetailGrouping(n.rows) || hasDetailGrouping(n.columns)) {
                return true;
            }
        }
        return false;
    }

    /** Перевод простого выражения в SQL; {@code null} — выражение не переводимо. */
    private String sqlOf(Expr e) {
        return switch (e) {
            case Expr.Field f -> {
                FieldCatalog.FieldInfo info = catalog.resolve(f.path());
                yield info != null && info.kind() == FieldCatalog.Kind.SOURCE ? info.sqlRef() : null;
            }
            case Expr.Lit l -> l.value() == null ? "NULL"
                    : (l.value() instanceof BigDecimal n ? n.toPlainString() : null);
            case Expr.Binary b -> {
                if (!List.of("+", "-", "*", "/").contains(b.op())) yield null;
                String left = sqlOf(b.left());
                String right = sqlOf(b.right());
                yield left == null || right == null ? null : "(" + left + " " + b.op() + " " + right + ")";
            }
            case null, default -> null;
        };
    }

    // ----------------------------------------------------------- параметризация

    private record Bound(String sql, List<Object> values) {}

    private String marker(Map<String, Object> markers, Object value) {
        String name = "_f" + markers.size();
        markers.put(name, value);
        return "&" + name;
    }

    /**
     * Финальный проход: каждый {@code &Имя} в собранном SQL заменяется на {@code ?},
     * значение берётся из маркеров отбора или из параметров схемы. Сам проход живёт в
     * {@link ParameterBinder} — его же использует автозаполнение полей и предпросмотр
     * набора, чтобы текст набора исполнялся одинаково на всех трёх путях.
     */
    private Bound bindParameters(String sql, Map<String, Object> markers) {
        ParameterBinder.Bound bound = ParameterBinder.bind(
                sql,
                name -> markers.containsKey(name) ? markers.get(name) : parameterValue(name),
                name -> {
                    if (markers.containsKey(name)) return;   // маркер отбора со значением null — это нормально
                    DcsSchema.Parameter p = schema.parameter(name);
                    if (p != null && p.required) {
                        throw new IllegalArgumentException(
                                "Parameter «" + (p.title == null ? name : p.title) + "» has no value");
                    }
                    warnings.add("Parameter &" + name + " has no value and is passed as NULL");
                });
        return new Bound(bound.sql(), bound.values());
    }

}
