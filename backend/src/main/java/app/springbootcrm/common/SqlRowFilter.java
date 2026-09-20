package app.springbootcrm.common;

import app.springbootcrm.metadata.ObjectRowQueryService;
import app.springbootcrm.reference.Reference;
import app.springbootcrm.registers.exchangerate.ExchangeRate;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.SingularAttribute;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Обобщённый фильтр/поиск/сортировка на уровне SQL (JPA Criteria / Specification).
 *
 * <p>SQL-аналог {@link InMemoryRowFilter}: принимает тот же контракт query-параметров
 * (quick-фильтры, глобальный поиск, advanced-фильтры с операторами, сортировка) и строит
 * {@link Specification} для {@code JpaSpecificationExecutor.findAll(spec, Pageable)}.
 * Фильтрация/сортировка/пагинация выполняются СУБД, поэтому в память грузится только
 * нужная страница, а {@code total} считается одним {@code count(...)}.
 *
 * <p><b>Row-level security сохранён:</b> {@code findAll(spec, pageable)} матчится паттерном
 * {@code find*} в {@link domain.core.persistence.AccessFilterActivator}, поэтому Hibernate
 * row-filter'ы и admin-bypass применяются и к странице, и к count-запросу.
 *
 * <h3>Семантика операторов</h3>
 * Зеркалит {@link InMemoryRowFilter} и фронтовый {@code applyFilter}:
 * <ul>
 *   <li>{@code contains}/{@code startsWith}/{@code endsWith}/quick/search — case-insensitive
 *       {@code LOWER(col) LIKE ...}, wildcard-ы пользователя экранируются;</li>
 *   <li>{@code eq}/{@code neq}/{@code gt}/{@code gte}/{@code lt}/{@code lte} — типозависимые;</li>
 *   <li>{@code in}/{@code nin} — принадлежность множеству (pipe-separated);</li>
 *   <li>{@code empty}/{@code notEmpty} — {@code IS NULL}/пустая строка.</li>
 * </ul>
 * Колонка, чьего backing-атрибута нет в метамодели сущности ({@code Root.getModel()}),
 * молча игнорируется — один набор колонок безопасен для разнородных reference-типов.
 *
 * <p><b>Отличия от in-memory:</b> глобальный/quick-поиск по нестроковым и по ref-колонкам
 * без {@code refResolver} (audit-UUID) не выполняется (свободный текст не сравнивается с
 * UUID/таймстампом); сортировка по {@code display}-колонке использует SQL-concat.
 */
public final class SqlRowFilter {

    private SqlRowFilter() {}

    public enum Type { STRING, NUMBER, DATE, BOOLEAN, ENUM, REFERENCE, UNION_REFERENCE }

    /**
     * Один ключ сортировки. {@code dir} — {@code "asc"} | {@code "desc"} (default asc).
     * Список таких ключей задаёт многоколоночную сортировку (приоритет = порядок).
     */
    public record SortSpec(String columnId, String dir) {}

    /**
     * Разбирает пару строк {@code sortBy}/{@code sortDir} в список {@link SortSpec}.
     * Поддерживает и одноколоночный ({@code sortBy=code}), и многоколоночный
     * ({@code sortBy=code,name&sortDir=asc,desc}) форматы. Если направлений меньше,
     * чем колонок, недостающие считаются {@code asc}.
     */
    public static List<SortSpec> parseSorts(String sortBy, String sortDir) {
        if (sortBy == null || sortBy.isBlank()) return List.of();
        String[] cols = sortBy.split(",");
        String[] dirs = (sortDir == null || sortDir.isBlank())
                ? new String[0] : sortDir.split(",");
        List<SortSpec> out = new ArrayList<>(cols.length);
        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].trim();
            if (c.isEmpty()) continue;
            String d = (i < dirs.length) ? dirs[i].trim() : "asc";
            out.add(new SortSpec(c, d.isEmpty() ? "asc" : d));
        }
        return out;
    }

    /** Поставщик JPA-выражения для колонки (значение для сравнения/сортировки). */
    @FunctionalInterface
    public interface ExprFn<T> {
        Expression<?> apply(Root<T> root, CriteriaBuilder cb);
    }

    /**
     * Описание одной колонки для SQL-фильтра.
     *
     * @param id           id колонки (как в ListView / в query-параметрах cf_/af_/sortBy)
     * @param type         тип значения колонки
     * @param expr         выражение значения: для STRING — {@code Expression<String>}; для DATE —
     *                     темпоральное выражение; для REFERENCE/ENUM — канонический id-выражение (например, UUID-колонка)
     * @param requiredAttr имя singular-атрибута, который должен существовать в метамодели, чтобы колонка
     *                     была активной ({@code null} = всегда активна / self-guarded)
     * @param searchable   участвует ли в глобальном/quick подстрочном поиске
     * @param refResolver  для REFERENCE: резолвер «подстрока → множество канонических id»
     *                     (например, owner-текст → UUID пользователей). {@code null} — ref-колонка
     *                     не участвует в текстовом поиске
     */
    public record Column<T>(
            String id,
            Type type,
            ExprFn<T> expr,
            String requiredAttr,
            boolean searchable,
            Function<String, Collection<?>> refResolver,
            /** Для UNION_REFERENCE — выражение колонки-дискриминатора (type_id). Иначе null. */
            ExprFn<T> typeExpr
    ) {
        public static <T> Column<T> string(String id, String attr) {
            return new Column<>(id, Type.STRING, (r, cb) -> r.get(attr), attr, true, null, null);
        }

        /** STRING-колонка с произвольным выражением (например, concat для display). */
        public static <T> Column<T> stringExpr(String id, ExprFn<T> expr, String guardAttr) {
            return new Column<>(id, Type.STRING, expr, guardAttr, true, null, null);
        }

        public static <T> Column<T> number(String id, String attr) {
            return new Column<>(id, Type.NUMBER, (r, cb) -> r.get(attr), attr, false, null, null);
        }

        public static <T> Column<T> instant(String id, String attr) {
            return new Column<>(id, Type.DATE, (r, cb) -> r.get(attr), attr, false, null, null);
        }

        public static <T> Column<T> bool(String id, String attr) {
            return new Column<>(id, Type.BOOLEAN, (r, cb) -> r.get(attr), attr, false, null, null);
        }

        /**
         * Reference-колонка. {@code idExpr} — выражение канонического id (обычно
         * {@code root.get(field).get("targetIdRaw")}, тип UUID). {@code refResolver}
         * (опционально) резолвит подстроку в множество UUID для текстового поиска.
         */
        public static <T> Column<T> reference(String id, ExprFn<T> idExpr, String guardAttr,
                                              Function<String, Collection<?>> refResolver) {
            return new Column<>(id, Type.REFERENCE, idExpr, guardAttr, refResolver != null, refResolver, null);
        }

        /**
         * Union-reference колонка. Значение фильтра кодируется как {@code "<typeId>:<uuid>"};
         * {@code idExpr} — выражение id-колонки (например, {@code subject.targetIdRaw}),
         * {@code typeIdExpr} — выражение колонки-дискриминатора (например, {@code subject.targetTypeId}).
         * Предикат добавляет оба условия: {@code id = <uuid> AND type_id = <typeId>}.
         */
        public static <T> Column<T> unionReference(String id, ExprFn<T> idExpr, ExprFn<T> typeIdExpr,
                                                   String guardAttr) {
            return new Column<>(id, Type.UNION_REFERENCE, idExpr, guardAttr, false, null, typeIdExpr);
        }
    }

    // =====================================================================
    // Public API
    // =====================================================================

    /**
     * Строит {@link Specification}, объединяющую quick-фильтры, глобальный поиск,
     * picker-{@code query}, advanced-фильтры и сортировку (через {@code orderBy}
     * на не-count-запросе).
     *
     * @param columns       описание колонок ({@code id → аксессоры})
     * @param query         picker-поиск (подстрока по {@code queryColumnIds}), либо {@code null}
     * @param queryColumnIds колонки, по которым действует {@code query} (обычно code/name)
     * @param search        глобальный поиск (подстрока по всем searchable-колонкам), либо {@code null}
     * @param columnFilters quick-фильтры {@code columnId → substring}, либо {@code null}
     * @param advanced      advanced-фильтры с операторами, либо {@code null}
     * @param sortBy        id колонки сортировки, либо {@code null}
     * @param sortDir       {@code "asc"} | {@code "desc"} (default asc)
     */
    public static <T> Specification<T> build(
            List<Column<T>> columns,
            String query, List<String> queryColumnIds,
            String search,
            Map<String, String> columnFilters,
            List<AdvancedFilterParam> advanced,
            String sortBy, String sortDir) {
        return build(columns, query, queryColumnIds, search, columnFilters, advanced,
                parseSorts(sortBy, sortDir));
    }

    /**
     * Многоколоночный вариант {@link #build}. {@code sorts} задаёт порядок ключей
     * сортировки (первый — главный). Ссылочные/union-колонки сортируются по
     * {@code type_id} (дискриминатору); моно-ссылка без дискриминатора в сортировке
     * не участвует (сортировать UUID нет смысла) — как и на клиенте.
     */
    public static <T> Specification<T> build(
            List<Column<T>> columns,
            String query, List<String> queryColumnIds,
            String search,
            Map<String, String> columnFilters,
            List<AdvancedFilterParam> advanced,
            List<SortSpec> sorts) {

        Map<String, Column<T>> byId = columns.stream()
                .collect(Collectors.toMap(Column::id, c -> c, (a, b) -> a, HashMap::new));

        return (root, cq, cb) -> {
            Set<String> attrs = root.getModel().getSingularAttributes().stream()
                    .map(SingularAttribute::getName)
                    .collect(Collectors.toSet());

            List<Predicate> preds = new ArrayList<>();

            // 1. Quick-фильтры (substring по колонке).
            if (columnFilters != null) {
                for (var e : columnFilters.entrySet()) {
                    String sub = trimOrEmpty(e.getValue());
                    if (sub.isEmpty()) continue;
                    Column<T> col = byId.get(e.getKey());
                    if (!active(col, attrs)) continue;
                    Predicate p = substringPredicate(col, sub, root, cb);
                    if (p != null) preds.add(p);
                }
            }

            // 2. Глобальный поиск (OR по всем searchable-колонкам).
            if (search != null && !search.isBlank()) {
                addOrSearch(preds, columns, attrs, search.trim(), root, cb, null);
            }

            // 3. Picker-query (OR по code/name по умолчанию).
            if (query != null && !query.isBlank()) {
                addOrSearch(preds, columns, attrs, query.trim(), root, cb, queryColumnIds);
            }

            // 4. Advanced-фильтры с операторами.
            if (advanced != null) {
                for (AdvancedFilterParam f : advanced) {
                    Column<T> col = byId.get(f.columnId());
                    if (!active(col, attrs)) continue;
                    Predicate p = advancedPredicate(col, f, root, cb);
                    if (p != null) preds.add(p);
                }
            }

            // 5. Сортировка — только для основного (не count) запроса.
            //    Многоколоночная: порядок ключей = приоритет. Добавляем стабильный
            //    tiebreaker по первичному ключу, иначе SQL-пагинация недетерминирована.
            if (!isCountQuery(cq)) {
                List<Order> orders = new ArrayList<>();
                Set<String> sortedIds = new HashSet<>();
                if (sorts != null) {
                    for (SortSpec s : sorts) {
                        if (s == null || s.columnId() == null || s.columnId().isBlank()) continue;
                        if (sortedIds.contains(s.columnId())) continue;   // дубль-ключ
                        Column<T> col = byId.get(s.columnId());
                        if (!active(col, attrs)) continue;
                        Expression<?> e = orderExpr(col, root, cb);
                        if (e == null) continue;   // ref без дискриминатора → не сортируем
                        orders.add("desc".equalsIgnoreCase(s.dir()) ? cb.desc(e) : cb.asc(e));
                        sortedIds.add(s.columnId());
                    }
                }
                if (attrs.contains("id") && !sortedIds.contains("id")) {
                    // Напрям id-tiebreaker'а МАЄ збігатися з keyset-шляхом
                    // (див. keysetSpec/idCmp): там id порівнюється у напрямку
                    // ГОЛОВНОГО (першого) сорт-ключа. Якщо тут жорстко лишити asc,
                    // то при сортуванні DESC offset дасть (col DESC, id ASC), а keyset —
                    // (col DESC, id DESC); усередині групи однакових значень col
                    // порядок РОЗІЙДЕТЬСЯ, і сусідні сторінки, завантажені різними
                    // шляхами, перекриються (один агрегат у двох сторінках → дубль-ключ).
                    boolean primaryDesc = sorts != null && !sorts.isEmpty()
                            && "desc".equalsIgnoreCase(sorts.get(0).dir());
                    Expression<?> idExpr = root.get("id");
                    orders.add(primaryDesc ? cb.desc(idExpr) : cb.asc(idExpr));
                }
                if (!orders.isEmpty()) {
                    cq.orderBy(orders);
                }
            }

            return preds.isEmpty() ? cb.conjunction() : cb.and(preds.toArray(new Predicate[0]));
        };
    }

    /**
     * <h3>Keyset (seek) specification для глибокої пагінації.</h3>
     *
     * <p>Будує ту саму {@link Specification} (фільтри + пошук + advanced + {@code ORDER BY}),
     * що й {@link #build}, але ДОДАТКОВО додає seek-предикат «рядки ПІСЛЯ якоря»:
     * <pre>  (sortCol, id) &gt; (afterValue, afterId)   // для asc; для desc — &lt;</pre>
     * розкритий як рядкове порівняння без кортежів (сумісно з H2):
     * <pre>  sortCol &gt; v  OR  (sortCol = v AND id &gt; aid)</pre>
     *
     * <p>На відміну від offset-пагінації, seek НЕ пропускає N рядків — СУБД одразу
     * стрибає в індекс {@code (sortCol, id)} (міграція V5) і читає {@code LIMIT} рядків.
     * Вартість стала й НЕ залежить від глибини сторінки. Викликається лише коли сорт —
     * одноколонковий по {@code sortCol} (+{@code id}-tiebreaker); інакше використовується
     * offset-варіант (див. {@code ObjectRowQueryService}).
     *
     * @param columns     описи колонок (як у {@link #build})
     * @param search      глобальний пошук (підрядок), або {@code null}
     * @param columnFilters quick-фільтри, або {@code null}
     * @param advanced    advanced-фільтри, або {@code null}
     * @param sorts       сорт-ключі (очікується РІВНО один + неявний id-tiebreaker)
     * @param sortCol     id колонки-ключа (STRING, напр. {@code code}/{@code name})
     * @param sortDir     {@code "asc"} | {@code "desc"}
     * @param afterValue  значення {@code sortCol} останнього рядка попередньої сторінки
     * @param afterId     {@code id} останнього рядка попередньої сторінки (UUID-рядок)
     */
    public static <T> Specification<T> keysetSpec(
            List<Column<T>> columns,
            String search,
            Map<String, String> columnFilters,
            List<AdvancedFilterParam> advanced,
            List<SortSpec> sorts,
            String sortCol, String sortDir,
            String afterValue, String afterId) {

        Specification<T> base = build(columns, null, List.of(), search, columnFilters, advanced, sorts);
        boolean desc = "desc".equalsIgnoreCase(sortDir);
        UUID anchorId;
        try { anchorId = afterId == null ? null : UUID.fromString(afterId.trim()); }
        catch (RuntimeException e) { anchorId = null; }
        final UUID aid = anchorId;
        final String aval = afterValue;   // може бути null (NULL-значення sortCol)

        return (root, cq, cb) -> {
            Predicate basePred = base.toPredicate(root, cq, cb);

            // seek будуємо лише на основному (не count) запиті й коли якір валідний.
            if (isCountQuery(cq) || aid == null) {
                return basePred;
            }

            @SuppressWarnings("unchecked")
            Expression<String> col = (Expression<String>) (Expression<?>) root.get(sortCol);
            Expression<?> idExpr = root.get("id");

            Predicate seek;
            if (aval == null) {
                // Якір має NULL у sortCol. У ASC NULL'и йдуть першими (H2): «після» —
                // або не-NULL, або той самий NULL з більшим id. У DESC — навпаки.
                Predicate sameNullLaterId = cb.and(cb.isNull(col), idCmp(cb, idExpr, aid, desc));
                seek = desc
                        ? sameNullLaterId
                        : cb.or(cb.isNotNull(col), sameNullLaterId);
            } else {
                Predicate strictlyAfterVal = desc ? cb.lessThan(col, aval) : cb.greaterThan(col, aval);
                Predicate sameValLaterId = cb.and(cb.equal(col, aval), idCmp(cb, idExpr, aid, desc));
                Predicate tail = cb.or(strictlyAfterVal, sameValLaterId);
                // У ASC рядки з NULL (якщо є) розташовані ДО будь-якого не-NULL якоря,
                // тож вони вже «позаду» — не включаємо. У DESC NULL'и в кінці — теж позаду.
                seek = tail;
            }

            return basePred == null ? seek : cb.and(basePred, seek);
        };
    }

    /** {@code id > aid} (asc) або {@code id < aid} (desc) для UUID-tiebreaker'а. */
    @SuppressWarnings("unchecked")
    private static Predicate idCmp(CriteriaBuilder cb, Expression<?> idExpr, UUID aid, boolean desc) {
        Expression<UUID> e = (Expression<UUID>) idExpr;
        return desc ? cb.lessThan(e, aid) : cb.greaterThan(e, aid);
    }
    /* подставляя вместо плейсхолдеров {@code {field}} соответствующие строковые колонки
     * (через {@code coalesce(col,'')}), а между ними — литеральный текст. Плейсхолдеры,
     * которых нет среди строковых атрибутов сущности, заменяются пустой строкой.
     */
    public static Expression<String> displayExpression(Root<?> root, CriteriaBuilder cb,
                                                       String pattern, Set<String> stringAttrs) {
        if (pattern == null || pattern.isEmpty()) {
            return cb.literal("");
        }
        Matcher m = PLACEHOLDER.matcher(pattern);
        Expression<String> acc = cb.literal("");
        int last = 0;
        boolean any = false;
        while (m.find()) {
            String literal = pattern.substring(last, m.start());
            if (!literal.isEmpty()) {
                acc = any ? cb.concat(acc, literal) : cb.literal(literal);
                any = true;
            }
            String field = m.group(1);
            if (stringAttrs.contains(field)) {
                Expression<String> colExpr = cb.coalesce(root.<String>get(field), "");
                acc = any ? cb.concat(acc, colExpr) : colExpr;
                any = true;
            }
            last = m.end();
        }
        String tail = pattern.substring(last);
        if (!tail.isEmpty()) {
            acc = any ? cb.concat(acc, tail) : cb.literal(tail);
        }
        return acc;
    }

    /** Имена singular-атрибутов сущности типа {@code String} (для display-плейсхолдеров). */
    public static Set<String> stringAttributes(Root<?> root) {
        Set<String> out = new HashSet<>();
        for (SingularAttribute<?, ?> a : root.getModel().getSingularAttributes()) {
            if (a.getJavaType() == String.class) out.add(a.getName());
        }
        return out;
    }

    // =====================================================================
    // Internal helpers
    // =====================================================================

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");

    private static boolean active(Column<?> col, Set<String> attrs) {
        if (col == null) return false;
        return col.requiredAttr() == null || attrs.contains(col.requiredAttr());
    }

    /**
     * Выражение для ORDER BY. Для ссылочных/union-колонок сортируем по дискриминатору
     * {@code type_id} ({@code typeExpr}); если его нет (моно-ссылка) — возвращаем
     * {@code null}, т.е. колонка в сортировке не участвует (UUID сортировать нет смысла).
     */
    private static <T> Expression<?> orderExpr(Column<T> col, Root<T> root, CriteriaBuilder cb) {
        if (col.type() == Type.REFERENCE || col.type() == Type.UNION_REFERENCE) {
            return col.typeExpr() != null ? col.typeExpr().apply(root, cb) : null;
        }
        return col.expr().apply(root, cb);
    }

    private static boolean isCountQuery(CriteriaQuery<?> cq) {
        Class<?> rt = cq.getResultType();
        return rt == Long.class || rt == long.class;
    }

    private static <T> void addOrSearch(List<Predicate> preds, List<Column<T>> columns,
                                        Set<String> attrs, String term,
                                        Root<T> root, CriteriaBuilder cb, List<String> onlyIds) {
        List<Predicate> ors = new ArrayList<>();
        for (Column<T> col : columns) {
            if (!col.searchable() || !active(col, attrs)) continue;
            if (onlyIds != null && !onlyIds.contains(col.id())) continue;
            Predicate p = substringPredicate(col, term, root, cb);
            if (p != null) ors.add(p);
        }
        if (!ors.isEmpty()) preds.add(cb.or(ors.toArray(new Predicate[0])));
    }

    @SuppressWarnings("unchecked")
    private static <T> Predicate substringPredicate(Column<T> col, String sub,
                                                    Root<T> root, CriteriaBuilder cb) {
        switch (col.type()) {
            case STRING, ENUM -> {
                Expression<String> e = (Expression<String>) col.expr().apply(root, cb);
                return likeContains(cb, e, sub);
            }
            case REFERENCE -> {
                if (col.refResolver() == null) return null;
                Collection<?> ids = col.refResolver().apply(sub);
                if (ids == null || ids.isEmpty()) return cb.disjunction(); // ничего не матчится
                return col.expr().apply(root, cb).in(ids);
            }
            default -> {
                // DATE/NUMBER/BOOLEAN не участвуют в свободном текстовом поиске.
                return null;
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Predicate advancedPredicate(Column<T> col, AdvancedFilterParam f,
                                                   Root<T> root, CriteriaBuilder cb) {
        String op = f.op();
        Expression<?> raw = col.expr().apply(root, cb);

        // empty / notEmpty — универсальные.
        if ("empty".equals(op)) {
            if (col.type() == Type.STRING || col.type() == Type.ENUM) {
                Expression<String> e = (Expression<String>) raw;
                return cb.or(cb.isNull(e), cb.equal(cb.trim(e), ""));
            }
            return cb.isNull(raw);
        }
        if ("notEmpty".equals(op)) {
            if (col.type() == Type.STRING || col.type() == Type.ENUM) {
                Expression<String> e = (Expression<String>) raw;
                return cb.and(cb.isNotNull(e), cb.notEqual(cb.trim(e), ""));
            }
            return cb.isNotNull(raw);
        }

        // in / nin — множество значений, разделённых '|'.
        if ("in".equals(op) || "nin".equals(op)) {
            List<String> values = splitValues(f.value());
            if (values.isEmpty()) return null; // пустой фильтр = отсутствует
            boolean isIn = "in".equals(op);
            switch (col.type()) {
                case REFERENCE -> {
                    List<UUID> ids = parseUuids(values);
                    if (ids.isEmpty()) return isIn ? cb.disjunction() : null;
                    Predicate inPred = raw.in(ids);
                    return isIn ? inPred : cb.or(cb.isNull(raw), cb.not(inPred));
                }
                case UNION_REFERENCE -> {
                    // Каждое значение — "<typeId>:<uuid>". Собираем OR из пар
                    // (id = uuid AND type_id = typeId); для nin — отрицание.
                    Expression<?> typeRaw = col.typeExpr() != null
                            ? col.typeExpr().apply(root, cb) : null;
                    List<Predicate> pairs = new ArrayList<>();
                    for (String s : values) {
                        UnionRef ur = parseUnionRef(s);
                        if (ur == null) continue;
                        Predicate idEq = cb.equal(raw, ur.id());
                        Predicate pair = (typeRaw != null)
                                ? cb.and(idEq, cb.equal(typeRaw, ur.typeId()))
                                : idEq;
                        pairs.add(pair);
                    }
                    if (pairs.isEmpty()) return isIn ? cb.disjunction() : null;
                    Predicate anyOf = cb.or(pairs.toArray(new Predicate[0]));
                    return isIn ? anyOf : cb.or(cb.isNull(raw), cb.not(anyOf));
                }
                case NUMBER -> {
                    List<BigDecimal> nums = parseNumbers(values);
                    if (nums.isEmpty()) return isIn ? cb.disjunction() : null;
                    Predicate inPred = raw.in(nums);
                    return isIn ? inPred : cb.or(cb.isNull(raw), cb.not(inPred));
                }
                default -> {
                    Expression<String> e = (Expression<String>) raw;
                    List<String> lc = values.stream().map(SqlRowFilter::lower).collect(Collectors.toList());
                    Predicate inPred = cb.lower(e).in(lc);
                    return isIn ? inPred : cb.or(cb.isNull(e), cb.not(inPred));
                }
            }
        }

        // Скалярные операторы.
        String v = trimOrEmpty(f.value());
        if (v.isEmpty()) return null; // пустой фильтр = отсутствует

        switch (col.type()) {
            case NUMBER -> {
                BigDecimal b = parseNumber(v);
                if (b != null) {
                    Expression<BigDecimal> e = (Expression<BigDecimal>) (Expression<?>) raw;
                    return switch (op) {
                        case "eq"  -> cb.equal(e, b);
                        case "neq" -> cb.or(cb.isNull(e), cb.notEqual(e, b));
                        case "gt"  -> cb.greaterThan(e, b);
                        case "gte" -> cb.greaterThanOrEqualTo(e, b);
                        case "lt"  -> cb.lessThan(e, b);
                        case "lte" -> cb.lessThanOrEqualTo(e, b);
                        default    -> null;
                    };
                }
                // fallback на строковое сравнение ниже для числа не применяем.
                return null;
            }
            case DATE -> {
                LocalDate d = parseDate(v);
                if (d == null) return null;
                // Колонка може бути LocalDate (напр. ExchangeRate.rateDate) або Instant
                // (напр. синтетичний createdAt). Розрізняємо за Java-типом виразу:
                //   • LocalDate → пряме порівняння дат;
                //   • Instant   → семантика «доби в UTC» ([start, next)).
                if (raw.getJavaType() == LocalDate.class) {
                    Expression<LocalDate> e = (Expression<LocalDate>) raw;
                    return switch (op) {
                        case "eq"  -> cb.equal(e, d);
                        case "neq" -> cb.or(cb.isNull(e), cb.notEqual(e, d));
                        case "gt"  -> cb.greaterThan(e, d);
                        case "gte" -> cb.greaterThanOrEqualTo(e, d);
                        case "lt"  -> cb.lessThan(e, d);
                        case "lte" -> cb.lessThanOrEqualTo(e, d);
                        default    -> null;
                    };
                }
                Expression<Instant> e = (Expression<Instant>) raw;
                Instant start = d.atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant next = d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                return switch (op) {
                    case "eq"  -> cb.and(cb.greaterThanOrEqualTo(e, start), cb.lessThan(e, next));
                    case "neq" -> cb.or(cb.lessThan(e, start), cb.greaterThanOrEqualTo(e, next));
                    case "gt"  -> cb.greaterThanOrEqualTo(e, next);
                    case "gte" -> cb.greaterThanOrEqualTo(e, start);
                    case "lt"  -> cb.lessThan(e, start);
                    case "lte" -> cb.lessThan(e, next);
                    default    -> null;
                };
            }
            case BOOLEAN -> {
                boolean b = parseBool(v);
                Expression<Boolean> e = (Expression<Boolean>) raw;
                if ("eq".equals(op))  return cb.equal(e, b);
                if ("neq".equals(op)) return cb.notEqual(e, b);
                return null;
            }
            case REFERENCE -> {
                UUID id = parseUuid(v);
                if ("eq".equals(op))  return id == null ? cb.disjunction() : cb.equal(raw, id);
                if ("neq".equals(op)) return id == null ? cb.conjunction()
                        : cb.or(cb.isNull(raw), cb.notEqual(raw, id));
                return null;
            }
            case UNION_REFERENCE -> {
                // v = "<typeId>:<uuid>" → (id = uuid AND type_id = typeId).
                UnionRef ur = parseUnionRef(v);
                if (ur == null) return "eq".equals(op) ? cb.disjunction() : cb.conjunction();
                Expression<?> typeRaw = col.typeExpr() != null
                        ? col.typeExpr().apply(root, cb) : null;
                Predicate idEq = cb.equal(raw, ur.id());
                Predicate match = (typeRaw != null)
                        ? cb.and(idEq, cb.equal(typeRaw, ur.typeId())) : idEq;
                if ("eq".equals(op))  return match;
                if ("neq".equals(op)) return cb.or(cb.isNull(raw), cb.not(match));
                return null;
            }
            case STRING, ENUM -> { /* ниже */ }
        }

        // STRING / ENUM (и fallback).
        Expression<String> e = (Expression<String>) raw;
        return switch (op) {
            case "eq"         -> cb.equal(e, v);
            case "neq"        -> cb.or(cb.isNull(e), cb.notEqual(e, v));
            case "gt"         -> cb.greaterThan(e, v);
            case "gte"        -> cb.greaterThanOrEqualTo(e, v);
            case "lt"         -> cb.lessThan(e, v);
            case "lte"        -> cb.lessThanOrEqualTo(e, v);
            case "contains"   -> likeContains(cb, e, v);
            case "ncontains"  -> cb.or(cb.isNull(e), cb.not(likeContains(cb, e, v)));
            case "startsWith" -> likeStarts(cb, e, v);
            case "endsWith"   -> likeEnds(cb, e, v);
            default           -> null;
        };
    }

    // -------- LIKE helpers (case-insensitive, wildcard-escaped) --------

    private static final char ESC = '\\';

    private static Predicate likeContains(CriteriaBuilder cb, Expression<String> e, String term) {
        return cb.like(cb.lower(e), "%" + escapeLike(lower(term)) + "%", ESC);
    }

    private static Predicate likeStarts(CriteriaBuilder cb, Expression<String> e, String term) {
        return cb.like(cb.lower(e), escapeLike(lower(term)) + "%", ESC);
    }

    private static Predicate likeEnds(CriteriaBuilder cb, Expression<String> e, String term) {
        return cb.like(cb.lower(e), "%" + escapeLike(lower(term)), ESC);
    }

    /** Экранирует спецсимволы LIKE ({@code \ % _}), чтобы подстрока матчилась буквально. */
    private static String escapeLike(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ESC || c == '%' || c == '_') sb.append(ESC);
            sb.append(c);
        }
        return sb.toString();
    }

    // -------- Parsers --------

    private static String trimOrEmpty(String s) { return s == null ? "" : s.trim(); }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }

    private static List<String> splitValues(String raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        return Arrays.stream(raw.split("\\|"))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static List<UUID> parseUuids(List<String> values) {
        List<UUID> out = new ArrayList<>(values.size());
        for (String v : values) {
            UUID id = parseUuid(v);
            if (id != null) out.add(id);
        }
        return out;
    }

    private static UUID parseUuid(String v) {
        try { return UUID.fromString(v.trim()); }
        catch (IllegalArgumentException e) { return null; }
    }

    /** Распарсенное union-ссылочное значение фильтра. */
    private record UnionRef(long typeId, UUID id) {}

    /**
     * Парсит значение union-ссылки {@code "<typeId>:<uuid>"} (формат с фронта).
     * Возвращает {@code null}, если формат невалидный.
     */
    private static UnionRef parseUnionRef(String v) {
        if (v == null) return null;
        String s = v.trim();
        int i = s.indexOf(':');
        if (i <= 0) return null;
        try {
            long typeId = Long.parseLong(s.substring(0, i).trim());
            UUID id = UUID.fromString(s.substring(i + 1).trim());
            return new UnionRef(typeId, id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<BigDecimal> parseNumbers(List<String> values) {
        List<BigDecimal> out = new ArrayList<>(values.size());
        for (String v : values) {
            BigDecimal b = parseNumber(v);
            if (b != null) out.add(b);
        }
        return out;
    }

    private static BigDecimal parseNumber(String s) {
        if (s == null) return null;
        String t = s.trim().replace(',', '.');
        if (t.isEmpty()) return null;
        try { return new BigDecimal(t); }
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
            } catch (RuntimeException e) { return null; }
        }
        // ISO local date
        try { return LocalDate.parse(t); }
        catch (RuntimeException ignored) {}
        // ISO instant / datetime — берём только date-часть
        try { return LocalDate.parse(t.substring(0, Math.min(10, t.length()))); }
        catch (RuntimeException ignored) {}
        try { return Instant.parse(t).atZone(ZoneOffset.UTC).toLocalDate(); }
        catch (RuntimeException ignored) {}
        return null;
    }

    private static boolean parseBool(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return "true".equals(t) || "1".equals(t) || "yes".equals(t)
                || "yes".equals(t) || "y".equals(t);
    }

    // Безопасный хелпер для построения ExprFn с навигацией в composite-ref.
    @SuppressWarnings("unused")
    private static <T> BiFunction<Root<T>, CriteriaBuilder, Path<Object>> refIdPath(String field) {
        return (r, cb) -> r.get(field).get("targetIdRaw");
    }
}