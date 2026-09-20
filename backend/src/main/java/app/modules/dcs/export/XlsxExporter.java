package app.modules.dcs.export;

import app.modules.dcs.model.CompositionResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * <h2>Выгрузка результата в XLSX без внешних библиотек.</h2>
 *
 * <p>Файл Excel — это zip с несколькими XML. Отчёт выгружает прямоугольник из чисел,
 * строк и жирных итогов, и для такой задачи целый Apache POI (плюс его транзитивные
 * зависимости) был бы платой за неиспользуемое: разбор чужих книг, формулы, картинки,
 * стили. Поэтому здесь минимальный писатель на {@link ZipOutputStream} — ровно те
 * четыре части, которые Excel и LibreOffice считают корректной книгой.
 *
 * <p>Строки пишутся как {@code inlineStr} — без таблицы общих строк. Это чуть больший
 * файл, но писатель остаётся потоковым и однопроходным.
 *
 * <p>Числа пишутся числами (а не текстом), иначе итоги в выгрузке нельзя ни сложить,
 * ни отсортировать — а это первое, что с ними делают.
 */
public final class XlsxExporter {

    private XlsxExporter() {}

    /** Порядковые номера стилей из {@link #STYLES}. */
    private static final int STYLE_DEFAULT = 0;
    private static final int STYLE_HEADER = 1;
    private static final int STYLE_TOTAL = 2;

    public static byte[] export(CompositionResult result) {
        TabularView.Table table = TabularView.flatten(result);
        String sheetName = sanitizeSheetName(result.title);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {

            put(zip, "[Content_Types].xml", CONTENT_TYPES);
            put(zip, "_rels/.rels", ROOT_RELS);
            put(zip, "xl/workbook.xml", workbook(sheetName));
            put(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            put(zip, "xl/styles.xml", STYLES);
            put(zip, "xl/worksheets/sheet1.xml", sheet(table));

            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build the XLSX file", e);
        }
    }

    // ------------------------------------------------------------------ лист

    private static String sheet(TabularView.Table table) {
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>""");

        int rowNum = 1;
        sb.append("<row r=\"").append(rowNum).append("\">");
        for (int c = 0; c < table.headers().size(); c++) {
            appendInlineString(sb, ref(c, rowNum), table.headers().get(c), STYLE_HEADER);
        }
        sb.append("</row>");

        for (TabularView.Row row : table.rows()) {
            rowNum++;
            int style = row.total() ? STYLE_TOTAL : STYLE_DEFAULT;
            sb.append("<row r=\"").append(rowNum).append("\">");
            appendInlineString(sb, ref(0, rowNum),
                    "    ".repeat(Math.max(0, row.level())) + row.label(), style);
            List<Object> values = row.values();
            for (int c = 0; c < values.size(); c++) {
                appendValue(sb, ref(c + 1, rowNum), values.get(c), style);
            }
            sb.append("</row>");
        }

        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, String ref, Object value, int style) {
        if (value == null) return;
        BigDecimal number = asNumber(value);
        if (number != null) {
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"><v>")
              .append(number.toPlainString()).append("</v></c>");
            return;
        }
        appendInlineString(sb, ref, String.valueOf(value), style);
    }

    private static void appendInlineString(StringBuilder sb, String ref, String text, int style) {
        sb.append("<c r=\"").append(ref).append("\" s=\"").append(style)
          .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
          .append(escape(text)).append("</t></is></c>");
    }

    /** Только настоящие числа: строку, похожую на число, оставляем строкой. */
    private static BigDecimal asNumber(Object v) {
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        return null;
    }

    /** Адрес ячейки: {@code A1}, {@code AB12}. */
    private static String ref(int column, int row) {
        StringBuilder col = new StringBuilder();
        int c = column;
        do {
            col.insert(0, (char) ('A' + c % 26));
            c = c / 26 - 1;
        } while (c >= 0);
        return col + String.valueOf(row);
    }

    // ------------------------------------------------------------------- части

    private static String workbook(String sheetName) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets>
                </workbook>""".formatted(escape(sheetName));
    }

    private static final String CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
            <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
            </Types>""";

    private static final String ROOT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>""";

    private static final String WORKBOOK_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            </Relationships>""";

    /** Три стиля: обычный, шапка и итог — больше отчёту для выгрузки не нужно. */
    private static final String STYLES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <fonts count="2">
              <font><sz val="11"/><name val="Calibri"/></font>
              <font><b/><sz val="11"/><name val="Calibri"/></font>
            </fonts>
            <fills count="3">
              <fill><patternFill patternType="none"/></fill>
              <fill><patternFill patternType="gray125"/></fill>
              <fill><patternFill patternType="solid"><fgColor rgb="FFEFEFEF"/><bgColor indexed="64"/></patternFill></fill>
            </fills>
            <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
            <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
            <cellXfs count="3">
              <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
              <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
              <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
            </cellXfs>
            </styleSheet>""";

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Excel запрещает в имени листа {@code : \ / ? * [ ]} и длину больше 31 символа. */
    private static String sanitizeSheetName(String title) {
        String name = title == null || title.isBlank() ? "Report" : title.trim();
        name = name.replaceAll("[:\\\\/?*\\[\\]]", " ");
        return name.length() > 31 ? name.substring(0, 31) : name;
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    // XML 1.0 не допускает управляющие символы — вычищаем, иначе файл не откроется.
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
