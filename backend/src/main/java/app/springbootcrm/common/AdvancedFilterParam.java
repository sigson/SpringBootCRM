package app.springbootcrm.common;

/**
 * Сериализованная форма advanced-фильтра из ListView.
 *
 * <p>Frontend кодирует advanced-фильтры как query-параметры
 * {@code af_<columnId>_<op>=<value>} (или {@code <v1>|<v2>|...} для multi-value
 * операторов {@code in}/{@code nin}). Контроллер парсит их в список этих записей
 * и передаёт сервис-слою / {@link InMemoryRowFilter}.
 *
 * @param columnId id колонки (как в ListView)
 * @param op       код оператора ({@code eq}, {@code neq}, {@code gt}, {@code gte},
 *                 {@code lt}, {@code lte}, {@code contains}, {@code ncontains},
 *                 {@code startsWith}, {@code endsWith}, {@code in}, {@code nin},
 *                 {@code empty}, {@code notEmpty})
 * @param value    raw-значение (для {@code in}/{@code nin} — pipe-separated)
 */
public record AdvancedFilterParam(String columnId, String op, String value) {}
