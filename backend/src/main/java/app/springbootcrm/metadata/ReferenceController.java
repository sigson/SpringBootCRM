package app.springbootcrm.metadata;

import app.springbootcrm.common.AdvancedFilterParam;
import app.springbootcrm.common.PageResponse;

import app.springbootcrm.metadata.ReferenceResolver.RefKey;
import app.springbootcrm.metadata.ReferenceResolver.ResolvedRef;
import app.springbootcrm.metadata.TypeRegistry.TypeDescriptor;
import app.springbootcrm.reference.CodeAllocationService;
import app.springbootcrm.reference.Reference;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.persistence.AggregateRepository;
import domain.core.persistence.RepositoryRegistry;
import domain.core.web.ValidationFailedException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

/**
 * REST API работы со ссылками и метаданными справочников.
 *
 * <p>{@code GET /api/references/{slug}/next-code} запрашивает следующий код для
 * нового элемента справочника; фронтенд вызывает его при открытии формы создания.
 * Код инкрементирует счётчик даже если запись в итоге не создастся (fail-safe
 * против race conditions).
 *
 * <p><b>Никаких ручных реестров.</b> Раньше контроллер инжектил по репозиторию
 * на каждый тип-справочник и держал два {@code switch}-каскада ({@code resolveClass},
 * {@code uniquenessFor}), которые приходилось править при добавлении нового справочника.
 * Теперь и класс агрегата, и репозиторий резолвятся обобщённо из системных реестров:
 * {@link MetadataSnapshot} (typeId -> класс) и {@link RepositoryRegistry}
 * (typeId -> {@code AggregateRepository}). Добавление справочника не требует никаких
 * правок здесь.
 */
@RestController
@RequestMapping("/api/references")
public class ReferenceController {

    private final ReferenceResolver resolver;
    private final TypeRegistry registry;
    private final CodeAllocationService codeAllocator;
    private final MetadataSnapshotProvider snapshots;
    private final RepositoryRegistry repositories;
    private final ObjectRowQueryService rowQuery;

    public ReferenceController(ReferenceResolver resolver, TypeRegistry registry,
                               CodeAllocationService codeAllocator,
                               MetadataSnapshotProvider snapshots,
                               RepositoryRegistry repositories,
                               ObjectRowQueryService rowQuery) {
        this.resolver = resolver;
        this.registry = registry;
        this.codeAllocator = codeAllocator;
        this.snapshots = snapshots;
        this.repositories = repositories;
        this.rowQuery = rowQuery;
    }

    /** Batch-resolve UUID-ссылок в display-strings. */
    @PostMapping("/resolve")
    public List<ResolvedRef> resolve(@RequestBody List<RefKey> keys) {
        if (keys == null || keys.isEmpty()) return List.of();
        if (keys.size() > 1000) {
            throw ValidationFailedException.general(
                    "At most 1000 references can be resolved per request");
        }
        return resolver.resolve(keys);
    }

    /** Список всех ссылок одного типа (для построения picker'а). */
    @GetMapping("/{slug}")
    public List<ResolvedRef> list(@PathVariable String slug,
                                  @RequestParam(value = "q", required = false) String query) {
        TypeDescriptor td = registry.bySlug(slug);
        if (td == null) {
            throw new NoSuchElementException("Unknown type: " + slug);
        }
        return resolver.list(td.typeId(), query);
    }

    /**
     * Постраничный список ссылок — унифицированный chunked-API для списков/пикеров
     * всех типов-справочников. Поддерживает:
     * <ul>
     *   <li>{@code search} — глобальный подстрочный поиск по code/name/display;</li>
     *   <li>{@code cf_<columnId>=<substring>} — quick-фильтры по колонкам;</li>
     *   <li>{@code af_<columnId>_<op>=<value>} — advanced-фильтры с операторами
     *       (значения для {@code in}/{@code nin} — pipe-separated);</li>
     *   <li>{@code sortBy}/{@code sortDir} — серверная сортировка.</li>
     * </ul>
     */
    @GetMapping("/{slug}/page")
    public app.springbootcrm.common.PageResponse<ResolvedRef> listPaged(
            @PathVariable String slug,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortDir", required = false) String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "true") boolean count,
            @org.springframework.web.bind.annotation.RequestParam Map<String, String> allParams) {
        TypeDescriptor td = registry.bySlug(slug);
        if (td == null) {
            throw new NoSuchElementException("Unknown type: " + slug);
        }
        Map<String, String> columnFilters = new java.util.HashMap<>();
        java.util.List<app.springbootcrm.common.AdvancedFilterParam> advanced = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : allParams.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("cf_")) {
                columnFilters.put(k.substring(3), e.getValue());
            } else if (k.startsWith("af_")) {
                String rest = k.substring(3);
                int sep = rest.lastIndexOf('_');
                if (sep <= 0) continue;
                advanced.add(new app.springbootcrm.common.AdvancedFilterParam(
                        rest.substring(0, sep), rest.substring(sep + 1), e.getValue()));
            }
        }
        return resolver.listPaged(td.typeId(), query, search, columnFilters, advanced,
                sortBy, sortDir, page, size, count);
    }

    /**
     * Постранічний список <b>повних рядків</b> обʼєкта БД для generic-списку
     * ({@link ObjectRowQueryService}). На відміну від {@code /page} (повертає
     * {@code ResolvedRef} для пікерів), повертає проєкцію по всіх UI-полях типу —
     * саме її споживає {@code ObjectList} замість завантаження всієї таблиці.
     *
     * <p>Контракт query-параметрів збігається з {@code /page}: {@code search},
     * {@code cf_<col>}, {@code af_<col>_<op>}, {@code sortBy}/{@code sortDir}
     * (підтримує багатоколоночне comma-separated).
     */
    @GetMapping("/{slug}/rows")
    public app.springbootcrm.common.PageResponse<Map<String, Object>> listRows(
            @PathVariable String slug,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortDir", required = false) String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "true") boolean count,
            // Keyset-якір: значення сорт-колонки та id останнього рядка попередньої
            // сторінки. Якщо задано (і сорт keyset-придатний) — seek замість offset.
            @RequestParam(value = "afterValue", required = false) String afterValue,
            @RequestParam(value = "afterId", required = false) String afterId,
            @org.springframework.web.bind.annotation.RequestParam Map<String, String> allParams) {
        Map<String, String> columnFilters = new java.util.HashMap<>();
        java.util.List<app.springbootcrm.common.AdvancedFilterParam> advanced = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : allParams.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("cf_")) {
                columnFilters.put(k.substring(3), e.getValue());
            } else if (k.startsWith("af_")) {
                String rest = k.substring(3);
                int sep = rest.lastIndexOf('_');
                if (sep <= 0) continue;
                advanced.add(new app.springbootcrm.common.AdvancedFilterParam(
                        rest.substring(0, sep), rest.substring(sep + 1), e.getValue()));
            }
        }
        return rowQuery.listRows(slug, search, columnFilters, advanced, sortBy, sortDir,
                page, size, count, afterValue, afterId);
    }

    /**
     * Предлагает следующий код для нового элемента справочника. Фронтенд подставляет
     * его в форму создания; пользователь может принять или отредактировать.
     *
     * <p>Контракт: счётчик гарантированно инкрементируется — даже если запись в итоге
     * не создастся.
     */
    @GetMapping("/{slug}/next-code")
    public Map<String, String> nextCode(@PathVariable String slug) {
        TypeDescriptor td = registry.bySlug(slug);
        if (td == null) {
            throw new NoSuchElementException("Unknown type: " + slug);
        }
        long typeId = td.typeId();
        Class<?> aggClass = resolveClass(typeId);
        if (aggClass == null) {
            throw new NoSuchElementException(
                    "Code auto-generation is not supported for the type \u00ab" + td.singularLabel() + "»");
        }
        Predicate<String> uniqueness = uniquenessFor(typeId);
        String code = codeAllocator.allocate(aggClass, uniqueness);
        return Map.of("code", code);
    }

    // -------- mapping helpers (обобщённые: без ручных switch-каскадов) --------

    /**
     * typeId -> класс агрегата. Берётся из системного {@link MetadataSnapshot}.
     * Возвращает класс только для типов-справочников (помеченных {@code @Reference});
     * для прочих — {@code null}, что транслируется в «авто-генерация не поддерживается».
     */
    private Class<?> resolveClass(long typeId) {
        MetadataSnapshot snap = snapshots.get();
        Class<?> cls;
        try {
            cls = snap.aggregate(typeId).javaClass();
        } catch (RuntimeException notRegistered) {
            return null;
        }
        return cls.isAnnotationPresent(Reference.class) ? cls : null;
    }

    /**
     * uniqueness-callback для типа. Репозиторий берётся обобщённо из {@link RepositoryRegistry},
     * а проверка уникальности кода выражается единой {@link Specification} ({@code code = ?})
     * через {@code count(...)}. Работает для любого справочника без правок.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Predicate<String> uniquenessFor(long typeId) {
        AggregateRepository<?, ?> repo = repositories.byTypeId(typeId);
        if (repo == null) return c -> true;     // нет репо — uniqueness не enforce'им
        return candidate -> {
            Specification spec = (root, q, cb) -> cb.equal(root.get("code"), candidate);
            return ((AggregateRepository) repo).count(spec) == 0L;
        };
    }
}
