package app.springbootcrm.metadata;

import app.springbootcrm.common.AdvancedFilterParam;
import app.springbootcrm.common.PageResponse;
import app.springbootcrm.common.SqlRowFilter;
import app.springbootcrm.metadata.TypeRegistry.FieldOut;
import app.springbootcrm.metadata.TypeRegistry.TypeDescriptor;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.AggregateReference;
import domain.core.persistence.AggregateRepository;
import domain.core.persistence.RepositoryRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h2>Універсальне серверне джерело рядків для списку будь-якого обʼєкта БД.</h2>
 *
 * <p>Справжня SQL-пагінація: фільтри/пошук/багатоколоночне сортування й сторінкування
 * транслюються в одну {@link Specification} і виконуються СУБД через
 * {@code findAll(spec, Pageable)}; у памʼять вантажиться лише потрібна сторінка,
 * {@code total} — окремим {@code count(...)}.
 *
 * <p><b>Один механізм на всі типи.</b> Колонки будуються з тих самих метаданих
 * ({@link TypeDescriptor#fields()}), що їх споживає фронтовий
 * {@code buildColumnsFromMetadata}, тож контракт id-колонок (а отже {@code cf_*},
 * {@code af_*_*}, {@code sortBy}) збігається один-в-один — і quick-фільтри, і
 * вільний пошук, і advanced-оператори, і багатоколоночне сортування лишаються
 * робочими на боці СУБД.
 *
 * <p><b>Проєкція рядка.</b> Для кожного UI-поля у відповідь кладеться
 * {@code row[field.name]} (для ссилок — {@code targetIdRaw}; для union додатково
 * {@code field.nameTypeId}). Це рівно ті ключі, які читає грід ({@code row[f.name]}).
 *
 * <p><b>Row-level security збережено:</b> {@code findAll} матчиться паттерном
 * {@code find*} у {@code AccessFilterActivator}, тож Hibernate-фільтри та
 * admin-bypass діють і на сторінку, і на count.
 */
@Service
public class ObjectRowQueryService {

    private final TypeRegistry registry;
    private final RepositoryRegistry repositories;

    /** Кеш рефлексії: (клас сутності + ім'я поля) → доступне Field. */
    private final Map<String, Field> fieldCache = new ConcurrentHashMap<>();

    public ObjectRowQueryService(TypeRegistry registry, RepositoryRegistry repositories) {
        this.registry = registry;
        this.repositories = repositories;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public PageResponse<Map<String, Object>> listRows(
            String slug, String search,
            Map<String, String> columnFilters,
            List<AdvancedFilterParam> advanced,
            String sortBy, String sortDir,
            int page, int size) {
        return listRows(slug, search, columnFilters, advanced, sortBy, sortDir, page, size, true);
    }

    /**
     * Варіант з керуванням обчисленням {@code total}.
     *
     * @param withCount {@code true} — порахувати загальну кількість (окремий {@code count(...)});
     *                  {@code false} — НЕ рахувати (повертається {@code total = -1}), коли клієнт
     *                  уже знає total для цього фільтра й лише гортає сторінки. Це прибирає
     *                  повне сканування-{@code count} з кожної наступної сторінки.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public PageResponse<Map<String, Object>> listRows(
            String slug, String search,
            Map<String, String> columnFilters,
            List<AdvancedFilterParam> advanced,
            String sortBy, String sortDir,
            int page, int size, boolean withCount) {
        return listRows(slug, search, columnFilters, advanced, sortBy, sortDir,
                page, size, withCount, null, null);
    }

    /**
     * Повний варіант із <b>keyset</b>-якорем для глибокої пагінації.
     *
     * @param afterValue значення сорт-колонки останнього рядка ПОПЕРЕДНЬОЇ сторінки
     *                   (для keyset-seek), або {@code null}
     * @param afterId    {@code id} останнього рядка попередньої сторінки (UUID-рядок), або {@code null}
     *
     * <p>Якщо обидва {@code after*} задані І сорт keyset-придатний (одноколонковий по
     * STRING-колонці code/name + неявний id-tiebreaker) — застосовується seek-предикат
     * {@code (sortCol, id) > (afterValue, afterId)} зі сталою вартістю незалежно від
     * глибини. Інакше — offset-пагінація (прискорена індексами V5).
     */
    @Transactional(readOnly = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public PageResponse<Map<String, Object>> listRows(
            String slug, String search,
            Map<String, String> columnFilters,
            List<AdvancedFilterParam> advanced,
            String sortBy, String sortDir,
            int page, int size, boolean withCount,
            String afterValue, String afterId) {

        int p = Math.max(0, page);
        int s = clampSize(size);

        TypeDescriptor td = registry.bySlug(slug);
        if (td == null) return PageResponse.of(List.of(), p, s, 0);

        AggregateRepository<?, ?> repo = repositories.byTypeId(td.typeId());
        if (repo == null) {
            // Тип без репозиторію (напр. «вільний контролер») — generic-список не застосовний.
            return PageResponse.of(List.of(), p, s, 0);
        }
        return paged((AggregateRepository) repo, td,
                search, columnFilters, advanced, sortBy, sortDir, p, s, withCount,
                afterValue, afterId);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends AbstractAggregate<?>> PageResponse<Map<String, Object>> paged(
            AggregateRepository<T, ?> repo, TypeDescriptor td,
            String search, Map<String, String> columnFilters,
            List<AdvancedFilterParam> advanced, String sortBy, String sortDir,
            int p, int s, boolean withCount, String afterValue, String afterId) {

        List<SqlRowFilter.Column<T>> columns = buildColumns(td);

        List<SqlRowFilter.SortSpec> sorts = SqlRowFilter.parseSorts(sortBy, sortDir);
        if (sorts.isEmpty() && hasField(td, "code")) {
            sorts = List.of(new SqlRowFilter.SortSpec("code", "asc"));   // дефолт — стабільно за кодом
        }

        List<T> rows;
        long total;

        // --- Keyset (seek) шлях: одноколонковий сорт по STRING-колонці + валідний якір ---
        // Стала вартість незалежно від глибини. Не застосовуємо, коли потрібен count
        // (перша сторінка запиту) — там і так offset=0, дешево.
        if (!withCount && afterId != null && isKeysetEligible(td, sorts)) {
            SqlRowFilter.SortSpec only = sorts.get(0);
            Specification<T> seekSpec = SqlRowFilter.keysetSpec(
                    columns, search, columnFilters, advanced, sorts,
                    only.columnId(), only.dir(), afterValue, afterId);
            // offset=0, limit=s — СУБД одразу стрибає по індексу (sortCol,id) і бере s рядків.
            rows = repo.findPageContent(seekSpec, PageRequest.of(0, s));
            total = -1;   // total уже відомий клієнту
            List<Map<String, Object>> seekContent = new ArrayList<>(rows.size());
            for (T e : rows) seekContent.add(projectRow(e, td));
            return PageResponse.of(seekContent, p, s, total);
        }

        // --- Offset шлях (перша сторінка / random-landing без якоря / неpridatний сорт) ---
        Specification<T> spec = SqlRowFilter.build(
                columns, null, List.of(), search, columnFilters, advanced, sorts);

        if (withCount) {
            // Перша сторінка запиту: рахуємо total (один count(...)) і кешуємо на клієнті.
            Page<T> result = repo.findAll(spec, PageRequest.of(p, s));
            rows = result.getContent();
            total = result.getTotalElements();
        } else {
            // Наступні сторінки того ж фільтра: total уже відомий клієнту — count не потрібен.
            rows = repo.findPageContent(spec, PageRequest.of(p, s));
            total = -1;   // sentinel: «не рахували»; фронтенд зберігає попередній total
        }

        List<Map<String, Object>> content = new ArrayList<>(rows.size());
        for (T e : rows) content.add(projectRow(e, td));
        return PageResponse.of(content, p, s, total);
    }

    /**
     * Keyset-придатність: РІВНО один сорт-ключ, і це STRING-колонка ({@code code}/{@code name}),
     * для якої є складений індекс {@code (col, id)} (міграція V5). Багатоколонковий сорт,
     * або сорт по ref/number/date/boolean — НЕ keyset (немає універсального покривного індексу),
     * лишається offset-варіант.
     */
    private boolean isKeysetEligible(TypeDescriptor td, List<SqlRowFilter.SortSpec> sorts) {
        if (sorts == null || sorts.size() != 1) return false;
        String colId = sorts.get(0).columnId();
        if (!"code".equals(colId) && !"name".equals(colId)) return false;
        // Колонка має існувати й бути STRING-подібною (TEXT/CODE) у метаданих типу.
        for (FieldOut f : td.fields()) {
            if (f.name().equals(colId)) {
                return switch (f.kind()) {
                    case PASSWORD, NUMBER, BOOLEAN, DATE, DATETIME, REF -> false;
                    default -> true;   // TEXT/CODE/TEXTAREA/EMAIL
                };
            }
        }
        return false;
    }

    /** Будує SqlRowFilter-колонки з UI-полів типу (id колонки = ім'я поля). */
    private <T> List<SqlRowFilter.Column<T>> buildColumns(TypeDescriptor td) {
        List<SqlRowFilter.Column<T>> cols = new ArrayList<>(td.fields().size());
        for (FieldOut f : td.fields()) {
            String n = f.name();
            switch (f.kind()) {
                case PASSWORD -> { /* пароль ніколи не повертаємо в списку */ }
                case NUMBER -> cols.add(SqlRowFilter.Column.number(n, n));
                case BOOLEAN -> cols.add(SqlRowFilter.Column.bool(n, n));
                case DATE, DATETIME ->
                        // expr = root.get(n); тип (Instant/LocalDate) визначається у SqlRowFilter.
                        cols.add(SqlRowFilter.Column.instant(n, n));
                case REF -> {
                    boolean union = f.refTypeIds() != null && f.refTypeIds().size() > 1;
                    if (union) {
                        cols.add(SqlRowFilter.Column.unionReference(n,
                                (r, cb) -> r.get(n).get("targetIdRaw"),
                                (r, cb) -> r.get(n).get("targetTypeId"), n));
                    } else {
                        cols.add(SqlRowFilter.Column.reference(n,
                                (r, cb) -> r.get(n).get("targetIdRaw"), n, null));
                    }
                }
                default -> cols.add(SqlRowFilter.Column.string(n, n)); // TEXT/CODE/TEXTAREA/EMAIL
            }
        }
        return cols;
    }

    /** Проєкція сутності у map за іменами UI-полів (+ id). */
    private Map<String, Object> projectRow(AbstractAggregate<?> e, TypeDescriptor td) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.getId());
        for (FieldOut f : td.fields()) {
            if (f.kind() == TypeRegistry.FieldKind.PASSWORD) continue;
            // Реквізит, схований і з таблиці, і з форми, generic-UI не рендерить ніде,
            // тож у рядку списку він — чистий баласт. Практично це JSON-реквізити,
            // які редагує спеціалізований конструктор (дерево інтерфейсу, схема
            // компоновки звіту) і які читаються поштучно через apiBase типу; без
            // цього відсіювання кожен рядок списку тягнув би за собою їхній повний текст.
            if (f.hiddenInTable() && f.hiddenInForm()) continue;
            Object v = readField(e, f.name());
            if (v instanceof AggregateReference<?, ?> ref) {
                row.put(f.name(), ref.targetIdRaw());
                if (f.refTypeIds() != null && f.refTypeIds().size() > 1) {
                    row.put(f.name() + "TypeId", ref.targetTypeId());
                }
            } else {
                row.put(f.name(), v);   // Instant/LocalDate/BigDecimal/Boolean/String → серіалізує Jackson
            }
        }
        return row;
    }

    // -------- helpers --------

    private boolean hasField(TypeDescriptor td, String name) {
        for (FieldOut f : td.fields()) if (f.name().equals(name)) return true;
        return false;
    }

    private Object readField(Object target, String name) {
        if (target == null) return null;
        Field fld = resolveField(target.getClass(), name);
        if (fld == null) return null;
        try {
            return fld.get(target);
        } catch (IllegalAccessException ex) {
            return null;
        }
    }

    /** Знаходить поле за іменем уверх по ієрархії класів (з кешем). */
    private Field resolveField(Class<?> cls, String name) {
        String key = cls.getName() + "#" + name;
        Field cached = fieldCache.get(key);
        if (cached != null) return cached;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                fieldCache.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                // піднімаємось вище
            }
        }
        return null;
    }

    private static int clampSize(int size) {
        if (size < 1) return 1;
        return Math.min(size, 500);
    }
}
