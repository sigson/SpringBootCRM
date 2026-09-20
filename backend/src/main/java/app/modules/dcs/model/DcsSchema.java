package app.modules.dcs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * <h2>Схема компоновки данных — декларативное описание источников отчёта.</h2>
 *
 * <p>Аналог {@code DataCompositionSchema} 1С:СКД. Хранится JSON-реквизитом
 * {@code scheme} справочника отчётов и целиком принадлежит этому модулю: хост
 * держит её как непрозрачное дерево.
 *
 * <h3>Где живут тексты запросов</h3>
 * Каноническим носителем запросов и связей является {@link #packed} — <b>одна строка
 * с несколькими запросами</b> в формате {@code QueryPackCodec} модуля Workbench'а.
 * Именно её читает Workbench, собирая наборы в связь. {@link #dataSets} описывает
 * <i>поля</i> наборов (заголовки, роли, доступность для отбора/группировки), а не их
 * SQL — так у текста запроса остаётся ровно один владелец и рассинхрона не бывает.
 *
 * <p>{@link #links} дублирует директивы {@code --#link} упакованной строки: конструктор
 * редактирует связи структурно, а {@code QueryPackCodec} кладёт их в строку при
 * сохранении. Если список непуст, при компоновке он <b>перекрывает</b> связи из строки —
 * «настройка связей» остаётся последним словом, даже когда строку правили руками.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DcsSchema {

    /** Упакованная строка с наборами-запросами и директивами связей. */
    public String packed;

    /** Описания полей наборов данных. */
    public List<DataSet> dataSets = new ArrayList<>();

    /** Настройка связей наборов (перекрывает связи из {@link #packed}, если непуста). */
    public List<DataSetLink> links = new ArrayList<>();

    /** Вычисляемые поля — считаются процессором построчно, а не в SQL. */
    public List<CalculatedField> calculatedFields = new ArrayList<>();

    /** Ресурсы — агрегаты по группировкам. */
    public List<ResourceField> resources = new ArrayList<>();

    /** Параметры схемы. */
    public List<Parameter> parameters = new ArrayList<>();

    /** Набор данных: имя, заголовок и перечень полей. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataSet {
        /** Имя набора; совпадает с именем запроса в упакованной строке. */
        public String name;
        public String title;
        /** {@code query} | {@code union} — вид набора; {@code union} перечисляет вложенные в {@link #items}. */
        public String type = "query";
        /** Имена вложенных наборов для объединения. */
        public List<String> items = new ArrayList<>();
        /**
         * Автозаполнение полей: при {@code true} конструктор дополняет {@link #fields}
         * колонками, которые реально вернул запрос.
         */
        public boolean autoFill = true;
        public List<DataSetField> fields = new ArrayList<>();
    }

    /** Поле набора данных. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataSetField {
        /** Имя колонки в SELECT'е набора. */
        public String name;
        /** Путь данных; по умолчанию равен {@link #name}. */
        public String dataPath;
        public String title;
        /** Роль поля — см. {@link FieldRole}. */
        public String role;
        /** Порядок периода для роли {@code period} (секунда=1, день=2, …). */
        public Integer periodOrder;
        /** Тип значения: {@code string|number|date|boolean}. Подсказка для UI и форматирования. */
        public String valueType;

        public boolean usableInSelection = true;
        public boolean usableInFilter = true;
        public boolean usableInGroup = true;
        public boolean usableInOrder = true;
        /** Исключать из группировок записи с NULL в этом поле. */
        public boolean ignoreNull;
        /** Поле всегда попадает в запрос, даже если не выбрано. */
        public boolean mandatory;
    }

    /** Связь наборов: «главный → подчинённый» с условиями соединения. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataSetLink {
        public String source;
        public String target;
        /** {@code inner|left|right|full|cross}. */
        public String linkType = "left";
        public List<LinkCondition> conditions = new ArrayList<>();
        /** Произвольное условие соединения; перекрывает {@link #conditions}. */
        public String rawCondition;
        public boolean disabled;
    }

    /** Элементарное условие связи. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LinkCondition {
        public String sourceExpr;
        public String operator = "=";
        public String targetExpr;
    }

    /** Вычисляемое поле: выражение на языке компоновки. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CalculatedField {
        public String name;
        public String title;
        public String expression;
        public String role;
        public String valueType;
        public boolean usableInSelection = true;
        public boolean usableInFilter;
        public boolean usableInGroup;
        public boolean usableInOrder = true;
    }

    /** Ресурс: агрегат по группировкам. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceField {
        /** Идентификатор ресурса в выбранных полях (например {@code amountTotal}). */
        public String name;
        public String title;
        /**
         * Выражение ресурса — как правило одна агрегатная функция
         * ({@code Сумма(Sales.amount)}), но допускается и произвольное
         * ({@code Сумма(Sales.amount) / Сумма(Sales.qty)}).
         */
        public String expression;
        /**
         * Имена полей группировок, по которым рассчитывается ресурс. Пусто — по всем
         * («Рассчитывать по…» в 1С).
         */
        public List<String> calcByGroups = new ArrayList<>();
        /** Формат вывода (маска числа), передаётся на фронтенд как есть. */
        public String format;
    }

    /** Параметр схемы. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Parameter {
        public String name;
        public String title;
        /** {@code string|number|date|boolean|list}. */
        public String valueType = "string";
        /** Значение по умолчанию. */
        public Object value;
        /** Доступные значения (список {value,presentation}) для выпадающего списка. */
        public List<AvailableValue> availableValues = new ArrayList<>();
        /** Стандартный период: значение задаётся относительной датой («этот месяц»). */
        public boolean stdPeriod;
        /** Запрет изменения пользователем. */
        public boolean useRestriction;
        /** Показывать в пользовательских настройках. */
        public boolean userVisible = true;
        /** Параметр обязателен: без значения компоновка не выполняется. */
        public boolean required;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AvailableValue {
        public Object value;
        public String presentation;
    }

    public DataSet dataSet(String name) {
        if (name == null || dataSets == null) return null;
        return dataSets.stream().filter(d -> name.equals(d.name)).findFirst().orElse(null);
    }

    public ResourceField resource(String name) {
        if (name == null || resources == null) return null;
        return resources.stream().filter(r -> name.equals(r.name)).findFirst().orElse(null);
    }

    public CalculatedField calculated(String name) {
        if (name == null || calculatedFields == null) return null;
        return calculatedFields.stream().filter(c -> name.equals(c.name)).findFirst().orElse(null);
    }

    public Parameter parameter(String name) {
        if (name == null || parameters == null) return null;
        return parameters.stream().filter(p -> name.equals(p.name)).findFirst().orElse(null);
    }
}
