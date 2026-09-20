package app.modules.dcs.engine;

import app.modules.dcs.expression.EvalContext;
import app.modules.dcs.expression.Expr;
import app.modules.dcs.expression.ExprEvaluator;
import app.modules.dcs.expression.ExprParser;
import app.modules.dcs.model.CompositionResult;
import app.modules.dcs.model.DcsSchema;
import app.modules.dcs.model.DcsSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <h2>Процессор компоновки: строки запроса → дерево итогов.</h2>
 *
 * <p>Второй этап конвейера. Получив плоские записи, процессор делает всё, чего SQL не
 * умеет или не должен уметь: вычисляемые и пользовательские поля, ресурсы по каждому
 * уровню группировки, общие итоги, функции уровня группировки
 * ({@code ВычислитьВыражение}), условное оформление и данные расшифровки.
 *
 * <p>Ключевое решение — <b>итоги считаются выражением, а не накоплением</b>. Каждый
 * уровень вызывает вычислитель над своим множеством записей, поэтому произвольный
 * ресурс вида {@code Сумма(Продажи.Сумма) / Сумма(Продажи.Количество)} на итоге даёт
 * правильное значение, а не сумму частных — классическая ошибка «наращиваемых» итогов.
 * Когда агрегацию выполнила СУБД, множеством записей становятся её группы, а
 * выражением — свёртка ({@code Сумма(ресурс)}), и алгоритм не меняется.
 */
public final class CompositionProcessor {

    /** Области применения условного оформления. */
    private static final String AREA_DETAILS = "details";
    private static final String AREA_GROUP_TOTALS = "groupTotals";
    private static final String AREA_GRAND_TOTAL = "grandTotal";

    private final DcsSchema schema;
    private final DcsSettings settings;
    private final FieldCatalog catalog;
    private final LayoutComposer.Composed layout;
    private final List<String> warnings = new ArrayList<>();

    /** Разобранные выражения — одно выражение разбирается один раз на всю компоновку. */
    private final Map<String, Expr> exprCache = new LinkedHashMap<>();

    private List<Map<String, Object>> allRows = List.of();

    public CompositionProcessor(DcsSchema schema, DcsSettings settings,
                                FieldCatalog catalog, LayoutComposer.Composed layout) {
        this.schema = schema;
        this.settings = settings;
        this.catalog = catalog;
        this.layout = layout;
    }

    // ------------------------------------------------------------------- запуск

    /**
     * Собирает результат из строк запроса.
     *
     * @param columnLabels метки колонок, как их вернул JDBC
     * @param rawRows      значения строк в порядке колонок
     */
    public CompositionResult process(List<String> columnLabels, List<List<Object>> rawRows) {
        allRows = materialize(columnLabels, rawRows);
        computeRowLevelFields(allRows);
        // Отбор, уже применённый в WHERE, второй раз не проверяем: в режиме
        // SQL-агрегации отбираемых колонок в строках нет, и повторная проверка
        // отбросила бы весь результат. Там, где отбор в SQL не ушёл целиком,
        // проверяем всё дерево целиком — это дешевле, чем разбирать, что именно
        // осталось непротолкнутым.
        if (!layout.filterPushedDown()) {
            allRows = applyFilter(allRows, settings.filter);
        }

        CompositionResult result = new CompositionResult();
        result.aggregatedInSql = layout.aggregatedInSql();
        result.sourceRowCount = allRows.size();
        result.title = settings.outputParameters == null || settings.outputParameters.title == null
                ? null : settings.outputParameters.title;
        result.columns = buildColumns();
        result.parameters = buildParameters();

        List<DcsSettings.StructureNode> structure = enabled(settings.structure);
        if (structure.isEmpty()) {
            // Структуры нет — выводим общий итог с выбранными полями: это осмысленный
            // минимум, а не ошибка (так ведёт себя и новый отчёт в 1С).
            result.rows.add(buildGrandTotal("0", allRows, List.of(), defaultSelection(), null));
        } else {
            for (int i = 0; i < structure.size(); i++) {
                DcsSettings.StructureNode node = structure.get(i);
                String id = String.valueOf(i);
                if (DcsSettings.StructureNode.KIND_TABLE.equals(node.kind)) {
                    result.rows.add(buildTable(id, node, allRows));
                } else {
                    result.rows.add(buildGrandTotal(id, allRows, List.of(node), selectionOf(node), node));
                }
            }
        }

        result.warnings.addAll(layout.warnings());
        result.warnings.addAll(warnings);
        return result;
    }

    // ------------------------------------------------------- подготовка записей

    /**
     * Строки в виде «id поля → значение». Метки колонок сопоставляются с псевдонимами
     * регистронезависимо: H2 в PostgreSQL-режиме приводит их к нижнему регистру, и
     * иначе ни одна колонка не нашлась бы.
     */
    private List<Map<String, Object>> materialize(List<String> labels, List<List<Object>> rawRows) {
        Map<String, Integer> byLabel = new LinkedHashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            byLabel.putIfAbsent(labels.get(i).toLowerCase(Locale.ROOT), i);
        }

        // Плановые колонки: измерения/поля по псевдониму, ресурсы — по своему имени.
        record Bound(String id, int index) {}
        List<Bound> bounds = new ArrayList<>();
        for (FieldCatalog.FieldInfo f : layout.sourceFields()) {
            Integer idx = byLabel.get(f.alias().toLowerCase(Locale.ROOT));
            if (idx == null) idx = byLabel.get(f.column().toLowerCase(Locale.ROOT));
            if (idx != null) bounds.add(new Bound(f.id(), idx));
        }
        if (layout.aggregatedInSql()) {
            for (FieldCatalog.FieldInfo r : catalog.byKind(FieldCatalog.Kind.RESOURCE)) {
                Integer idx = byLabel.get(r.id().toLowerCase(Locale.ROOT));
                if (idx != null) bounds.add(new Bound(r.id(), idx));
            }
        }

        List<Map<String, Object>> out = new ArrayList<>(rawRows.size());
        for (List<Object> raw : rawRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Bound b : bounds) {
                if (b.index() < raw.size()) row.put(b.id(), raw.get(b.index()));
            }
            out.add(row);
        }
        return out;
    }

    /**
     * Вычисляемые и пользовательские поля — построчно. Два прохода, потому что одно
     * вычисляемое поле имеет право ссылаться на другое; больше двух уровней такой
     * зависимости в практике не встречается, а бесконечный цикл исключён по построению.
     */
    private void computeRowLevelFields(List<Map<String, Object>> rows) {
        List<FieldCatalog.FieldInfo> rowLevel = new ArrayList<>();
        rowLevel.addAll(catalog.byKind(FieldCatalog.Kind.CALCULATED));
        rowLevel.addAll(catalog.byKind(FieldCatalog.Kind.USER));
        if (rowLevel.isEmpty()) return;

        for (int pass = 0; pass < 2; pass++) {
            for (Map<String, Object> row : rows) {
                for (FieldCatalog.FieldInfo f : rowLevel) {
                    Object v = f.kind() == FieldCatalog.Kind.USER
                            ? evalUserField(f.id(), row)
                            : evalInRow(f.expression(), row);
                    row.put(f.id(), v);
                }
            }
        }
    }

    private Object evalUserField(String name, Map<String, Object> row) {
        DcsSettings.UserField uf = settings.userFields == null ? null
                : settings.userFields.stream().filter(u -> u != null && name.equals(u.name))
                        .findFirst().orElse(null);
        if (uf == null) return null;
        if (!"select".equalsIgnoreCase(uf.kind)) return evalInRow(uf.expression, row);
        for (DcsSettings.SelectCase c : uf.cases == null ? List.<DcsSettings.SelectCase>of() : uf.cases) {
            if (c == null) continue;
            if (matches(row, c.filter)) return evalInRow(c.value, row);
        }
        return null;
    }

    // ----------------------------------------------------------------- отбор

    private List<Map<String, Object>> applyFilter(List<Map<String, Object>> rows,
                                                  DcsSettings.FilterGroup filter) {
        if (filter == null || filter.items == null || filter.items.isEmpty()) return rows;
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) if (matches(row, filter)) out.add(row);
        return out;
    }

    /** Проверка дерева отбора на одной записи. */
    boolean matches(Map<String, Object> row, DcsSettings.FilterGroup group) {
        if (group == null || group.items == null || group.items.isEmpty()) return true;
        String combinator = group.combinator == null ? "and" : group.combinator.toLowerCase(Locale.ROOT);
        List<DcsSettings.FilterNode> items = group.items.stream()
                .filter(n -> n != null && !n.disabled).toList();
        if (items.isEmpty()) return true;

        boolean acc = !"or".equals(combinator);
        for (DcsSettings.FilterNode n : items) {
            boolean value = n.isGroup() ? matches(row, asGroup(n)) : matchesItem(row, n);
            acc = "or".equals(combinator) ? (acc || value) : (acc && value);
        }
        return "not".equals(combinator) != acc;
    }

    private static DcsSettings.FilterGroup asGroup(DcsSettings.FilterNode n) {
        DcsSettings.FilterGroup g = new DcsSettings.FilterGroup();
        g.combinator = n.combinator;
        g.items = n.items == null ? List.of() : n.items;
        return g;
    }

    private boolean matchesItem(Map<String, Object> row, DcsSettings.FilterNode node) {
        FieldCatalog.FieldInfo f = catalog.resolve(node.left);
        Object left = f == null ? row.get(node.left) : row.get(f.id());
        Object right = node.right instanceof String s && s.startsWith("&")
                ? parameterValue(s.substring(1))
                : node.right;

        String op = node.op == null ? DcsSettings.CompareOp.EQ : node.op;
        return switch (op) {
            case DcsSettings.CompareOp.EQ -> eq(left, right);
            case DcsSettings.CompareOp.NE -> !eq(left, right);
            case DcsSettings.CompareOp.GT -> left != null && ExprEvaluator.cmp(left, right) > 0;
            case DcsSettings.CompareOp.GE -> left != null && ExprEvaluator.cmp(left, right) >= 0;
            case DcsSettings.CompareOp.LT -> left != null && ExprEvaluator.cmp(left, right) < 0;
            case DcsSettings.CompareOp.LE -> left != null && ExprEvaluator.cmp(left, right) <= 0;
            case DcsSettings.CompareOp.FILLED -> isFilled(left);
            case DcsSettings.CompareOp.NOT_FILLED -> !isFilled(left);
            case DcsSettings.CompareOp.CONTAINS -> text(left).contains(text(right));
            case DcsSettings.CompareOp.NOT_CONTAINS -> !text(left).contains(text(right));
            case DcsSettings.CompareOp.BEGINS_WITH -> text(left).startsWith(text(right));
            case DcsSettings.CompareOp.IN -> listOf(right).stream().anyMatch(v -> eq(left, v));
            case DcsSettings.CompareOp.NOT_IN -> listOf(right).stream().noneMatch(v -> eq(left, v));
            case DcsSettings.CompareOp.BETWEEN -> {
                List<Object> b = listOf(right);
                yield b.size() >= 2 && left != null
                        && ExprEvaluator.cmp(left, b.get(0)) >= 0
                        && ExprEvaluator.cmp(left, b.get(1)) <= 0;
            }
            default -> true;
        };
    }

    private static boolean eq(Object a, Object b) {
        if (a == null || b == null) return a == null && b == null;
        return ExprEvaluator.cmp(a, b) == 0;
    }

    private static boolean isFilled(Object v) {
        if (v == null) return false;
        if (v instanceof String s) return !s.isBlank();
        return true;
    }

    private static String text(Object v) {
        return ExprEvaluator.asText(v).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return (List<Object>) l;
        if (v instanceof Object[] a) return List.of(a);
        return List.of(v);
    }

    // --------------------------------------------------------------- структура

    private CompositionResult.Node buildGrandTotal(String id, List<Map<String, Object>> rows,
                                                   List<DcsSettings.StructureNode> children,
                                                   List<String> selection,
                                                   DcsSettings.StructureNode owner) {
        CompositionResult.Node root = new CompositionResult.Node("g" + id, "grandTotal", 0);
        root.display = title(owner);
        root.rowCount = rows.size();
        root.details = new LinkedHashMap<>();
        fillCells(root, rows, rows, null, selection, 0);
        applyAppearance(root, rows, AREA_GRAND_TOTAL, selection);

        for (DcsSettings.StructureNode child : children) {
            buildInto(root, child, rows, rows, 1, new LinkedHashMap<>());
        }
        return root;
    }

    /** Разворачивает один узел структуры в детей {@code parent}. */
    private void buildInto(CompositionResult.Node parent, DcsSettings.StructureNode node,
                           List<Map<String, Object>> rows, List<Map<String, Object>> parentRows,
                           int level, Map<String, Object> inheritedDetails) {
        if (node == null || node.disabled) return;

        if (DcsSettings.StructureNode.KIND_TABLE.equals(node.kind)) {
            parent.addChild(buildTable(parent.id + ".t", node, rows));
            return;
        }
        if (DcsSettings.StructureNode.KIND_CHART.equals(node.kind)) {
            // Диаграмма питается теми же группировками — строим их как обычные узлы,
            // а тип отрисовки фронтенд берёт из настроек.
            for (DcsSettings.StructureNode child : enabled(node.children)) {
                buildInto(parent, child, rows, parentRows, level, inheritedDetails);
            }
            return;
        }

        List<String> selection = selectionOf(node);

        // Детальные записи: группировка без поля.
        if (node.field == null || node.field.isBlank()) {
            List<Map<String, Object>> ordered = sortRows(rows, orderOf(node));
            int n = 0;
            for (Map<String, Object> row : ordered) {
                n++;
                CompositionResult.Node detail =
                        new CompositionResult.Node(parent.id + ".d" + n, "detail", level);
                detail.rowCount = 1;
                detail.details = new LinkedHashMap<>(inheritedDetails);
                List<Map<String, Object>> single = List.of(row);
                fillCells(detail, single, parentRows, row, selection, n);
                applyAppearance(detail, single, AREA_DETAILS, selection);
                parent.addChild(detail);
            }
            return;
        }

        FieldCatalog.FieldInfo field = catalog.resolve(node.field);
        if (field == null) {
            warnings.add("Grouping by an unknown field «" + node.field + "» is skipped");
            return;
        }
        if (node.groupingType != null && node.groupingType.toLowerCase(Locale.ROOT).startsWith("hierarchy")) {
            warnings.add("Grouping «" + field.title() + "»: hierarchical totals need a parent field, "
                    + "which the schema does not describe — built as a flat grouping");
        }

        Map<Object, List<Map<String, Object>>> partitions = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get(field.id());
            partitions.computeIfAbsent(normalizeKey(key), k -> new ArrayList<>()).add(row);
        }

        List<CompositionResult.Node> groups = new ArrayList<>();
        int n = 0;
        for (Map.Entry<Object, List<Map<String, Object>>> e : partitions.entrySet()) {
            n++;
            List<Map<String, Object>> part = e.getValue();
            Object value = part.get(0).get(field.id());

            CompositionResult.Node group =
                    new CompositionResult.Node(parent.id + ".g" + n, "group", level);
            group.field = field.id();
            group.fieldTitle = field.title();
            group.value = value;
            group.display = ExprEvaluator.asText(value);
            if (value == null) group.display = "<empty>";
            group.rowCount = part.size();

            Map<String, Object> details = new LinkedHashMap<>(inheritedDetails);
            details.put(field.id(), value);
            group.details = details;

            fillCells(group, part, rows, null, selection, n);

            // Отбор уровня: применяется к уже посчитанной группировке — аналог HAVING.
            if (node.filter != null && !matches(groupRow(group, part), node.filter)) continue;

            applyAppearance(group, part, AREA_GROUP_TOTALS, selection);

            for (DcsSettings.StructureNode child : enabled(node.children)) {
                buildInto(group, child, part, rows, level + 1, details);
            }
            groups.add(group);
        }

        for (CompositionResult.Node g : sortGroups(groups, orderOf(node))) parent.addChild(g);
    }

    /** Псевдозапись группировки — по ней проверяется отбор уровня. */
    private Map<String, Object> groupRow(CompositionResult.Node group, List<Map<String, Object>> rows) {
        Map<String, Object> row = new LinkedHashMap<>(group.cells);
        if (group.field != null) row.put(group.field, group.value);
        if (!rows.isEmpty()) rows.get(0).forEach(row::putIfAbsent);
        return row;
    }

    // ------------------------------------------------------------ кросс-таблица

    /**
     * Кросс-таблица: независимые иерархии группировок по строкам и по колонкам,
     * в пересечении — ресурсы. Строки строятся обычным рекурсивным способом, колонки
     * превращаются в дерево заголовков, а ячейки адресуются ключом
     * {@code <ключ колонки>|<ресурс>} — так фронтенду не нужно заново сопоставлять
     * координаты.
     */
    private CompositionResult.Node buildTable(String id, DcsSettings.StructureNode node,
                                              List<Map<String, Object>> rows) {
        CompositionResult.Node table = new CompositionResult.Node("t" + id, "table", 0);
        table.display = title(node);
        table.rowCount = rows.size();

        List<FieldCatalog.FieldInfo> columnFields = new ArrayList<>();
        for (DcsSettings.StructureNode c : flatten(node.columns)) {
            FieldCatalog.FieldInfo f = c.field == null ? null : catalog.resolve(c.field);
            if (f != null) columnFields.add(f);
        }

        List<String> resources = selectionOf(node).stream()
                .filter(sel -> {
                    FieldCatalog.FieldInfo f = catalog.resolve(sel);
                    return f != null && f.kind() == FieldCatalog.Kind.RESOURCE;
                }).toList();
        if (resources.isEmpty()) {
            resources = catalog.byKind(FieldCatalog.Kind.RESOURCE).stream()
                    .map(FieldCatalog.FieldInfo::id).toList();
        }

        table.columnHeaders = buildColumnHeaders(rows, columnFields, 0, "");
        List<String> leafKeys = new ArrayList<>();
        collectLeafKeys(table.columnHeaders, leafKeys);
        if (leafKeys.isEmpty()) leafKeys.add("");

        // Карта «ключ колонки → записи» — считается один раз на всю таблицу.
        Map<String, List<Map<String, Object>>> byColumn = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byColumn.computeIfAbsent(columnKey(row, columnFields, ""), k -> new ArrayList<>()).add(row);
        }

        List<DcsSettings.StructureNode> rowGroupings = enabled(node.rows);
        if (rowGroupings.isEmpty()) {
            fillMatrixCells(table, rows, byColumn, leafKeys, resources);
        } else {
            fillMatrixCells(table, rows, byColumn, leafKeys, resources);
            for (DcsSettings.StructureNode rg : rowGroupings) {
                buildMatrixRows(table, rg, rows, byColumn, leafKeys, resources, 1, new LinkedHashMap<>());
            }
        }
        return table;
    }

    private void buildMatrixRows(CompositionResult.Node parent, DcsSettings.StructureNode node,
                                 List<Map<String, Object>> rows,
                                 Map<String, List<Map<String, Object>>> byColumn,
                                 List<String> leafKeys, List<String> resources,
                                 int level, Map<String, Object> inheritedDetails) {
        FieldCatalog.FieldInfo field = node.field == null ? null : catalog.resolve(node.field);
        if (field == null) return;

        Map<Object, List<Map<String, Object>>> partitions = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            partitions.computeIfAbsent(normalizeKey(row.get(field.id())), k -> new ArrayList<>()).add(row);
        }

        List<CompositionResult.Node> groups = new ArrayList<>();
        int n = 0;
        for (Map.Entry<Object, List<Map<String, Object>>> e : partitions.entrySet()) {
            n++;
            List<Map<String, Object>> part = e.getValue();
            CompositionResult.Node group =
                    new CompositionResult.Node(parent.id + ".r" + n, "group", level);
            group.field = field.id();
            group.fieldTitle = field.title();
            group.value = part.get(0).get(field.id());
            group.display = group.value == null ? "<empty>" : ExprEvaluator.asText(group.value);
            group.rowCount = part.size();
            Map<String, Object> details = new LinkedHashMap<>(inheritedDetails);
            details.put(field.id(), group.value);
            group.details = details;

            Map<String, List<Map<String, Object>>> partByColumn = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> col : byColumn.entrySet()) {
                List<Map<String, Object>> intersect = new ArrayList<>();
                for (Map<String, Object> r : col.getValue()) if (part.contains(r)) intersect.add(r);
                if (!intersect.isEmpty()) partByColumn.put(col.getKey(), intersect);
            }
            fillMatrixCells(group, part, partByColumn, leafKeys, resources);
            applyAppearance(group, part, AREA_GROUP_TOTALS, resources);

            for (DcsSettings.StructureNode child : enabled(node.children)) {
                buildMatrixRows(group, child, part, partByColumn, leafKeys, resources, level + 1, details);
            }
            groups.add(group);
        }
        for (CompositionResult.Node g : sortGroups(groups, orderOf(node))) parent.addChild(g);
    }

    private void fillMatrixCells(CompositionResult.Node target, List<Map<String, Object>> rows,
                                 Map<String, List<Map<String, Object>>> byColumn,
                                 List<String> leafKeys, List<String> resources) {
        for (String key : leafKeys) {
            List<Map<String, Object>> cellRows = byColumn.getOrDefault(key, List.of());
            for (String res : resources) {
                target.cells.put(key + "|" + res, evalResource(res, cellRows, rows, target.level));
            }
        }
        // Итог по строке — по всем её записям.
        for (String res : resources) {
            target.cells.put("|" + res, evalResource(res, rows, rows, target.level));
        }
    }

    private List<CompositionResult.ColumnHeader> buildColumnHeaders(
            List<Map<String, Object>> rows, List<FieldCatalog.FieldInfo> fields, int depth, String prefix) {
        if (depth >= fields.size()) return null;
        FieldCatalog.FieldInfo field = fields.get(depth);

        Map<Object, List<Map<String, Object>>> partitions = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            partitions.computeIfAbsent(normalizeKey(row.get(field.id())), k -> new ArrayList<>()).add(row);
        }

        List<CompositionResult.ColumnHeader> out = new ArrayList<>();
        for (Map.Entry<Object, List<Map<String, Object>>> e : partitions.entrySet()) {
            Object value = e.getValue().get(0).get(field.id());
            String key = prefix + "/" + ExprEvaluator.asText(value);
            CompositionResult.ColumnHeader header = new CompositionResult.ColumnHeader(
                    key, field.id(), value,
                    value == null ? "<empty>" : ExprEvaluator.asText(value), depth);
            header.children = buildColumnHeaders(e.getValue(), fields, depth + 1, key);
            out.add(header);
        }
        out.sort(Comparator.comparing(h -> h.display == null ? "" : h.display));
        return out;
    }

    private void collectLeafKeys(List<CompositionResult.ColumnHeader> headers, List<String> out) {
        if (headers == null) return;
        for (CompositionResult.ColumnHeader h : headers) {
            if (h.children == null || h.children.isEmpty()) out.add(h.key);
            else collectLeafKeys(h.children, out);
        }
    }

    private String columnKey(Map<String, Object> row, List<FieldCatalog.FieldInfo> fields, String prefix) {
        String key = prefix;
        for (FieldCatalog.FieldInfo f : fields) {
            key = key + "/" + ExprEvaluator.asText(row.get(f.id()));
        }
        return key;
    }

    // ------------------------------------------------------------------ ячейки

    private void fillCells(CompositionResult.Node target, List<Map<String, Object>> rows,
                           List<Map<String, Object>> parentRows, Map<String, Object> singleRow,
                           List<String> selection, int recordNumber) {
        for (String id : selection) {
            FieldCatalog.FieldInfo f = catalog.resolve(id);
            if (f == null) continue;
            Object value;
            if (f.kind() == FieldCatalog.Kind.RESOURCE) {
                value = evalResource(f.id(), rows, parentRows, target.level);
            } else if (singleRow != null) {
                value = singleRow.get(f.id());
            } else {
                value = commonValue(rows, f.id());
            }
            target.cells.put(f.id(), value);
        }
    }

    /**
     * Значение поля на группировке: оно осмысленно только если одинаково у всех записей
     * группы. Иначе ячейка остаётся пустой — показать произвольное из нескольких значений
     * означало бы соврать.
     */
    private Object commonValue(List<Map<String, Object>> rows, String fieldId) {
        Object first = null;
        boolean firstSeen = false;
        for (Map<String, Object> row : rows) {
            Object v = row.get(fieldId);
            if (!firstSeen) { first = v; firstSeen = true; continue; }
            if (ExprEvaluator.cmp(first, v) != 0) return null;
        }
        return first;
    }

    private Object evalResource(String resourceId, List<Map<String, Object>> rows,
                                List<Map<String, Object>> parentRows, int level) {
        DcsSchema.ResourceField res = schema.resource(resourceId);
        String expression = layout.aggregatedInSql() && layout.rollupExpressions().containsKey(resourceId)
                ? layout.rollupExpressions().get(resourceId)
                : (res == null ? null : res.expression);
        if (expression == null || expression.isBlank()) return null;

        // «Рассчитывать по…»: ресурс считается только на перечисленных группировках.
        if (res != null && res.calcByGroups != null && !res.calcByGroups.isEmpty() && level > 0) {
            // Ограничение проверяется по полю текущей группировки, известному в details.
            // Пустое пересечение означает «на этом уровне ресурс не рассчитывается».
            boolean allowed = false;
            for (String g : res.calcByGroups) {
                FieldCatalog.FieldInfo gf = catalog.resolve(g);
                if (gf != null && rows.stream().anyMatch(r -> r.containsKey(gf.id()))) { allowed = true; break; }
            }
            if (!allowed) return null;
        }

        Expr parsed = parse(expression);
        if (parsed == null) {
            warnings.add("Resource «" + resourceId + "»: cannot parse the expression");
            return null;
        }
        return new ExprEvaluator(new GroupContext(rows, parentRows, level, 0)).eval(parsed);
    }

    private Object evalInRow(String expression, Map<String, Object> row) {
        Expr parsed = parse(expression);
        if (parsed == null) return null;
        try {
            return new ExprEvaluator(new GroupContext(List.of(row), allRows, 0, 0)).eval(parsed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Expr parse(String expression) {
        if (expression == null || expression.isBlank()) return null;
        return exprCache.computeIfAbsent(expression, ExprParser::parseQuietly);
    }

    // ------------------------------------------------------------ оформление

    private void applyAppearance(CompositionResult.Node node, List<Map<String, Object>> rows,
                                 String area, List<String> selection) {
        if (settings.conditionalAppearance == null) return;
        Map<String, Object> probe = rows.isEmpty()
                ? new LinkedHashMap<>(node.cells)
                : mergeProbe(node, rows.get(0));

        for (DcsSettings.AppearanceItem item : settings.conditionalAppearance) {
            if (item == null || item.disabled || item.appearance == null) continue;
            if (item.areas != null && !item.areas.isEmpty() && !item.areas.contains(area)) continue;
            if (item.filter != null && !matches(probe, item.filter)) continue;

            if (item.fields == null || item.fields.isEmpty()) {
                node.appear(null, item.appearance);
            } else {
                for (String f : item.fields) {
                    FieldCatalog.FieldInfo info = catalog.resolve(f);
                    String cellId = info == null ? f : info.id();
                    if (selection.contains(cellId) || node.cells.containsKey(cellId)) {
                        node.appear(cellId, item.appearance);
                    }
                }
            }
        }
    }

    /** Значения для проверки условия оформления: посчитанные ячейки плюс поля записи. */
    private Map<String, Object> mergeProbe(CompositionResult.Node node, Map<String, Object> row) {
        Map<String, Object> probe = new LinkedHashMap<>(row);
        probe.putAll(node.cells);
        if (node.field != null) probe.put(node.field, node.value);
        return probe;
    }

    // -------------------------------------------------------------- сортировка

    private List<Map<String, Object>> sortRows(List<Map<String, Object>> rows,
                                               List<DcsSettings.OrderItem> order) {
        if (order.isEmpty()) return rows;
        List<Map<String, Object>> copy = new ArrayList<>(rows);
        copy.sort(rowComparator(order));
        return copy;
    }

    private Comparator<Map<String, Object>> rowComparator(List<DcsSettings.OrderItem> order) {
        return (a, b) -> {
            for (DcsSettings.OrderItem o : order) {
                FieldCatalog.FieldInfo f = catalog.resolve(o.field);
                String key = f == null ? o.field : f.id();
                int c = ExprEvaluator.cmp(a.get(key), b.get(key));
                if (c != 0) return "desc".equalsIgnoreCase(o.direction) ? -c : c;
            }
            return 0;
        };
    }

    private List<CompositionResult.Node> sortGroups(List<CompositionResult.Node> groups,
                                                    List<DcsSettings.OrderItem> order) {
        if (order.isEmpty()) {
            // Без явного порядка группировки идут по значению — стабильно и предсказуемо.
            List<CompositionResult.Node> copy = new ArrayList<>(groups);
            copy.sort((a, b) -> ExprEvaluator.cmp(a.value, b.value));
            return copy;
        }
        List<CompositionResult.Node> copy = new ArrayList<>(groups);
        copy.sort((a, b) -> {
            for (DcsSettings.OrderItem o : order) {
                FieldCatalog.FieldInfo f = catalog.resolve(o.field);
                String key = f == null ? o.field : f.id();
                Object av = key.equals(a.field) ? a.value : a.cells.get(key);
                Object bv = key.equals(b.field) ? b.value : b.cells.get(key);
                int c = ExprEvaluator.cmp(av, bv);
                if (c != 0) return "desc".equalsIgnoreCase(o.direction) ? -c : c;
            }
            return 0;
        });
        return copy;
    }

    // ----------------------------------------------------------------- прочее

    private List<CompositionResult.Column> buildColumns() {
        List<CompositionResult.Column> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : collectSelection()) {
            FieldCatalog.FieldInfo f = catalog.resolve(id);
            if (f == null || !seen.add(f.id())) continue;
            CompositionResult.Column c = new CompositionResult.Column(
                    f.id(), f.title(),
                    f.kind() == FieldCatalog.Kind.RESOURCE ? "resource" : "field",
                    f.kind() == FieldCatalog.Kind.RESOURCE ? "number" : f.valueType());
            DcsSchema.ResourceField res = schema.resource(f.id());
            if (res != null) c.format = res.format;
            if ("resource".equals(c.kind)) c.align = "right";
            out.add(c);
        }
        return out;
    }

    private List<String> collectSelection() {
        List<String> out = new ArrayList<>(defaultSelection());
        collectSelectionFrom(settings.structure, out);
        return out;
    }

    private void collectSelectionFrom(List<DcsSettings.StructureNode> nodes, List<String> out) {
        if (nodes == null) return;
        for (DcsSettings.StructureNode n : nodes) {
            if (n == null || n.disabled) continue;
            if (n.selection != null) out.addAll(n.selection);
            collectSelectionFrom(n.children, out);
            collectSelectionFrom(n.rows, out);
            collectSelectionFrom(n.columns, out);
        }
    }

    private List<String> defaultSelection() {
        if (settings.selection != null && !settings.selection.isEmpty()) return settings.selection;
        // Пустой набор выбранных полей — выводим все ресурсы: отчёт без ресурсов
        // бессмысленен, а с ними показывает хотя бы итоги.
        return catalog.byKind(FieldCatalog.Kind.RESOURCE).stream()
                .map(FieldCatalog.FieldInfo::id).toList();
    }

    private List<String> selectionOf(DcsSettings.StructureNode node) {
        if (node != null && node.selection != null && !node.selection.isEmpty()) return node.selection;
        return defaultSelection();
    }

    private List<DcsSettings.OrderItem> orderOf(DcsSettings.StructureNode node) {
        if (node != null && node.order != null && !node.order.isEmpty()) {
            return node.order.stream().filter(o -> o != null && !o.disabled).toList();
        }
        return settings.order == null ? List.of()
                : settings.order.stream().filter(o -> o != null && !o.disabled).toList();
    }

    private List<CompositionResult.ParameterOut> buildParameters() {
        List<CompositionResult.ParameterOut> out = new ArrayList<>();
        if (schema.parameters == null) return out;
        boolean show = settings.outputParameters == null || settings.outputParameters.showParameters;
        if (!show) return out;
        for (DcsSchema.Parameter p : schema.parameters) {
            if (p == null || p.name == null) continue;
            CompositionResult.ParameterOut po = new CompositionResult.ParameterOut();
            po.name = p.name;
            po.title = p.title == null ? p.name : p.title;
            po.value = parameterValue(p.name);
            po.presentation = ExprEvaluator.asText(po.value);
            out.add(po);
        }
        return out;
    }

    private Object parameterValue(String name) {
        if (settings.dataParameters != null && settings.dataParameters.containsKey(name)) {
            return settings.dataParameters.get(name);
        }
        DcsSchema.Parameter p = schema.parameter(name);
        return p == null ? null : p.value;
    }

    private static String title(DcsSettings.StructureNode node) {
        return node == null || node.title == null || node.title.isBlank() ? "Total" : node.title;
    }

    private static List<DcsSettings.StructureNode> enabled(List<DcsSettings.StructureNode> nodes) {
        if (nodes == null) return List.of();
        return nodes.stream().filter(n -> n != null && !n.disabled).toList();
    }

    private static List<DcsSettings.StructureNode> flatten(List<DcsSettings.StructureNode> nodes) {
        List<DcsSettings.StructureNode> out = new ArrayList<>();
        for (DcsSettings.StructureNode n : enabled(nodes)) {
            out.add(n);
            out.addAll(flatten(n.children));
        }
        return out;
    }

    /** Ключ разбиения: null и разные представления одного значения должны совпадать. */
    private static Object normalizeKey(Object v) {
        if (v == null) return "\u0000null";
        return ExprEvaluator.asText(v);
    }

    // ----------------------------------------------------- контекст вычисления

    /** Контекст группировки: записи области, записи родителя и уровень. */
    private final class GroupContext implements EvalContext {
        private final List<Map<String, Object>> rows;
        private final List<Map<String, Object>> parentRows;
        private final int level;
        private final int recordNumber;

        GroupContext(List<Map<String, Object>> rows, List<Map<String, Object>> parentRows,
                     int level, int recordNumber) {
            this.rows = rows;
            this.parentRows = parentRows;
            this.level = level;
            this.recordNumber = recordNumber;
        }

        @Override public Object field(String path) {
            FieldCatalog.FieldInfo f = catalog.resolve(path);
            String key = f == null ? path : f.id();
            if (rows.size() == 1) return rows.get(0).get(key);
            return commonValue(rows, key);
        }

        @Override public Object parameter(String name) { return parameterValue(name); }
        @Override public List<Map<String, Object>> rows() { return rows; }
        @Override public int level() { return level; }
        @Override public int recordNumber() { return recordNumber; }

        @Override public Object evaluateInScope(String expression, String grouping, String area) {
            Expr parsed = parse(expression);
            if (parsed == null) return null;
            String scope = area == null || area.isBlank() ? grouping : area;
            List<Map<String, Object>> scopeRows = switch (canonicalScope(scope)) {
                case "grandtotal" -> allRows;
                case "parent" -> parentRows;
                default -> rows;
            };
            return new ExprEvaluator(new GroupContext(scopeRows, allRows, level, recordNumber)).eval(parsed);
        }

        private String canonicalScope(String s) {
            if (s == null) return "";
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "общийитог", "grandtotal", "overall" -> "grandtotal";
                case "иерархия", "hierarchy", "родитель", "parent" -> "parent";
                default -> "";
            };
        }
    }
}
