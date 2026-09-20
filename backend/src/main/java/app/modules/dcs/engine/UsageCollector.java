package app.modules.dcs.engine;

import app.modules.dcs.expression.Expr;
import app.modules.dcs.expression.ExprParser;
import app.modules.dcs.model.DcsSchema;
import app.modules.dcs.model.DcsSettings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <h2>Какие поля реально нужны отчёту.</h2>
 *
 * <p>Запрос должен выбирать ровно то, что участвует в выводе, отборе, порядке и
 * оформлении, — «взять всё на всякий случай» на детальных выборках стоит слишком
 * дорого. Сборщик обходит настройки и схему и раскрывает транзитивные зависимости:
 * ресурс тянет поля из своего выражения, вычисляемое поле — из своего, и так далее,
 * пока множество не перестанет расти.
 *
 * <p>Циклические ссылки между вычисляемыми полями обрываются естественно: имя,
 * которое уже в множестве, второй раз не раскрывается.
 */
final class UsageCollector {

    private final DcsSchema schema;
    private final DcsSettings settings;
    private final FieldCatalog catalog;

    UsageCollector(DcsSchema schema, DcsSettings settings, FieldCatalog catalog) {
        this.schema = schema;
        this.settings = settings;
        this.catalog = catalog;
    }

    Set<String> collect() {
        Set<String> direct = new LinkedHashSet<>();

        addAll(direct, settings.selection);
        collectOrder(direct, settings.order);
        collectFilter(direct, settings.filter);
        collectStructure(direct, settings.structure);

        if (settings.conditionalAppearance != null) {
            for (DcsSettings.AppearanceItem a : settings.conditionalAppearance) {
                if (a == null || a.disabled) continue;
                collectFilter(direct, a.filter);
                addAll(direct, a.fields);
            }
        }
        if (settings.userFields != null) {
            for (DcsSettings.UserField u : settings.userFields) {
                if (u == null) continue;
                addExpression(direct, u.expression);
                if (u.cases != null) {
                    for (DcsSettings.SelectCase c : u.cases) {
                        if (c == null) continue;
                        collectFilter(direct, c.filter);
                        addExpression(direct, c.value);
                    }
                }
            }
        }

        return expand(direct);
    }

    /** Раскрытие выражений ресурсов и вычисляемых полей до колонок наборов. */
    private Set<String> expand(Set<String> direct) {
        Set<String> result = new LinkedHashSet<>(direct);
        Deque<String> queue = new ArrayDeque<>(direct);
        Set<String> expanded = new LinkedHashSet<>();

        while (!queue.isEmpty()) {
            String id = queue.poll();
            FieldCatalog.FieldInfo f = catalog.resolve(id);
            if (f == null || !expanded.add(f.id())) continue;
            result.add(f.id());
            if (f.expression() == null || f.expression().isBlank()) continue;
            for (String referenced : fieldsOf(f.expression())) {
                FieldCatalog.FieldInfo ref = catalog.resolve(referenced);
                if (ref != null && result.add(ref.id())) queue.add(ref.id());
            }
        }

        // Ресурсы всегда считаются целиком — их слагаемые нужны запросу, даже если
        // сам ресурс не выбран ни в одной группировке (он может появиться в итогах).
        if (schema.resources != null) {
            for (DcsSchema.ResourceField r : schema.resources) {
                if (r == null || r.expression == null) continue;
                if (!result.contains(r.name)) continue;
                for (String referenced : fieldsOf(r.expression)) {
                    FieldCatalog.FieldInfo ref = catalog.resolve(referenced);
                    if (ref != null) result.add(ref.id());
                }
            }
        }
        return result;
    }

    private void collectStructure(Set<String> out, List<DcsSettings.StructureNode> nodes) {
        if (nodes == null) return;
        for (DcsSettings.StructureNode n : nodes) {
            if (n == null || n.disabled) continue;
            if (n.field != null && !n.field.isBlank()) out.add(n.field);
            addAll(out, n.selection);
            collectOrder(out, n.order);
            collectFilter(out, n.filter);
            collectStructure(out, n.children);
            collectStructure(out, n.rows);
            collectStructure(out, n.columns);
        }
    }

    private void collectOrder(Set<String> out, List<DcsSettings.OrderItem> order) {
        if (order == null) return;
        for (DcsSettings.OrderItem o : order) {
            if (o != null && !o.disabled && o.field != null && !o.field.isBlank()) out.add(o.field);
        }
    }

    private void collectFilter(Set<String> out, DcsSettings.FilterGroup group) {
        if (group == null || group.items == null) return;
        for (DcsSettings.FilterNode n : group.items) collectFilterNode(out, n);
    }

    private void collectFilterNode(Set<String> out, DcsSettings.FilterNode n) {
        if (n == null || n.disabled) return;
        if (n.isGroup()) {
            if (n.items != null) for (DcsSettings.FilterNode child : n.items) collectFilterNode(out, child);
            return;
        }
        if (n.left != null && !n.left.isBlank()) out.add(n.left);
    }

    private void addExpression(Set<String> out, String expression) {
        out.addAll(fieldsOf(expression));
    }

    /** Имена полей, встречающиеся в выражении. Неразбираемое выражение даёт пустой список. */
    private static List<String> fieldsOf(String expression) {
        Expr parsed = ExprParser.parseQuietly(expression);
        List<String> out = new ArrayList<>();
        walk(parsed, out);
        return out;
    }

    private static void walk(Expr e, List<String> out) {
        if (e == null) return;
        switch (e) {
            case Expr.Field f -> out.add(f.path());
            case Expr.Unary u -> walk(u.operand(), out);
            case Expr.Binary b -> { walk(b.left(), out); walk(b.right(), out); }
            case Expr.Call c -> c.args().forEach(a -> walk(a, out));
            case Expr.Case c -> {
                for (Expr.Branch br : c.branches()) { walk(br.when(), out); walk(br.then(), out); }
                walk(c.otherwise(), out);
            }
            case Expr.In i -> { walk(i.value(), out); i.options().forEach(o -> walk(o, out)); }
            case Expr.Between b -> { walk(b.value(), out); walk(b.low(), out); walk(b.high(), out); }
            case Expr.IsNull n -> walk(n.value(), out);
            case Expr.Lit ignored -> { }
            case Expr.Param ignored -> { }
        }
    }

    private static void addAll(Set<String> out, List<String> ids) {
        if (ids == null) return;
        for (String id : ids) if (id != null && !id.isBlank()) out.add(id);
    }
}
