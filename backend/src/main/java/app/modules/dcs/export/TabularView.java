package app.modules.dcs.export;

import app.modules.dcs.expression.ExprEvaluator;
import app.modules.dcs.model.CompositionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Разворачивает дерево результата в плоскую таблицу для выгрузки.
 *
 * <p>Экспорт в Excel и CSV — это всегда прямоугольник, поэтому иерархия кодируется
 * первой колонкой: отступ по уровню плюс представление группировки. Так выгрузка
 * читается тем же взглядом, что и отчёт на экране, и при этом остаётся обычной
 * таблицей, по которой работают автофильтр и сводные.
 */
public final class TabularView {

    /** Строка выгрузки: уровень (для отступа) и значения колонок. */
    public record Row(int level, String label, List<Object> values, boolean total) {}

    public record Table(List<String> headers, List<Row> rows) {}

    private TabularView() {}

    public static Table flatten(CompositionResult result) {
        List<String> headers = new ArrayList<>();
        headers.add("Grouping");
        for (CompositionResult.Column c : result.columns) headers.add(c.title);

        List<Row> rows = new ArrayList<>();
        for (CompositionResult.Node node : result.rows) appendNode(result, node, rows);
        return new Table(headers, rows);
    }

    private static void appendNode(CompositionResult result, CompositionResult.Node node, List<Row> out) {
        List<Object> values = new ArrayList<>();
        for (CompositionResult.Column c : result.columns) values.add(node.cells.get(c.id));

        String label = switch (node.kind) {
            case "grandTotal" -> node.display == null ? "Total" : node.display;
            case "detail" -> "";
            default -> node.display == null ? "" : node.display;
        };
        out.add(new Row(node.level, label, values,
                "grandTotal".equals(node.kind) || "group".equals(node.kind)));

        if (node.children != null) {
            for (CompositionResult.Node child : node.children) appendNode(result, child, out);
        }
    }

    /** Текст ячейки для форматов без типов (CSV). */
    public static String text(Object v) { return ExprEvaluator.asText(v); }
}
