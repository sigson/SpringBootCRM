package app.springbootcrm.metadata;

import app.springbootcrm.common.InMemoryRowFilter;

import app.springbootcrm.common.AdvancedFilterParam;
import app.springbootcrm.common.PageResponse;
import app.springbootcrm.common.SqlRowFilter;
import app.springbootcrm.metadata.TypeRegistry.TypeDescriptor;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.AggregateReference;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Универсальный резолвер ссылок на агрегаты (typeId, id) → читаемая строка.
 *
 * <p>Диспетчеризуется через {@link ReferenceProvider}-bean'ы, собранные в карту
 * {@code typeId → provider}. Никаких if/else-каскадов по typeId: добавление нового
 * типа = добавление одного {@code @Component implements ReferenceProvider<Type>}.
 *
 * <p>Чтение идёт через стандартный {@code AggregateRepository}, поэтому row-level
 * фильтры (например, Activity) применяются автоматически через
 * {@link domain.core.persistence.AccessFilterActivator}.
 */
@Service
public class ReferenceResolver {

    private final TypeRegistry registry;
    private final Map<Long, ReferenceProvider<?>> byTypeId;

    @SuppressWarnings("unchecked")
    public ReferenceResolver(TypeRegistry registry, List<ReferenceProvider<?>> providers) {
        this.registry = registry;
        Map<Long, ReferenceProvider<?>> m = new HashMap<>();
        for (ReferenceProvider<?> p : providers) {
            ReferenceProvider<?> existing = m.get(p.typeId());
            if (existing == null) {
                m.put(p.typeId(), p);
                continue;
            }
            // Коллизия typeId. Рукописный (override) провайдер вытесняет авто-сгенерированный
            // (см. ReferenceProvider#generated()) — так задаётся нестандартное поведение типа.
            if (existing.generated() && !p.generated()) {
                m.put(p.typeId(), p);             // p — рукописный, побеждает
            } else if (!existing.generated() && p.generated()) {
                // existing — рукописный, оставляем
            } else {
                // оба рукописных или оба сгенерированных — это настоящая ошибка
                throw new IllegalStateException(
                        "Two ReferenceProviders claim typeId=" + p.typeId() +
                                ": " + existing.getClass().getName() + " and " + p.getClass().getName());
            }
        }
        this.byTypeId = Map.copyOf(m);
    }

    /**
     * Batch-resolve ссылок. Для каждой несуществующей — placeholder + accessible=false.
     *
     * <p>Отказы доступа изолируются <b>по каждому ключу</b>: резолв идёт через
     * {@code provider.findById()} (а значит через {@link domain.core.persistence.AccessFilterActivator}
     * / {@link domain.core.persistence.PostLoadAccessCheckListener}), и если прав на репозиторий
     * или экземпляр нет — {@link AccessDeniedException} ловится локально и превращается в
     * {@code accessible=false}-плейсхолдер. Так батч всегда успешен (фронт кэширует «нет доступа»
     * и не зацикливает запрос), а доступные ссылки в том же батче резолвятся нормально.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<ResolvedRef> resolve(List<RefKey> keys) {
        List<ResolvedRef> out = new ArrayList<>(keys.size());
        for (RefKey k : keys) {
            TypeDescriptor td = registry.byTypeId(k.typeId());
            ReferenceProvider provider = byTypeId.get(k.typeId());
            if (td == null || provider == null) {
                out.add(ResolvedRef.unknown(k));
                continue;
            }
            try {
                Optional<? extends AbstractAggregate<?>> opt = provider.findById(k.id());
                if (opt.isEmpty()) {
                    out.add(ResolvedRef.inaccessible(k, td));
                    continue;
                }
                AbstractAggregate<?> e = opt.get();
                String display = provider.display(e, td);
                String code = provider.code(e);
                String name = provider.name(e);
                out.add(new ResolvedRef(k.typeId(), k.id(), display, code, name, true, td.slug(),
                        auditIdRaw(e.getCreatedBy()), auditIdRaw(e.getUpdatedBy())));
            } catch (AccessDeniedException denied) {
                // Нет доступа к репозиторию/экземпляру — не валим весь батч, отдаём
                // per-key плейсхолдер «недоступно».
                out.add(ResolvedRef.inaccessible(k, td));
            }
        }
        return out;
    }

    /** Список всех записей типа (для picker'а), опционально фильтруемых query'ем. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<ResolvedRef> list(long typeId, String query) {
        TypeDescriptor td = registry.byTypeId(typeId);
        ReferenceProvider provider = byTypeId.get(typeId);
        if (td == null || provider == null) return List.of();

        List<? extends AbstractAggregate<?>> rows = provider.list(query);
        List<ResolvedRef> out = new ArrayList<>(rows.size());
        for (AbstractAggregate<?> e : rows) {
            String display = provider.display(e, td);
            String code = provider.code(e);
            String name = provider.name(e);
            String idRaw = String.valueOf(e.getId());
            out.add(new ResolvedRef(typeId, idRaw, display, code, name, true, td.slug(),
                    auditIdRaw(e.getCreatedBy()), auditIdRaw(e.getUpdatedBy())));
        }
        return out;
    }

    /**
     * Постраничный список ссылок (для унифицированной chunked-загрузки в
     * списках/пикерах всех типов-справочников).
     *
     * <p><b>SQL-фильтрация.</b> Если провайдер отдаёт {@link ReferenceProvider#filterRepository()}
     * (все справочники SpringBootCRM — да), поиск/quick-фильтры/advanced-операторы/сортировка
     * и пагинация транслируются в единую {@link Specification} и выполняются СУБД через
     * {@code findAll(spec, Pageable)}. В память грузится только запрошенная страница, а
     * {@code total} считается отдельным {@code count(...)}-запросом. Row-level security
     * сохраняется: {@code findAll} матчится паттерном {@code find*} в
     * {@link domain.core.persistence.AccessFilterActivator}, поэтому Hibernate-фильтры и
     * admin-bypass применяются и к странице, и к count'у.
     *
     * <p><b>Fallback.</b> Если провайдер не отдал репозиторий ({@code filterRepository()==null}),
     * остаётся in-memory-фильтрация через {@link app.springbootcrm.common.InMemoryRowFilter}.
     *
     * <p>Колонки соответствуют фронтовому контракту: {@code code}, {@code name},
     * вычисляемая {@code display} (concat по {@code displayPattern}), а также скрытые
     * audit-колонки {@code createdBy}/{@code updatedBy} (UUID автора/корректора для
     * фильтров «Автор»/«Корректировка»). Колонки, чей backing-атрибут отсутствует у
     * конкретного типа, автоматически игнорируются.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public PageResponse<ResolvedRef> listPaged(
            long typeId, String query, String search,
            Map<String, String> columnFilters,
            List<AdvancedFilterParam> advanced,
            String sortBy, String sortDir,
            int page, int size) {
        return listPaged(typeId, query, search, columnFilters, advanced,
                sortBy, sortDir, page, size, true);
    }

    /**
     * Варіант з керуванням обчисленням {@code total}. {@code withCount=false} пропускає
     * {@code count(...)} і повертає {@code total = -1} — для гортання сторінок, коли клієнт
     * уже знає загальну кількість для поточного фільтра (прибирає повне сканування-count
     * з кожної наступної сторінки пікера/списку).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public PageResponse<ResolvedRef> listPaged(
            long typeId, String query, String search,
            Map<String, String> columnFilters,
            List<AdvancedFilterParam> advanced,
            String sortBy, String sortDir,
            int page, int size, boolean withCount) {

        TypeDescriptor td = registry.byTypeId(typeId);
        ReferenceProvider provider = byTypeId.get(typeId);
        if (td == null || provider == null) {
            return PageResponse.of(List.of(), Math.max(0, page), clampSize(size), 0);
        }

        JpaSpecificationExecutor repo = provider.filterRepository();
        if (repo != null) {
            return pagedViaSql(repo, provider, td, typeId, query, search,
                    columnFilters, advanced, sortBy, sortDir, page, size, withCount);
        }

        // ---- Fallback: in-memory (для провайдеров без собственного репо) ----
        List<ResolvedRef> all = list(typeId, query);
        var columns = List.<app.springbootcrm.common.InMemoryRowFilter.Column<ResolvedRef>>of(
                app.springbootcrm.common.InMemoryRowFilter.Column.string("code", ResolvedRef::code),
                app.springbootcrm.common.InMemoryRowFilter.Column.string("name", ResolvedRef::name),
                app.springbootcrm.common.InMemoryRowFilter.Column.string("display", ResolvedRef::display),
                app.springbootcrm.common.InMemoryRowFilter.Column.ref("createdBy",
                        ResolvedRef::createdBy, ResolvedRef::createdBy),
                app.springbootcrm.common.InMemoryRowFilter.Column.ref("updatedBy",
                        ResolvedRef::updatedBy, ResolvedRef::updatedBy)
        );
        return app.springbootcrm.common.InMemoryRowFilter.apply(
                all, columns, search, columnFilters, advanced,
                sortBy, sortDir, page, size);
    }

    /**
     * SQL-ветка {@link #listPaged}: строит обобщённые reference-колонки, формирует
     * {@link Specification} и выполняет постраничный запрос через
     * {@link JpaSpecificationExecutor#findAll(Specification, org.springframework.data.domain.Pageable)}.
     */
    private <T extends AbstractAggregate<?>> PageResponse<ResolvedRef> pagedViaSql(
            JpaSpecificationExecutor<T> repo, ReferenceProvider<T> provider, TypeDescriptor td,
            long typeId, String query, String search,
            Map<String, String> columnFilters, List<AdvancedFilterParam> advanced,
            String sortBy, String sortDir, int page, int size, boolean withCount) {

        List<SqlRowFilter.Column<T>> columns = List.of(
                SqlRowFilter.Column.string("code", "code"),
                SqlRowFilter.Column.string("name", "name"),
                // display — вычисляемый concat по displayPattern (self-guarded).
                SqlRowFilter.Column.stringExpr("display",
                        (r, cb) -> SqlRowFilter.displayExpression(
                                r, cb, td.displayPattern(), SqlRowFilter.stringAttributes(r)),
                        null),
                // audit-колонки: канонический UUID; участвуют только в advanced
                // (eq/neq/in/nin/empty/notEmpty) — текстового поиска по UUID нет.
                SqlRowFilter.Column.reference("createdBy",
                        (r, cb) -> r.get("createdBy").get("targetIdRaw"), "createdBy", null),
                SqlRowFilter.Column.reference("updatedBy",
                        (r, cb) -> r.get("updatedBy").get("targetIdRaw"), "updatedBy", null)
        );

        // Многоколоночная сортировка (comma-separated sortBy/sortDir). По умолчанию —
        // стабильный порядок по коду (детерминированная пагинация).
        List<SqlRowFilter.SortSpec> sorts = SqlRowFilter.parseSorts(sortBy, sortDir);
        if (sorts.isEmpty()) sorts = List.of(new SqlRowFilter.SortSpec("code", "asc"));

        Specification<T> spec = SqlRowFilter.build(
                columns, query, List.of("code", "name"),
                search, columnFilters, advanced, sorts);

        int p = Math.max(0, page);
        int s = clampSize(size);

        List<T> rows;
        long total;
        // Усі репозиторії SpringBootCRM — AggregateRepository, тож no-count-шлях доступний.
        // Якщо колись провайдер віддасть «голий» JpaSpecificationExecutor — безпечно
        // відкочуємось до count-варіанту.
        if (!withCount && repo instanceof AggregateRepository<?, ?> aggRepo) {
            rows = ((AggregateRepository<T, ?>) aggRepo).findPageContent(spec, PageRequest.of(p, s));
            total = -1;   // sentinel: не рахували; фронтенд зберігає попередній total
        } else {
            Page<T> result = repo.findAll(spec, PageRequest.of(p, s));
            rows = result.getContent();
            total = result.getTotalElements();
        }

        List<ResolvedRef> content = new ArrayList<>(rows.size());
        for (T e : rows) {
            String display = provider.display(e, td);
            String code = provider.code(e);
            String name = provider.name(e);
            String idRaw = String.valueOf(e.getId());
            content.add(new ResolvedRef(typeId, idRaw, display, code, name, true, td.slug(),
                    auditIdRaw(e.getCreatedBy()), auditIdRaw(e.getUpdatedBy())));
        }
        return PageResponse.of(content, p, s, total);
    }

    /** Ограничивает размер страницы в [1..500] (защита от чрезмерных/некорректных значений). */
    private static int clampSize(int size) {
        if (size < 1) return 1;
        return Math.min(size, 500);
    }

    /**
     * Извлекает raw-UUID (как String) из audit-ссылки {@link AggregateReference}.
     * Для системного principal'а ({@code "__SYSTEM__"}) возвращает его как есть —
     * UI покажет placeholder. {@code null}-safe.
     */
    @SuppressWarnings("rawtypes")
    private static String auditIdRaw(AggregateReference ref) {
        if (ref == null) return null;
        return ref.targetIdRaw();
    }

    // -------- Public records --------

    public record RefKey(long typeId, String id) {}

    /**
     * Resolved-record: помимо {@code display} отдаёт {@code code} и {@code name}
     * отдельными полями. Frontend использует их для:
     *   - autocomplete'а (поиск по code, name независимо);
     *   - отображения двух колонок «Код + Наименование» в дефолтном picker'е;
     *   - визуализации selected-chip'а в формате «code — name».
     */
    public record ResolvedRef(long typeId, String id, String display,
                              String code, String name,
                              boolean accessible, String slug,
                              /* audit-ссылки (UUID автора/корректора) для скрытых
                                 фильтруемых колонок «Автор»/«Корректировка». */
                              String createdBy, String updatedBy) {
        static ResolvedRef unknown(RefKey k) {
            return new ResolvedRef(k.typeId(), k.id(), "?:" + k.typeId() + ":" + k.id(),
                    null, null, false, null, null, null);
        }
        static ResolvedRef inaccessible(RefKey k, TypeDescriptor td) {
            return new ResolvedRef(k.typeId(), k.id(), "(unavailable)",
                    null, null, false, td.slug(), null, null);
        }
    }
}
