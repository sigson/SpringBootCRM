package app.modules.dcs.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * <h2>Подстановка параметров: {@code &Имя} → {@code ?} плюс список значений.</h2>
 *
 * <p>Единственное место, где текст запроса встречается со значениями. Значение не
 * склеивается с SQL никогда: проход слева направо заменяет каждое вхождение на
 * позиционный маркер и складывает значение в том же порядке, в каком JDBC потом
 * пронумерует позиции. Инъекция становится невозможной по построению, а не по
 * дисциплине вызывающего.
 *
 * <p>Класс общий для всего модуля сознательно. Текст набора данных исполняется не
 * только при компоновке: его же запускает автозаполнение полей и предпросмотр данных
 * в конструкторе. Вторая реализация этого прохода неизбежно разошлась бы с первой —
 * и разошлась: пока её не было, оба этих пути падали на первом же {@code &Параметре}.
 *
 * <p>Внутри строковых литералов {@code &} не трогается: там это обычный символ.
 */
public final class ParameterBinder {

    /** Готовый к исполнению SQL и значения в порядке позиций. */
    public record Bound(String sql, List<Object> values) {}

    private ParameterBinder() {}

    /**
     * @param sql      текст с маркерами {@code &Имя}
     * @param resolver имя → значение; {@code null} допустим и уходит биндом как NULL
     * @param onUnresolved вызывается для имени, которому резолвер не дал значения —
     *                     вызывающий решает, предупреждение это или ошибка
     */
    public static Bound bind(String sql,
                             Function<String, Object> resolver,
                             Consumer<String> onUnresolved) {
        StringBuilder out = new StringBuilder(sql.length());
        List<Object> values = new ArrayList<>();
        char quote = 0;
        int i = 0;

        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == quote) quote = 0;
                i++;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; out.append(c); i++; continue; }

            if (c == '&' && i + 1 < sql.length() && isIdentStart(sql.charAt(i + 1))) {
                int j = i + 1;
                while (j < sql.length() && isIdentPart(sql.charAt(j))) j++;
                String name = sql.substring(i + 1, j);
                Object value = resolver.apply(name);
                if (value == null && onUnresolved != null) onUnresolved.accept(name);
                out.append('?');
                values.add(value);
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return new Bound(out.toString(), values);
    }

    /** Имена параметров, встречающихся в тексте (в порядке появления, без повторов). */
    public static List<String> namesIn(String sql) {
        List<String> names = new ArrayList<>();
        bind(sql, name -> { if (!names.contains(name)) names.add(name); return null; }, null);
        return names;
    }

    /**
     * Имя параметра: буквы (включая кириллицу — имена параметров пишут по-русски),
     * цифры и подчёркивание, не с цифры.
     */
    private static boolean isIdentStart(char c) { return Character.isLetter(c) || c == '_'; }
    private static boolean isIdentPart(char c)  { return Character.isLetterOrDigit(c) || c == '_'; }
}
