package app.modules.dcs.engine;

import app.modules.dcs.model.DcsSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * <h2>Компоновщик настроек: настройки по умолчанию ⊕ пользовательские.</h2>
 *
 * <p>Аналог {@code SettingsComposer} 1С. Пользователь почти никогда не переопределяет
 * отчёт целиком: он меняет пару параметров, добавляет отбор, разворачивает группировку.
 * Поэтому присланные настройки накладываются <b>поверх</b>, а не заменяют собой всё:
 * незаданный раздел остаётся таким, каким его задумал разработчик отчёта.
 *
 * <p>Правило простое и одинаковое для всех разделов: {@code null} — «не трогать»,
 * непустое значение — «заменить». Исключение одно — параметры данных: они сливаются
 * по ключам, потому что менять один параметр, не пересылая остальные, нужно постоянно.
 */
public final class SettingsComposer {

    private SettingsComposer() {}

    /** Накладывает {@code user} на {@code base}, не изменяя ни один из аргументов. */
    public static DcsSettings compose(DcsSettings base, DcsSettings user) {
        DcsSettings out = copy(base == null ? new DcsSettings() : base);
        if (user == null) return out;

        if (user.structure != null && !user.structure.isEmpty()) out.structure = user.structure;
        if (user.filter != null) out.filter = user.filter;
        if (user.selection != null && !user.selection.isEmpty()) out.selection = user.selection;
        if (user.order != null && !user.order.isEmpty()) out.order = user.order;
        if (user.conditionalAppearance != null && !user.conditionalAppearance.isEmpty()) {
            out.conditionalAppearance = user.conditionalAppearance;
        }
        if (user.userFields != null && !user.userFields.isEmpty()) out.userFields = user.userFields;
        if (user.outputParameters != null) out.outputParameters = mergeOutput(out.outputParameters, user.outputParameters);
        if (user.dataParameters != null && !user.dataParameters.isEmpty()) {
            if (out.dataParameters == null) out.dataParameters = new LinkedHashMap<>();
            out.dataParameters.putAll(user.dataParameters);
        }
        return out;
    }

    /**
     * Параметры вывода сливаются по-полевому: пользователь меняет заголовок, не сбрасывая
     * расположение итогов, выставленное автором отчёта.
     */
    private static DcsSettings.OutputParameters mergeOutput(DcsSettings.OutputParameters base,
                                                            DcsSettings.OutputParameters user) {
        DcsSettings.OutputParameters out = base == null ? new DcsSettings.OutputParameters() : base;
        if (user.title != null) out.title = user.title;
        if (user.verticalTotals != null) out.verticalTotals = user.verticalTotals;
        if (user.horizontalTotals != null) out.horizontalTotals = user.horizontalTotals;
        if (user.groupPlacement != null) out.groupPlacement = user.groupPlacement;
        if (user.maxRows != null) out.maxRows = user.maxRows;
        out.showTitle = user.showTitle;
        out.showParameters = user.showParameters;
        out.showFilter = user.showFilter;
        return out;
    }

    /** Поверхностная копия разделов — достаточно, потому что наложение только заменяет ссылки. */
    private static DcsSettings copy(DcsSettings s) {
        DcsSettings out = new DcsSettings();
        out.structure = s.structure == null ? new ArrayList<>() : new ArrayList<>(s.structure);
        out.filter = s.filter;
        out.selection = s.selection == null ? new ArrayList<>() : new ArrayList<>(s.selection);
        out.order = s.order == null ? new ArrayList<>() : new ArrayList<>(s.order);
        out.conditionalAppearance = s.conditionalAppearance == null
                ? new ArrayList<>() : new ArrayList<>(s.conditionalAppearance);
        out.outputParameters = s.outputParameters == null
                ? new DcsSettings.OutputParameters() : s.outputParameters;
        out.dataParameters = s.dataParameters == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(s.dataParameters);
        out.userFields = s.userFields == null ? new ArrayList<>() : new ArrayList<>(s.userFields);
        return out;
    }

    /** Отбор пользователя добавляется к отбору отчёта через {@code И}, а не вместо него. */
    public static DcsSettings.FilterGroup andFilters(DcsSettings.FilterGroup a, DcsSettings.FilterGroup b) {
        if (a == null || a.items == null || a.items.isEmpty()) return b;
        if (b == null || b.items == null || b.items.isEmpty()) return a;
        DcsSettings.FilterGroup out = new DcsSettings.FilterGroup();
        out.combinator = "and";
        out.items = new ArrayList<>();
        out.items.add(asNode(a));
        out.items.add(asNode(b));
        return out;
    }

    private static DcsSettings.FilterNode asNode(DcsSettings.FilterGroup g) {
        DcsSettings.FilterNode n = new DcsSettings.FilterNode();
        n.combinator = g.combinator == null ? "and" : g.combinator;
        n.items = new ArrayList<>(g.items);
        return n;
    }

    /** Один элемент отбора «поле = значение» — так строится расшифровка. */
    public static DcsSettings.FilterNode equals(String field, Object value) {
        DcsSettings.FilterNode n = new DcsSettings.FilterNode();
        n.left = field;
        n.op = value == null ? DcsSettings.CompareOp.NOT_FILLED : DcsSettings.CompareOp.EQ;
        n.right = value;
        return n;
    }

    /** Группа {@code И} из готовых элементов. */
    public static DcsSettings.FilterGroup and(List<DcsSettings.FilterNode> items) {
        DcsSettings.FilterGroup g = new DcsSettings.FilterGroup();
        g.combinator = "and";
        g.items = new ArrayList<>(items);
        return g;
    }
}
