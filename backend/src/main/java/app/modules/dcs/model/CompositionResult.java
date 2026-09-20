package app.modules.dcs.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <h2>Результат компоновки — дерево значений с данными расшифровки.</h2>
 *
 * <p>Аналог того, что в 1С отдаёт процессор вывода. Плоской таблицей результат быть не
 * может: группировки вложены, у каждого уровня свои итоги, а каждая ячейка должна
 * помнить, из каких значений группировок она получена, — иначе расшифровка невозможна.
 *
 * <p>Оформление приезжает вместе с данными ({@link Node#appearance},
 * {@link Node#cellAppearance}): условное оформление вычисляется там же, где считаются
 * итоги, и фронтенду остаётся только раскрасить.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompositionResult {

    /** Колонка вывода. */
    public static class Column {
        public String id;
        public String title;
        /** {@code dimension} | {@code resource} | {@code field}. */
        public String kind;
        public String valueType;
        public String format;
        public String align;

        public Column() {}
        public Column(String id, String title, String kind, String valueType) {
            this.id = id; this.title = title; this.kind = kind; this.valueType = valueType;
        }
    }

    /** Узел результата: общий итог, группировка, детальная запись или кросс-таблица. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Node {
        /** Путь узла; он же идентификатор расшифровки. */
        public String id;
        /** {@code grandTotal} | {@code group} | {@code detail} | {@code table}. */
        public String kind;
        public int level;
        /** Поле группировки (для {@code group}). */
        public String field;
        public String fieldTitle;
        /** Значение группировки. */
        public Object value;
        /** Представление значения группировки. */
        public String display;
        /** Значения колонок: id колонки → значение. */
        public Map<String, Object> cells = new LinkedHashMap<>();
        /** Оформление отдельных ячеек. */
        public Map<String, DcsSettings.Appearance> cellAppearance;
        /** Оформление всей строки. */
        public DcsSettings.Appearance appearance;
        /** Значения группировок вверх по дереву — вход расшифровки. */
        public Map<String, Object> details;
        public List<Node> children;
        /** Сколько исходных записей стоит за узлом. */
        public int rowCount;

        /** Для {@code table}: дерево заголовков колонок кросс-таблицы. */
        public List<ColumnHeader> columnHeaders;

        public Node(String id, String kind, int level) {
            this.id = id; this.kind = kind; this.level = level;
        }

        public void addChild(Node child) {
            if (children == null) children = new ArrayList<>();
            children.add(child);
        }

        public void appear(String cellId, DcsSettings.Appearance a) {
            if (a == null || a.isEmpty()) return;
            if (cellId == null) {
                appearance = merge(appearance, a);
                return;
            }
            if (cellAppearance == null) cellAppearance = new LinkedHashMap<>();
            cellAppearance.put(cellId, merge(cellAppearance.get(cellId), a));
        }

        private static DcsSettings.Appearance merge(DcsSettings.Appearance base, DcsSettings.Appearance add) {
            if (base == null) return copy(add);
            if (add.textColor != null) base.textColor = add.textColor;
            if (add.backColor != null) base.backColor = add.backColor;
            if (add.bold != null) base.bold = add.bold;
            if (add.italic != null) base.italic = add.italic;
            if (add.format != null) base.format = add.format;
            if (add.text != null) base.text = add.text;
            if (add.visible != null) base.visible = add.visible;
            if (add.align != null) base.align = add.align;
            return base;
        }

        private static DcsSettings.Appearance copy(DcsSettings.Appearance a) {
            DcsSettings.Appearance c = new DcsSettings.Appearance();
            c.textColor = a.textColor; c.backColor = a.backColor;
            c.bold = a.bold; c.italic = a.italic; c.format = a.format;
            c.text = a.text; c.visible = a.visible; c.align = a.align;
            return c;
        }
    }

    /** Заголовок колонки кросс-таблицы; листья несут {@link #key} для адресации ячеек. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ColumnHeader {
        public String key;
        public String field;
        public Object value;
        public String display;
        public int level;
        public List<ColumnHeader> children;

        public ColumnHeader(String key, String field, Object value, String display, int level) {
            this.key = key; this.field = field; this.value = value; this.display = display; this.level = level;
        }
    }

    /** Параметр в том виде, в каком он показывается в шапке отчёта. */
    public static class ParameterOut {
        public String name;
        public String title;
        public Object value;
        public String presentation;
    }

    public String reportId;
    public String title;
    public List<Column> columns = new ArrayList<>();
    /** Корневые узлы вывода: по одному на элемент структуры. */
    public List<Node> rows = new ArrayList<>();
    public List<ParameterOut> parameters = new ArrayList<>();
    /** Итоговый SQL — показывается в отладке и в предпросмотре. */
    public String sql;
    public List<String> warnings = new ArrayList<>();
    /** Сколько исходных записей прочитано. */
    public int sourceRowCount;
    /** Выборка упёрлась в предел: итоги посчитаны по неполным данным. */
    public boolean truncated;
    public long elapsedMs;
    /** Агрегировала ли СУБД (иначе итоги считал процессор по детальным записям). */
    public boolean aggregatedInSql;
}
