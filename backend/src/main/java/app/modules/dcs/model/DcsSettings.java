package app.modules.dcs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <h2>Настройки компоновки — что и как выводить.</h2>
 *
 * <p>Аналог {@code DataCompositionSettings} 1С:СКД. Отчёт хранит настройки по умолчанию
 * реквизитом {@code settings}; пользователь присылает свои поверх, а
 * {@code SettingsComposer} накладывает одни на другие, получая исполнимые настройки.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DcsSettings {

    /** Структура отчёта: дерево группировок/таблиц/диаграмм. */
    public List<StructureNode> structure = new ArrayList<>();

    /** Общий отбор, применяемый к исходным записям (уходит в WHERE). */
    public FilterGroup filter;

    /** Выбранные поля верхнего уровня (наследуются группировками без своего набора). */
    public List<String> selection = new ArrayList<>();

    /** Порядок по умолчанию. */
    public List<OrderItem> order = new ArrayList<>();

    /** Условное оформление. */
    public List<AppearanceItem> conditionalAppearance = new ArrayList<>();

    /** Параметры вывода (заголовок, расположение итогов, …). */
    public OutputParameters outputParameters = new OutputParameters();

    /** Значения параметров схемы. */
    public Map<String, Object> dataParameters = new LinkedHashMap<>();

    /** Пользовательские поля. */
    public List<UserField> userFields = new ArrayList<>();

    // ------------------------------------------------------------- структура

    /** Узел структуры отчёта. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StructureNode {
        public String id;
        /** {@code grouping} | {@code table} | {@code chart}. */
        public String kind = KIND_GROUPING;
        /**
         * Поле группировки. {@code null} — группировка «Детальные записи»: выводит
         * исходные строки без свёртки.
         */
        public String field;
        /** {@code items} | {@code hierarchy} | {@code hierarchyOnly}. */
        public String groupingType = "items";
        public String title;
        /** Выбранные поля этого уровня; пусто — наследуются от родителя/настроек. */
        public List<String> selection = new ArrayList<>();
        public List<OrderItem> order = new ArrayList<>();
        /** Отбор уровня: применяется к уже сгруппированным строкам (аналог HAVING). */
        public FilterGroup filter;
        public List<StructureNode> children = new ArrayList<>();
        /** Для {@code table}: группировки строк. */
        public List<StructureNode> rows = new ArrayList<>();
        /** Для {@code table}: группировки колонок. */
        public List<StructureNode> columns = new ArrayList<>();
        /** Для {@code chart}: тип диаграммы ({@code bar|line|pie}) — рендерит фронтенд. */
        public String chartType = "bar";
        public boolean disabled;

        public static final String KIND_GROUPING = "grouping";
        public static final String KIND_TABLE = "table";
        public static final String KIND_CHART = "chart";
    }

    // ---------------------------------------------------------------- отбор

    /** Группа отбора: {@code and} | {@code or} | {@code not}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FilterGroup {
        public String combinator = "and";
        /** Смешанный список: элементы отбора и вложенные группы. */
        public List<FilterNode> items = new ArrayList<>();
    }

    /**
     * Узел отбора. Одним классом вместо иерархии — так дерево переживает любую
     * форму JSON'а от фронтенда: если есть {@link #combinator}, узел групповой,
     * иначе это элементарное условие.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FilterNode {
        /** Непусто → узел является группой. */
        public String combinator;
        public List<FilterNode> items;

        /** Левая часть — идентификатор поля. */
        public String left;
        /** Вид сравнения — см. {@link CompareOp}. */
        public String op = CompareOp.EQ;
        /** Правая часть — литерал, {@code &Параметр} или список значений. */
        public Object right;
        public boolean disabled;
        /** Показывать пользователю в быстрых настройках. */
        public boolean userVisible;

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isGroup() { return combinator != null && !combinator.isBlank(); }
    }

    /** Виды сравнения отбора. */
    public static final class CompareOp {
        private CompareOp() {}
        public static final String EQ = "eq";
        public static final String NE = "ne";
        public static final String IN = "in";
        public static final String NOT_IN = "notIn";
        public static final String GT = "gt";
        public static final String GE = "ge";
        public static final String LT = "lt";
        public static final String LE = "le";
        public static final String CONTAINS = "contains";
        public static final String NOT_CONTAINS = "notContains";
        public static final String BEGINS_WITH = "beginsWith";
        public static final String FILLED = "filled";
        public static final String NOT_FILLED = "notFilled";
        public static final String BETWEEN = "between";
    }

    // ------------------------------------------------------------- порядок

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderItem {
        public String field;
        /** {@code asc} | {@code desc}. */
        public String direction = "asc";
        public boolean disabled;
    }

    // --------------------------------------------------- условное оформление

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AppearanceItem {
        public String id;
        /** Условие применения; {@code null} — применять всегда. */
        public FilterGroup filter;
        /** Оформляемые поля; пусто — вся строка. */
        public List<String> fields = new ArrayList<>();
        public Appearance appearance = new Appearance();
        /**
         * Области применения: {@code header}, {@code details}, {@code groupTotals},
         * {@code grandTotal}. Пусто — все области.
         */
        public List<String> areas = new ArrayList<>();
        public boolean disabled;
    }

    /** Оформление ячейки/строки. Всё опционально; движок отдаёт как есть фронтенду. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Appearance {
        public String textColor;
        public String backColor;
        public Boolean bold;
        public Boolean italic;
        public String format;
        /** Текст-заместитель значения. */
        public String text;
        public Boolean visible;
        public String align;

        /** Служебная проверка «оформления нет» — в JSON не попадает. */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isEmpty() {
            return textColor == null && backColor == null && bold == null && italic == null
                    && format == null && text == null && visible == null && align == null;
        }
    }

    // ----------------------------------------------------- параметры вывода

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OutputParameters {
        public String title;
        public boolean showTitle = true;
        /** Расположение вертикальных общих итогов: {@code begin|end|none}. */
        public String verticalTotals = "end";
        /** Расположение горизонтальных общих итогов в кросс-таблице: {@code begin|end|none}. */
        public String horizontalTotals = "end";
        /** Расположение группировок: {@code begin} — итог над данными, {@code end} — под. */
        public String groupPlacement = "begin";
        public boolean showParameters = true;
        public boolean showFilter;
        /** Предел исходных записей; превышение помечается как усечённый результат. */
        public Integer maxRows;
    }

    // -------------------------------------------------- пользовательские поля

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserField {
        public String name;
        public String title;
        /** {@code expression} — поле-выражение; {@code select} — поле-выбор (набор условий). */
        public String kind = "expression";
        public String expression;
        /** Для {@code select}: варианты «условие → значение», проверяемые сверху вниз. */
        public List<SelectCase> cases = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SelectCase {
        public FilterGroup filter;
        /** Выражение значения варианта. */
        public String value;
    }
}
