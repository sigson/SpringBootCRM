package app.springbootcrm.common;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Обобщённый фильтр/поиск/сортировка строк в памяти.
 *
 * <p>Делает то же, что фронтовый {@code applyFilter} в {@code filterTypes.ts}: quick-фильтры
 * (substring), глобальный поиск, advanced-операторы (= ≠ &gt; ≥ &lt; ≤ contains startsWith in
 * nin empty notEmpty) и сортировку — единый контракт между клиентом и всеми paged-endpoint'ами.
 *
 * <p>Вызывающий передаёт колоночные аксессоры ({@link Column}): {@code textOf} (substring/поиск/
 * сортировка) + опционально {@code idOf} (сравнение по каноническому значению — UUID для ref,
 * code для enum), поэтому фильтр ведёт себя точно как на UI.
 *
 * <p>Для малых/средних наборов, грузимых одним вызовом репозитория (справочники SpringBootCRM —
 * тысячи строк). Для больших JPA-таблиц используйте {@link SqlRowFilter}.
 */
public final class InMemoryRowFilter {

    private InMemoryRowFilter() {}

    /** Описание одной колонки для фильтра. */
    public record Column<T>(
            String id,
            Function<T, String> textOf,
            Function<T, String> idOf,
            ColumnFilterType filterType
    ) {
        public static <T> Column<T> string(String id, Function<T, String> textOf) {
            return new Column<>(id, textOf, textOf, ColumnFilterType.STRING);
        }
        public static <T> Column<T> ref(String id, Function<T, String> textOf,
                                         Function<T, String> idOf) {
            return new Column<>(id, textOf, idOf, ColumnFilterType.REFERENCE);
        }
        public static <T> Column<T> date(String id, Function<T, String> textOf) {
            return new Column<>(id, textOf, textOf, ColumnFilterType.DATE);
        }
        public static <T> Column<T> number(String id, Function<T, String> textOf) {
            return new Column<>(id, textOf, textOf, ColumnFilterType.NUMBER);
        }
    }

    /** Тип значения в колонке (зеркало {@code ColumnFilterType} на фронте). */
    public enum ColumnFilterType { STRING, NUMBER, DATE, BOOLEAN, ENUM, REFERENCE }

    /**
     * Применяет все фильтры/поиск/сортировку и возвращает страницу.
     *
     * @param rows          входные строки (уже прочитаны из репозитория + access-фильтры)
     * @param columns       колонки списка (id → аксессоры)
     * @param search        глобальный поиск (substring по всем textOf), либо {@code null}
     * @param columnFilters quick-фильтры: {@code columnId → substring}, либо {@code null}
     * @param advanced      advanced-фильтры с операторами, либо {@code null}
     * @param sortBy        id колонки сортировки, либо {@code null}
     * @param sortDir       {@code "asc"} | {@code "desc"} (default asc)
     * @param page          0-based индекс страницы
     * @param size          размер страницы
     */
    public static <T> PageResponse<T> apply(
            List<T> rows,
            List<Column<T>> columns,
            String search,
            Map<String, String> columnFilters,
            List<AdvancedFilterParam> advanced,
            String sortBy, String sortDir,
            int page, int size) {

        Map<String, Column<T>> byId = columns.stream()
                .collect(Collectors.toMap(Column::id, c -> c, (a, b) -> a));

        List<T> filtered = new ArrayList<>(rows);

        // 1. Quick-фильтры (substring по textOf колонки).
        if (columnFilters != null) {
            for (var e : columnFilters.entrySet()) {
                String sub = e.getValue() == null ? "" : e.getValue().trim().toLowerCase(Locale.ROOT);
                if (sub.isEmpty()) continue;
                Column<T> col = byId.get(e.getKey());
                if (col == null) continue;
                filtered = filtered.stream()
                        .filter(r -> {
                            String v = col.textOf().apply(r);
                            return v != null && v.toLowerCase(Locale.ROOT).contains(sub);
                        })
                        .collect(Collectors.toList());
            }
        }

        // 2. Глобальный поиск (substring по всем textOf видимых колонок).
        if (search != null && !search.isBlank()) {
            String q = search.trim().toLowerCase(Locale.ROOT);
            filtered = filtered.stream()
                    .filter(r -> columns.stream().anyMatch(c -> {
                        String v = c.textOf().apply(r);
                        return v != null && v.toLowerCase(Locale.ROOT).contains(q);
                    }))
                    .collect(Collectors.toList());
        }

        // 3. Advanced-фильтры с операторами.
        if (advanced != null && !advanced.isEmpty()) {
            for (AdvancedFilterParam f : advanced) {
                Column<T> col = byId.get(f.columnId());
                if (col == null) continue;
                filtered = filtered.stream()
                        .filter(r -> matchesAdvanced(col, r, f))
                        .collect(Collectors.toList());
            }
        }

        // 4. Сортировка.
        if (sortBy != null && !sortBy.isBlank()) {
            Column<T> col = byId.get(sortBy);
            if (col != null) {
                Comparator<T> cmp = comparatorFor(col);
                if ("desc".equalsIgnoreCase(sortDir)) cmp = cmp.reversed();
                filtered.sort(cmp);
            }
        }

        // 5. Пагинация.
        int total = filtered.size();
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 500);
        int from = Math.min(p * s, total);
        int to = Math.min(from + s, total);
        return PageResponse.of(filtered.subList(from, to), p, s, total);
    }

    // -------- Advanced operator matching --------

    private static <T> boolean matchesAdvanced(Column<T> col, T row, AdvancedFilterParam f) {
        String text = nullSafe(col.textOf().apply(row));
        String idValue = nullSafe(col.idOf().apply(row));
        String op = f.op();

        // empty / notEmpty: работают по text (display-значению).
        if ("empty".equals(op))    return text.isBlank();
        if ("notEmpty".equals(op)) return !text.isBlank();

        // in / nin: список значений, разделённых '|'. Сравниваем idValue (для ref/enum
        // это канонический ID/code, для строк — то же, что textOf).
        if ("in".equals(op) || "nin".equals(op)) {
            String raw = f.value() == null ? "" : f.value();
            Set<String> set = Arrays.stream(raw.split("\\|"))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(HashSet::new));
            if (set.isEmpty()) return true;
            boolean contains;
            if (col.filterType() == ColumnFilterType.STRING) {
                String lc = idValue.toLowerCase(Locale.ROOT);
                contains = set.stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).equals(lc));
            } else {
                contains = set.contains(idValue);
            }
            return "in".equals(op) == contains;
        }

        String v = nullSafe(f.value());
        if (v.isBlank()) return true;   // пустой фильтр = нет фильтра

        switch (col.filterType()) {
            case NUMBER -> {
                Double a = parseNumber(idValue);
                Double b = parseNumber(v);
                if (a != null && b != null) {
                    return switch (op) {
                        case "eq"  -> Objects.equals(a, b);
                        case "neq" -> !Objects.equals(a, b);
                        case "gt"  -> a > b;
                        case "gte" -> a >= b;
                        case "lt"  -> a < b;
                        case "lte" -> a <= b;
                        default    -> true;
                    };
                }
            }
            case DATE -> {
                LocalDate a = parseDate(text);
                LocalDate b = parseDate(v);
                if (a != null && b != null) {
                    int c = a.compareTo(b);
                    return switch (op) {
                        case "eq"  -> c == 0;
                        case "neq" -> c != 0;
                        case "gt"  -> c >  0;
                        case "gte" -> c >= 0;
                        case "lt"  -> c <  0;
                        case "lte" -> c <= 0;
                        default    -> true;
                    };
                }
            }
            case BOOLEAN -> {
                boolean a = parseBool(idValue);
                boolean b = parseBool(v);
                if ("eq".equals(op))  return a == b;
                if ("neq".equals(op)) return a != b;
            }
            case REFERENCE, ENUM -> {
                if ("eq".equals(op))  return idValue.equals(v);
                if ("neq".equals(op)) return !idValue.equals(v);
            }
            case STRING -> {}
        }

        // String fallback (работает и как последний запасной путь для number/date,
        // если парсинг не удался).
        String lc = text.toLowerCase(Locale.ROOT);
        String lcv = v.toLowerCase(Locale.ROOT);
        return switch (op) {
            case "eq"         -> text.equals(v);
            case "neq"        -> !text.equals(v);
            case "gt"         -> text.compareTo(v) >  0;
            case "gte"        -> text.compareTo(v) >= 0;
            case "lt"         -> text.compareTo(v) <  0;
            case "lte"        -> text.compareTo(v) <= 0;
            case "contains"   -> lc.contains(lcv);
            case "ncontains"  -> !lc.contains(lcv);
            case "startsWith" -> lc.startsWith(lcv);
            case "endsWith"   -> lc.endsWith(lcv);
            default           -> true;
        };
    }

    // -------- Comparator helpers --------

    private static <T> Comparator<T> comparatorFor(Column<T> col) {
        return (a, b) -> {
            String ta = nullSafe(col.textOf().apply(a));
            String tb = nullSafe(col.textOf().apply(b));
            switch (col.filterType()) {
                case NUMBER -> {
                    Double na = parseNumber(ta);
                    Double nb = parseNumber(tb);
                    if (na != null && nb != null) return Double.compare(na, nb);
                }
                case DATE -> {
                    LocalDate da = parseDate(ta);
                    LocalDate db = parseDate(tb);
                    if (da != null && db != null) return da.compareTo(db);
                }
                default -> {}
            }
            return ta.compareToIgnoreCase(tb);
        };
    }

    // -------- Parsers --------

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static Double parseNumber(String s) {
        if (s == null) return null;
        String t = s.trim().replace(',', '.');
        if (t.isEmpty()) return null;
        try { return Double.parseDouble(t); }
        catch (NumberFormatException e) { return null; }
    }

    private static LocalDate parseDate(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        // "dd.MM.yyyy"
        if (t.matches("^\\d{1,2}\\.\\d{1,2}\\.\\d{4}$")) {
            String[] p = t.split("\\.");
            try {
                return LocalDate.of(Integer.parseInt(p[2]),
                        Integer.parseInt(p[1]), Integer.parseInt(p[0]));
            } catch (Exception e) { return null; }
        }
        // ISO local date
        try { return LocalDate.parse(t); }
        catch (Exception ignored) {}
        // ISO instant — беремо лише date-частину
        try { return LocalDate.parse(t.substring(0, Math.min(10, t.length()))); }
        catch (Exception ignored) {}
        // ISO Instant
        try { return Instant.parse(t).atZone(java.time.ZoneOffset.UTC).toLocalDate(); }
        catch (Exception ignored) {}
        return null;
    }

    private static boolean parseBool(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return "true".equals(t) || "1".equals(t) || "yes".equals(t)
                || "yes".equals(t) || "y".equals(t);
    }
}
