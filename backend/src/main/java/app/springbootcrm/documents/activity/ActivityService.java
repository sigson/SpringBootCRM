package app.springbootcrm.documents.activity;

import app.springbootcrm.catalogs.customer.Customer;
import app.springbootcrm.catalogs.leadsource.LeadSource;
import app.springbootcrm.metadata.ObjectRowQueryService;

import app.springbootcrm.common.AdvancedFilterParam;
import app.springbootcrm.common.PageResponse;
import app.springbootcrm.common.SqlRowFilter;
import app.springbootcrm.auth.AdminCheck;
import app.springbootcrm.user.User;
import app.springbootcrm.user.UserRepository;
import domain.core.access.AccessFlags;
import domain.core.access.PermissionRequirement;
import domain.core.access.StructuredAccessDeniedException;
import domain.core.ddd.AggregateReference;
import domain.core.web.ValidationFailedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Function;

/**
 * Сервис модуля «Календарь».
 *
 * <p>Отказы в доступе бросаются как {@link StructuredAccessDeniedException}, ошибки
 * валидации — как {@link ValidationFailedException}, поэтому клиент получает единый
 * envelope-формат.
 */
@Service
public class ActivityService {

    private final ActivityRepository repo;
    private final UserRepository users;
    private final AdminCheck admin;

    public ActivityService(ActivityRepository repo, UserRepository users,
                           AdminCheck admin) {
        this.repo = repo;
        this.users = users;
        this.admin = admin;
    }

    @Transactional(readOnly = true)
    public List<ActivityDto> list() {
        return repo.findAllSorted().stream().map(ActivityDto::of).toList();
    }

    /**
     * Постраничная загрузка событий (chunked для больших списков). Row-level фильтр
     * {@code filter_activity_owner} применяется тем же AOP, что и для {@link #list()} —
     * {@code findAll(spec, Pageable)} матчится паттерном {@code find*} в
     * {@link domain.core.persistence.AccessFilterActivator}, поэтому фильтр и admin-bypass
     * действуют и на страницу, и на {@code count(...)}. {@code total} в ответе —
     * полное число видимых пользователю событий <i>после</i> применения фильтров,
     * посчитанное СУБД.
     *
     * <p><b>SQL-фильтрация.</b> Quick-фильтры по колонкам, глобальный поиск,
     * advanced-фильтры с операторами и сортировка транслируются в единую
     * {@link Specification} и выполняются СУБД. В память грузится только запрошенная
     * страница.
     *
     * <p>Колонки: {@code title} (строка), {@code starts}/{@code ends} (дата по
     * UTC-дню), {@code owner} (reference). Для {@code owner} advanced-операторы
     * (eq/in/...) работают по UUID из picker'а напрямую, а quick-фильтр/глобальный
     * поиск — через резолвер «подстрока → UUID пользователей»: текст сверяется с
     * display-форматом владельца (код — наим. / displayName / username), а в запрос
     * идёт {@code owner_id IN (...)}.
     */
    @Transactional(readOnly = true)
    public PageResponse<ActivityDto> listPaged(
            int page, int size, String search,
            Map<String, String> columnFilters,
            List<AdvancedFilterParam> advanced,
            String sortBy, String sortDir) {
        return listPaged(page, size, search, columnFilters, advanced, sortBy, sortDir, true);
    }

    /**
     * Варіант з керуванням обчисленням {@code total}. {@code withCount=false} пропускає
     * окремий {@code count(...)}-запит (повне сканування на кожну сторінку) і повертає
     * {@code total = -1} — для гортання, коли клієнт уже знає загальну кількість для
     * поточного фільтра. Симетрично з generic-списком об'єктів
     * ({@link app.springbootcrm.metadata.ObjectRowQueryService#listRows}).
     */
    @Transactional(readOnly = true)
    public PageResponse<ActivityDto> listPaged(
            int page, int size, String search,
            Map<String, String> columnFilters,
            List<AdvancedFilterParam> advanced,
            String sortBy, String sortDir, boolean withCount) {

        // Резолвер owner-подстроки → множество UUID пользователей (для quick/search).
        // Таблица пользователей — ограниченный справочник; скан один раз на запрос.
        Function<String, Collection<?>> ownerResolver = term -> {
            String q = term == null ? "" : term.toLowerCase(Locale.ROOT);
            if (q.isBlank()) return List.of();
            List<UUID> ids = new ArrayList<>();
            for (User u : users.findAll()) {
                String disp = ownerDisplayString(u);
                if (disp != null && disp.toLowerCase(Locale.ROOT).contains(q)) {
                    ids.add(u.getId());
                }
            }
            return ids;
        };

        List<SqlRowFilter.Column<Activity>> columns = List.of(
                SqlRowFilter.Column.string("title", "title"),
                SqlRowFilter.Column.instant("starts", "startsAt"),
                SqlRowFilter.Column.instant("ends", "endsAt"),
                // owner: канонический UUID = owner.targetIdRaw; резолвер даёт текстовый поиск.
                SqlRowFilter.Column.reference("owner",
                        (r, cb) -> r.get("owner").get("targetIdRaw"), "owner", ownerResolver),
                // subject: union-ссылка (User | Customer | LeadSource).
                // Значение фильтра кодируется как "<typeId>:<uuid>" → предикат добавляет
                // и id (subject.targetIdRaw), и дискриминатор (subject.targetTypeId).
                SqlRowFilter.Column.unionReference("subject",
                        (r, cb) -> r.get("subject").get("targetIdRaw"),
                        (r, cb) -> r.get("subject").get("targetTypeId"),
                        "subject")
        );

        // Многоколоночная сортировка: разбираем sortBy/sortDir (поддерживает
        // comma-separated). По умолчанию (без явного sortBy) — startsAt asc.
        List<SqlRowFilter.SortSpec> sorts = SqlRowFilter.parseSorts(sortBy, sortDir);
        if (sorts.isEmpty()) sorts = List.of(new SqlRowFilter.SortSpec("starts", "asc"));

        Specification<Activity> spec = SqlRowFilter.build(
                columns, null, null, search, columnFilters, advanced, sorts);

        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 500);

        List<Activity> rows;
        long total;
        if (withCount) {
            Page<Activity> result = repo.findAll(spec, PageRequest.of(p, s));
            rows = result.getContent();
            total = result.getTotalElements();
        } else {
            // Наступні сторінки того ж фільтра: total уже відомий клієнту — count не потрібен.
            rows = repo.findPageContent(spec, PageRequest.of(p, s));
            total = -1;   // sentinel: «не рахували»
        }

        List<ActivityDto> content = rows.stream()
                .map(ActivityDto::of).toList();
        return PageResponse.of(content, p, s, total);
    }

    /** Display-формат владельца события: «код — наим.» / displayName / username. */
    private static String ownerDisplayString(User u) {
        String code = u.getCode();
        String name = u.getName();
        if (code != null && name != null) return code + " — " + name;
        if (u.getDisplayName() != null) return u.getDisplayName();
        return u.getUsername();
    }

    @Transactional(readOnly = true)
    public ActivityDto get(UUID id) {
        Activity e = repo.findVisibleById(id)
                .orElseThrow(() -> new NoSuchElementException("Activity not found: " + id));
        return ActivityDto.of(e);
    }

    @Transactional
    public ActivityDto create(MutationRequest req) {
        UUID userId = admin.currentUserIdOrNull();
        if (userId == null) {
            throw new StructuredAccessDeniedException(
                    "Only authenticated users may create activities",
                    PermissionRequirement.global(AccessFlags.READ));
        }
        validate(req);
        UUID ownerId = resolveOwnerId(req, userId);
        Activity e = new Activity(
                UUID.randomUUID(),
                req.title(),
                req.description(),
                req.startsAt(),
                req.endsAt(),
                AggregateReference.<User, UUID>ofRaw(User.TYPE_ID, ownerId.toString())
        );
        e.setSubjectRef(req.subjectTypeId(), req.subjectId());
        return ActivityDto.of(repo.save(e));
    }

    @Transactional
    public ActivityDto update(UUID id, MutationRequest req) {
        validate(req);
        Activity e = repo.findVisibleById(id)
                .orElseThrow(() -> new NoSuchElementException("Activity not found: " + id));
        requireOwnerOrAdmin(e);
        e.setTitle(req.title());
        e.setDescription(req.description());
        e.setStartsAt(req.startsAt());
        e.setEndsAt(req.endsAt());
        e.setSubjectRef(req.subjectTypeId(), req.subjectId());
        // Владелец — редактируемый реквизит: переназначаем, если клиент прислал
        // ownerId. resolveOwnerId применяет то же правило, что и при создании —
        // админ может назначить любого, обычный пользователь только себя.
        if (req.ownerId() != null) {
            UUID newOwner = resolveOwnerId(req, admin.currentUserIdOrNull());
            e.setOwnerId(newOwner);
        }
        return ActivityDto.of(repo.save(e));
    }

    @Transactional
    public void delete(UUID id) {
        Activity e = repo.findVisibleById(id)
                .orElseThrow(() -> new NoSuchElementException("Activity not found: " + id));
        requireOwnerOrAdmin(e);
        repo.deleteById(id);
    }

    // ============ Helpers ============

    private UUID resolveOwnerId(MutationRequest req, UUID currentUserId) {
        if (req.ownerId() == null) return currentUserId;
        if (req.ownerId().equals(currentUserId)) return currentUserId;
        if (!admin.isAdmin()) {
            throw new StructuredAccessDeniedException(
                    "Only administrators may assign an activity to another user",
                    PermissionRequirement.global(AccessFlags.ADMIN_READ));
        }
        if (!users.existsById(req.ownerId())) {
            throw new NoSuchElementException("User not found: " + req.ownerId());
        }
        return req.ownerId();
    }

    private void requireOwnerOrAdmin(Activity e) {
        if (admin.isAdmin()) return;
        UUID self = admin.currentUserIdOrNull();
        if (self == null) {
            throw new StructuredAccessDeniedException(
                    "The current user could not be determined",
                    PermissionRequirement.global(AccessFlags.READ));
        }
        UUID ownerId = null;
        if (e.getOwner() != null && e.getOwner().targetIdRaw() != null) {
            try { ownerId = UUID.fromString(e.getOwner().targetIdRaw()); }
            catch (IllegalArgumentException ignored) {}
        }
        if (ownerId == null || !ownerId.equals(self)) {
            throw new StructuredAccessDeniedException(
                    "Allowed only for the activity owner or an administrator",
                    PermissionRequirement.repo(Activity.TYPE_ID, AccessFlags.ADMIN_WRITE));
        }
    }

    private void validate(MutationRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw ValidationFailedException.ofField("title",
                    "Title is required");
        }
        if (req.title().length() > 200) {
            throw ValidationFailedException.ofField("title",
                    "Title must not exceed 200 characters");
        }
        if (req.startsAt() == null) {
            throw ValidationFailedException.ofField("startsAt", "Start date is required");
        }
        if (req.endsAt() == null) {
            throw ValidationFailedException.ofField("endsAt", "End date is required");
        }
        if (req.endsAt().isBefore(req.startsAt())) {
            throw ValidationFailedException.ofField("endsAt",
                    "End date cannot be earlier than the start date");
        }
        if (req.description() != null && req.description().length() > 2000) {
            throw ValidationFailedException.ofField("description",
                    "Description must not exceed 2000 characters");
        }
        validateSubject(req);
    }

    /**
     * Валидация union-поля «связанный объект»: пара (typeId, id) должна быть либо
     * полностью пустой, либо полностью заполненной, а typeId — одним из допустимых
     * вариантов union'а (User | Customer | LeadSource). Это лишь
     * UX-проверка; строгая целостность гарантируется {@code ValidAggregateRefValidator}'ом
     * во время persist'а.
     */
    private void validateSubject(MutationRequest req) {
        boolean hasType = req.subjectTypeId() != null;
        boolean hasId = req.subjectId() != null;
        if (hasType != hasId) {
            throw ValidationFailedException.ofField("subjectId",
                    "A related object needs both a type and a specific record");
        }
        if (hasType) {
            long t = req.subjectTypeId();
            if (t != User.TYPE_ID
                    && t != Customer.TYPE_ID
                    && t != LeadSource.TYPE_ID) {
                throw ValidationFailedException.ofField("subjectTypeId",
                        "Unsupported related object type: " + t);
            }
        }
    }

    public record MutationRequest(
            String title,
            String description,
            Instant startsAt,
            Instant endsAt,
            UUID ownerId,
            /** Union «связанный объект»: typeId варианта (необязательно). */
            Long subjectTypeId,
            /** UUID целевого агрегата связанного объекта (необязательно). */
            UUID subjectId) {}
}
