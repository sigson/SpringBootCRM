package app.modules.dcs.export;

import app.modules.dcs.model.CompositionResult;

import java.nio.charset.StandardCharsets;

/**
 * Выгрузка результата в CSV.
 *
 * <p>Разделитель — точка с запятой, а перед содержимым пишется BOM: без этих двух
 * уступок Excel в русской локали открывает файл одной колонкой и ломает кириллицу.
 */
public final class CsvExporter {

    private CsvExporter() {}

    public static byte[] export(CompositionResult result) {
        TabularView.Table table = TabularView.flatten(result);
        StringBuilder sb = new StringBuilder();
        sb.append('﻿');

        sb.append(String.join(";", table.headers().stream().map(CsvExporter::quote).toList()));
        sb.append("\r\n");

        for (TabularView.Row row : table.rows()) {
            sb.append(quote("  ".repeat(Math.max(0, row.level())) + row.label()));
            for (Object v : row.values()) sb.append(';').append(quote(TabularView.text(v)));
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String s) {
        String v = s == null ? "" : s;
        if (v.contains("\"") || v.contains(";") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
