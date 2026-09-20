package app.springbootcrm.admin;

import app.springbootcrm.access.AccessRole;
import app.springbootcrm.access.AccessRoleRepository;
import app.springbootcrm.access.AccessRoleService;
import app.springbootcrm.auth.AdminCheck;
import app.springbootcrm.documents.activity.Activity;
import app.springbootcrm.documents.activity.ActivityRepository;
import app.springbootcrm.interfaces.InterfaceLayout;
import app.springbootcrm.metadata.AggregateClassification;
import app.springbootcrm.metadata.TypeRegistry;
import app.springbootcrm.metadata.TypeRegistry.TypeDescriptor;
import app.springbootcrm.reference.CodeAllocationService;
import app.springbootcrm.reference.Reference;
import app.springbootcrm.user.User;
import app.springbootcrm.user.UserDto;
import app.springbootcrm.user.UserRepository;
import app.springbootcrm.user.UserService;
import domain.core.bootstrap.AggregateDescriptor;
import domain.core.bootstrap.FieldDescriptor;
import domain.core.bootstrap.MetadataSnapshot;
import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.AbstractTabularPart;
import domain.core.ddd.AggregateReference;
import domain.core.persistence.AggregateRepository;
import domain.core.persistence.RepositoryRegistry;
import domain.core.web.ValidationFailedException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Сервіс генерації тестових даних (адмін-утиліта).
 *
 * <p>Повністю керується метаданими: заповнює будь-який довідник чи регістр з кодової
 * бази без ручного переліку типів.
 *
 * <h3>Масштабованість</h3>
 * Генерація й зачистка виконуються порціями ({@link #CHUNK} записів), кожна — в окремій
 * транзакції ({@code REQUIRES_NEW}) з {@code flush}+{@code clear} та JDBC-батчингом, тож
 * пам'ять і час лінійні навіть для мільйонів записів (персистент-контекст не росте
 * необмежено). Коди довідників видаються блоком (один інкремент лічильника на порцію).
 *
 * <p>Порції комітяться незалежно, тож збій посеред прогону лишає створені порції в БД —
 * усе згенероване занесене до {@link DataGenLog}, тож {@link #purge()} прибере його.
 *
 * <h3>Що генерується / зачистка</h3>
 * Довідники/регістри/табличні частини (ТЧ — {@code count} на кожного власника); користувачі —
 * окремий шлях (роль/пароль/інтерфейс); «Ролі»/«Інтерфейси» не генеруються; зачистка — за
 * {@link DataGenLog} у безпечному для FK порядку + legacy за маркером {@link #GEN_FLAG}.
 */
@Service
public class DataGenService {

    /** Маркер-флаг у текстових полях згенерованих об'єктів. */
    public static final String GEN_FLAG = "[[GEN]]";

    /**
     * Максимум кешованих ссилкових кандидатів на тип у <b>benchmark</b>-режимі.
     * За ТЗ розкид випадкових ссилок у ссилкових полях обмежений: кешуємо не
     * більше стількох id на цільовий тип і присвоюємо їх по колу (round-robin),
     * без вибірки з повного пулу й без per-row shuffle.
     */
    private static final int BENCHMARK_REF_FANOUT = 10;

    /** Верхня межа кількості за один виклик: 1 млрд. */
    private static final long MAX_COUNT = 1_000_000_000L;

    /** Розмір порції: стільки записів пишеться/видаляється в одній транзакції. */
    private static final int CHUNK = 500;

    /** Типи, які НЕ генеруються випадково (керують правами/навігацією). */
    private static final Set<Long> EXCLUDED_TYPE_IDS =
            Set.of(AccessRole.TYPE_ID, InterfaceLayout.TYPE_ID);

    private final MetadataSnapshotProvider snapshots;
    private final RepositoryRegistry repositories;
    private final TypeRegistry typeRegistry;
    private final CodeAllocationService codeAlloc;
    private final UserService userService;
    private final AccessRoleService accessRoleService;
    private final AccessRoleRepository roleRepo;
    private final UserRepository userRepo;
    private final ActivityRepository activityRepo;
    private final DataGenLogRepository genLog;
    private final AdminCheck admin;

    /** Self-proxy: щоб chunk-методи з {@code REQUIRES_NEW} йшли через Spring AOP. */
    private final DataGenService self;

    @PersistenceContext
    private EntityManager em;

    public DataGenService(MetadataSnapshotProvider snapshots,
                          RepositoryRegistry repositories,
                          TypeRegistry typeRegistry,
                          CodeAllocationService codeAlloc,
                          UserService userService,
                          AccessRoleService accessRoleService,
                          AccessRoleRepository roleRepo,
                          UserRepository userRepo,
                          ActivityRepository activityRepo,
                          DataGenLogRepository genLog,
                          AdminCheck admin,
                          @Lazy DataGenService self) {
        this.snapshots = snapshots;
        this.repositories = repositories;
        this.typeRegistry = typeRegistry;
        this.codeAlloc = codeAlloc;
        this.userService = userService;
        this.accessRoleService = accessRoleService;
        this.roleRepo = roleRepo;
        this.userRepo = userRepo;
        this.activityRepo = activityRepo;
        this.genLog = genLog;
        this.admin = admin;
        this.self = self;
    }

    // ==================================================================
    //  Перелік типів, доступних для генерації (для UI)
    // ==================================================================

    /**
     * Список типів, які можна генерувати (для динамічної побудови форми на
     * фронтенді). Виключає: NONSTANDARD-контролери, «Ролі»/«Інтерфейси» та
     * «Користувачів» (для останніх є окрема секція з паролем/роллю/інтерфейсом).
     */
    public List<GenTarget> targets() {
        admin.requireAdmin();
        List<GenTarget> out = new ArrayList<>();
        for (TypeDescriptor td : typeRegistry.all()) {
            if (TypeRegistry.REPRESENTATION_NONSTANDARD.equals(td.representation())) continue;
            if (EXCLUDED_TYPE_IDS.contains(td.typeId())) continue;
            if (td.typeId() == User.TYPE_ID) continue;        // окрема секція

            String kind;
            String ownerLabel = null;
            if (td.isTabularPart()) {
                kind = "TABULAR";
                TypeDescriptor owner = td.ownerTypeId() == null
                        ? null : typeRegistry.byTypeId(td.ownerTypeId());
                ownerLabel = owner != null ? owner.pluralLabel() : "owners";
            } else if (td.isReference()) {
                kind = "REFERENCE";
            } else {
                kind = "REGISTER";
            }
            out.add(new GenTarget(td.typeId(), td.slug(), td.singularLabel(),
                    td.pluralLabel(), td.iconHint(), kind, ownerLabel));
        }
        out.sort(Comparator.comparing(GenTarget::kind).thenComparing(GenTarget::pluralLabel));
        return out;
    }

    // ==================================================================
    //  Універсальна генерація одного типу (порціями)
    // ==================================================================

    /**
     * Генерує {@code count} записів типу {@code typeId} порціями по {@link #CHUNK}
     * (кожна — окрема транзакція). Для табличних частин {@code count} — кількість
     * рядків <b>на кожного власника</b>.
     *
     * <p>Метод навмисно НЕ {@code @Transactional}: транзакції відкривають
     * chunk-методи (через {@link #self}), щоб обсяг не накопичувався в одній.
     *
     * @return фактично створено записів
     */
    public int generate(long typeId, int count) {
        return generate(typeId, count, new GenOptions(false, false));
    }

    /** Сумісність зі старим контрактом (лише benchmark). */
    public int generate(long typeId, int count, boolean benchmark) {
        return generate(typeId, count, new GenOptions(benchmark, false));
    }

    /**
     * Генерує {@code count} записів типу {@code typeId} порціями по {@link #CHUNK}.
     *
     * <p>Опції ({@link GenOptions}):
     * <ul>
     *   <li><b>benchmark</b> — швидке наповнення: ссилкові поля з кешу
     *       ≤{@link #BENCHMARK_REF_FANOUT} кандидатів на тип (round-robin, без per-row
     *       random/shuffle), скаляри генеруються РАЗ на порцію й переприсвоюються;</li>
     *   <li><b>noLogging</b> — НЕ писати {@link DataGenLog} по рядку. Прибирає подвоєння
     *       вставок, АЛЕ робить точковий {@link #purge()} неможливим для цих рядків — їх
     *       прибирає лише «очистити весь тип» ({@link #purgeType(long)}). Текстові поля все
     *       ще несуть {@link #GEN_FLAG} (legacy-зачистка лишається для типів із текстом).</li>
     * </ul>
     */
    public int generate(long typeId, int count, GenOptions opts) {
        admin.requireAdmin();
        validateCount(count);

        TypeDescriptor td = typeRegistry.byTypeId(typeId);
        if (td == null || TypeRegistry.REPRESENTATION_NONSTANDARD.equals(td.representation())) {
            throw ValidationFailedException.ofField("typeId", "Unknown type for generation");
        }
        if (EXCLUDED_TYPE_IDS.contains(typeId)) {
            throw ValidationFailedException.ofField("typeId",
                    "This catalog cannot be randomly generated (Roles/Interfaces)");
        }
        if (typeId == User.TYPE_ID) {
            throw ValidationFailedException.ofField("typeId",
                    "Generate users from the dedicated section (password/role/interface are required)");
        }

        MetadataSnapshot snap = snapshots.get();
        AggregateDescriptor agg = snap.aggregate(typeId);
        // Кеш id наявних посилань будуємо ОДИН раз і переносимо між порціями
        // (рядки, не сутності — переживають em.clear()).
        Map<Long, List<String>> idCache = new HashMap<>();

        if (td.isTabularPart()) {
            return generateTabular(agg, td, count, idCache, opts);
        }
        return generateRegular(agg, count, idCache, opts);
    }

    // --------- регістри / довідники ---------

    private int generateRegular(AggregateDescriptor agg, int count,
                                Map<Long, List<String>> idCache, GenOptions opts) {
        boolean isRef = AggregateClassification.isReference(agg.javaClass());
        int created = 0;
        int remaining = count;
        while (remaining > 0) {
            int n = Math.min(CHUNK, remaining);
            created += self.persistRegularChunk(agg, n, isRef, idCache, opts);
            remaining -= n;
        }
        return created;
    }

    /** Одна порція регістру/довідника в окремій транзакції з батчингом. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int persistRegularChunk(AggregateDescriptor agg, int n, boolean isRef,
                                   Map<Long, List<String>> idCache, GenOptions opts) {
        enableBatching();
        long typeId = agg.typeId();
        List<String> codes = preallocateCodes(agg, n);
        // benchmark: один набір скалярів на порцію — переприсвоюємо без per-row random.
        Map<String, Object> scalarTemplate = opts.benchmark() ? new HashMap<>() : null;
        for (int i = 0; i < n; i++) {
            Object inst = newInstance(agg.javaClass());
            UUID id = assignId(inst);
            populate(agg, inst, isRef, idCache, codes == null ? null : codes.get(i),
                    opts.benchmark(), i, scalarTemplate);
            em.persist(inst);
            if (!opts.noLogging()) {
                em.persist(new DataGenLog(typeId, id.toString()));
            }
        }
        em.flush();
        em.clear();
        return n;
    }

    // --------- табличні частини ---------

    private int generateTabular(AggregateDescriptor agg, TypeDescriptor td,
                                int perOwner, Map<Long, List<String>> idCache, GenOptions opts) {
        Long ownerTypeId = td.ownerTypeId();
        if (ownerTypeId == null) {
            throw ValidationFailedException.ofField("typeId",
                    "No owner type is defined for this tabular part");
        }
        List<String> ownerIds = idsOf(ownerTypeId, idCache);
        if (ownerIds.isEmpty()) {
            throw ValidationFailedException.ofField("count",
                    "No owners exist (" + labelOf(ownerTypeId)
                            + ") - generate them first");
        }
        int created = 0;
        for (String ownerIdRaw : ownerIds) {
            UUID ownerId = UUID.fromString(ownerIdRaw);
            int remaining = perOwner;
            while (remaining > 0) {
                int n = Math.min(CHUNK, remaining);
                created += self.persistTabularChunk(agg, ownerTypeId, ownerId, n, idCache, opts);
                remaining -= n;
            }
        }
        return created;
    }

    /** Одна порція рядків ТЧ для конкретного власника, окрема транзакція. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int persistTabularChunk(AggregateDescriptor agg, long ownerTypeId, UUID ownerId,
                                   int n, Map<Long, List<String>> idCache, GenOptions opts) {
        enableBatching();
        long typeId = agg.typeId();
        List<String> codes = preallocateCodes(agg, n);
        Map<String, Object> scalarTemplate = opts.benchmark() ? new HashMap<>() : null;
        for (int i = 0; i < n; i++) {
            Object inst = newInstance(agg.javaClass());
            UUID id = assignId(inst);
            ((AbstractTabularPart<?>) inst).setOwner(ownerTypeId, ownerId);
            populate(agg, inst, /* isRef */ false, idCache, codes == null ? null : codes.get(i),
                    opts.benchmark(), i, scalarTemplate);
            em.persist(inst);
            if (!opts.noLogging()) {
                em.persist(new DataGenLog(typeId, id.toString()));
            }
        }
        em.flush();
        em.clear();
        return n;
    }

    /** Блок кодів для порції (лише для типів із {@code @Reference}); інакше {@code null}. */
    private List<String> preallocateCodes(AggregateDescriptor agg, int n) {
        if (!agg.javaClass().isAnnotationPresent(Reference.class)) return null;
        return codeAlloc.allocateBlock(agg.javaClass(), n);
    }

    // ==================================================================
    //  Заповнення реквізитів
    // ==================================================================

    private void populate(AggregateDescriptor agg, Object inst, boolean isRef,
                          Map<Long, List<String>> idCache, String preCode,
                          boolean benchmark, int rowIndex,
                          Map<String, Object> scalarTemplate) {
        boolean hasReferenceCode = agg.javaClass().isAnnotationPresent(Reference.class);
        for (FieldDescriptor fd : agg.fields()) {
            if (fd.parentChain().length > 0) continue;     // вкладені @Embedded — через батька
            if (fd.isElementCollection()) continue;        // колекції не генеруємо
            String n = fd.shortName();
            if (isInternal(n) || isInternalType(fd)) continue;

            if (fd.isAggregateReference()) {
                AggregateReference<?, ?> ref = benchmark
                        ? benchmarkRef(fd, idCache, rowIndex)
                        : randomRef(fd, idCache);
                if (ref == null) {
                    if (RandomValueFactory.isRequired(fd)) {
                        throw ValidationFailedException.ofField("typeId",
                                "No references available for the required field \u00ab" + n
                                        + "\u00bb - generate the matching catalogs first");
                    }
                    continue;   // необов'язкове — лишаємо порожнім
                }
                fd.write(inst, ref);
                continue;
            }

            if (n.equals("code") && hasReferenceCode) {
                String code = (preCode != null) ? preCode
                        : codeAlloc.allocate(agg.javaClass(), c -> true);
                fd.write(inst, code);
                continue;
            }

            boolean flagged = n.equals("name") || n.equals("title");   // головне «назвове» поле — з маркером
            Object value;
            if (benchmark && scalarTemplate != null) {
                // Один набір скалярів на порцію: генеруємо при першому рядку,
                // далі переприсвоюємо те саме значення — прибирає per-row random.
                if (scalarTemplate.containsKey(n)) {
                    value = scalarTemplate.get(n);
                } else {
                    value = RandomValueFactory.forField(fd, flagged);
                    scalarTemplate.put(n, value);
                }
            } else {
                value = RandomValueFactory.forField(fd, flagged);
            }
            if (value != null) {
                fd.write(inst, value);
            }
        }
    }

    /**
     * <b>Benchmark</b>-ссилка: бере id по колу з обмеженого кешу (≤{@link #BENCHMARK_REF_FANOUT}
     * кандидатів на цільовий тип), не з повного пулу. Кеш будується один раз на тип і
     * переживає {@code em.clear()} (зберігає рядки-id, не сутності), тож наступні порції
     * лише присвоюють. Жодного per-row {@code shuffle}/{@code nextInt} по великому списку —
     * звідси максимальна швидкість і обмежений розкид ссилок за ТЗ.
     */
    private AggregateReference<?, ?> benchmarkRef(FieldDescriptor fd,
                                                  Map<Long, List<String>> idCache,
                                                  int rowIndex) {
        long[] raw = fd.referencedTypeIds();
        // union → детермінований вибір типу (за rowIndex), щоб не shuffl'ити на рядок.
        List<Long> targets = new ArrayList<>();
        for (long t : raw) if (t > 0) targets.add(t);
        if (targets.isEmpty()) return null;
        for (int k = 0; k < targets.size(); k++) {
            long chosenType = targets.get((rowIndex + k) % targets.size());
            List<String> pool = benchmarkPool(chosenType, idCache);
            if (!pool.isEmpty()) {
                String id = pool.get(rowIndex % pool.size());   // round-robin, без random
                return AggregateReference.ofRaw(chosenType, id);
            }
        }
        return null;
    }

    /**
     * Обмежений (≤{@link #BENCHMARK_REF_FANOUT}) кеш id-кандидатів для benchmark-ссилок.
     * Зберігається у {@code idCache} під негативним ключем (щоб не конфліктувати з повним
     * пулом {@link #idsOf}), будується вибіркою першої сторінки, а не {@code findAll()} —
     * тож не вантажить мільйони id у пам'ять.
     */
    @SuppressWarnings("unchecked")
    private List<String> benchmarkPool(long typeId, Map<Long, List<String>> idCache) {
        long key = -typeId - 1;   // окремий простір ключів від повного пулу
        List<String> cached = idCache.get(key);
        if (cached != null) return cached;
        AggregateRepository<?, ?> repo = repositories.byTypeId(typeId);
        if (repo == null) {
            idCache.put(key, List.of());
            return List.of();
        }
        var pageReq = org.springframework.data.domain.PageRequest.of(0, BENCHMARK_REF_FANOUT);
        List<String> pool = new ArrayList<>(BENCHMARK_REF_FANOUT);
        for (Object o : ((JpaRepository<?, ?>) repo).findAll(pageReq)) {
            Object id = ((AbstractAggregate<?>) o).getId();
            if (id != null) pool.add(id.toString());
            if (pool.size() >= BENCHMARK_REF_FANOUT) break;
        }
        List<String> result = List.copyOf(pool);
        idCache.put(key, result);
        return result;
    }

    /** Випадкове посилання для ref/union-поля; {@code null}, якщо немає кандидатів. */
    private AggregateReference<?, ?> randomRef(FieldDescriptor fd,
                                               Map<Long, List<String>> idCache) {
        long[] raw = fd.referencedTypeIds();
        List<Long> targets = new ArrayList<>();
        for (long t : raw) if (t > 0) targets.add(t);
        if (targets.isEmpty()) return null;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // union → випадковий допустимий тип; перебираємо у випадковому порядку,
        // доки не знайдемо тип, у якого є хоча б один запис.
        java.util.Collections.shuffle(targets, rnd);
        for (long chosenType : targets) {
            List<String> ids = idsOf(chosenType, idCache);
            if (!ids.isEmpty()) {
                String id = ids.get(rnd.nextInt(ids.size()));
                return AggregateReference.ofRaw(chosenType, id);
            }
        }
        return null;
    }

    // ==================================================================
    //  Генерація користувачів (окремий шлях, теж порціями)
    // ==================================================================

    /**
     * Генерує {@code count} користувачів із дефолтними роллю, паролем та
     * інтерфейсом. Порціями (кожна — окрема транзакція), кожен логується у
     * {@link DataGenLog}.
     */
    public int generateUsers(int count, UUID defaultRoleId,
                             String defaultPassword, UUID defaultInterfaceId) {
        admin.requireAdmin();
        validateCount(count);
        String password = (defaultPassword == null || defaultPassword.isBlank())
                ? "Password123" : defaultPassword;
        if (password.length() < 6) {
            throw ValidationFailedException.ofField("defaultPassword",
                    "The default password must contain at least 6 characters");
        }
        if (defaultRoleId != null && !roleRepo.existsById(defaultRoleId)) {
            throw ValidationFailedException.ofField("defaultRoleId",
                    "The selected default role was not found");
        }
        if (defaultInterfaceId != null && !existsGeneric(InterfaceLayout.TYPE_ID, defaultInterfaceId)) {
            throw ValidationFailedException.ofField("defaultInterfaceId",
                    "The selected default interface was not found");
        }

        int created = 0;
        int remaining = count;
        while (remaining > 0) {
            int n = Math.min(CHUNK, remaining);
            created += self.persistUserChunk(n, defaultRoleId, password, defaultInterfaceId);
            remaining -= n;
        }
        return created;
    }

    /** Одна порція користувачів в окремій транзакції. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int persistUserChunk(int n, UUID defaultRoleId, String password, UUID defaultInterfaceId) {
        for (int i = 0; i < n; i++) {
            String suffix = RandomValueFactory.shortRandom();
            String username = "gen_user_" + suffix;
            String name = GEN_FLAG + " User " + suffix;
            UserDto dto = userService.createUser(new UserService.CreateUserRequest(
                    null, name, username,
                    suffix + "@gen.local",
                    "Generated " + suffix,
                    password, true));
            if (defaultRoleId != null) {
                accessRoleService.setRoleForUser(dto.id(), defaultRoleId);
            }
            if (defaultInterfaceId != null) {
                userService.setInterfaceLayout(dto.id(), defaultInterfaceId);
            }
            genLog.save(new DataGenLog(User.TYPE_ID, dto.id().toString()));
        }
        em.flush();
        em.clear();
        return n;
    }

    // ==================================================================
    //  Зачистка (порціями)
    // ==================================================================

    /**
     * Видаляє всі згенеровані записи (за {@link DataGenLog}) у безпечному для
     * зовнішніх ключів порядку: табличні частини → регістри → довідники →
     * користувачі. Видалення йде порціями (окремі транзакції). Додатково —
     * legacy-зачистка за {@link #GEN_FLAG}.
     */
    public PurgeReport purge() {
        admin.requireAdmin();

        List<DataGenLog> logs = genLog.findAllByOrderByCreatedAtDesc();
        // Глобальне сортування за пріоритетом гарантує, що залежні (ТЧ/регістри)
        // видаляються раніше за довідники, на які вони посилаються.
        logs.sort(Comparator.comparingInt(l -> deletionPriority(l.getTypeId())));

        Map<String, Integer> byType = new LinkedHashMap<>();
        int total = 0, usersDeleted = 0, eventsDeleted = 0;

        for (int i = 0; i < logs.size(); i += CHUNK) {
            List<DataGenLog> chunk = new ArrayList<>(logs.subList(i, Math.min(i + CHUNK, logs.size())));
            PurgeChunkResult r = self.purgeChunk(chunk);
            total += r.total();
            usersDeleted += r.users();
            eventsDeleted += r.events();
            r.byType().forEach((k, v) -> byType.merge(k, v, Integer::sum));
        }

        // legacy fallback (дані старої версії без журналу) — окрема транзакція.
        PurgeChunkResult legacy = self.purgeLegacy();
        total += legacy.total();
        usersDeleted += legacy.users();
        eventsDeleted += legacy.events();
        legacy.byType().forEach((k, v) -> byType.merge(k, v, Integer::sum));

        return new PurgeReport(total, usersDeleted, eventsDeleted, byType);
    }

    /**
     * <h3>«Очистити весь тип».</h3>
     * Видаляє <b>ВСІ</b> рядки типу {@code typeId} (і реальні, і згенеровані) однією
     * bulk-операцією {@code deleteAllInBatch()} та прибирає всі записи журналу
     * {@link DataGenLog} цього типу. Призначено для типів, наповнених у режимі
     * <b>noLogging</b> (де точковий {@link #purge()} неможливий), але працює для
     * будь-якого типу.
     *
     * <p><b>Небезпечно:</b> знищує також реальні дані типу. Викликається лише з
     * адмін-UI з явним підтвердженням. Користувачів цим шляхом чистити не можна
     * (FK на роль/інтерфейс і окремий шлях видалення) — для них {@link #purge()}.
     *
     * @return скільки рядків видалено
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @SuppressWarnings({"rawtypes", "unchecked"})
    public int purgeType(long typeId) {
        admin.requireAdmin();
        if (typeId == User.TYPE_ID) {
            throw ValidationFailedException.ofField("typeId",
                    "Users cannot be purged this way - use the regular cleanup");
        }
        TypeDescriptor td = typeRegistry.byTypeId(typeId);
        if (td == null || TypeRegistry.REPRESENTATION_NONSTANDARD.equals(td.representation())) {
            throw ValidationFailedException.ofField("typeId", "Unknown type");
        }
        AggregateRepository<?, ?> repo = repositories.byTypeId(typeId);
        if (repo == null) return 0;
        JpaRepository raw = (JpaRepository) repo;
        long before = raw.count();
        raw.deleteAllInBatch();                 // одна bulk-операція на весь тип
        genLog.deleteByTypeId(typeId);          // прибираємо журнал цього типу (якщо був)
        return (int) Math.min(before, Integer.MAX_VALUE);
    }
    public PurgeChunkResult purgeChunk(List<DataGenLog> chunk) {
        // Групуємо за typeId, зберігаючи відносний порядок (вже відсортовано за пріоритетом).
        Map<Long, List<UUID>> idsByType = new LinkedHashMap<>();
        for (DataGenLog entry : chunk) {
            UUID id;
            try { id = UUID.fromString(entry.getRecordId()); }
            catch (IllegalArgumentException e) { continue; }
            idsByType.computeIfAbsent(entry.getTypeId(), k -> new ArrayList<>()).add(id);
        }

        Map<String, Integer> byType = new LinkedHashMap<>();
        int total = 0, users = 0, events = 0;

        for (Map.Entry<Long, List<UUID>> e : idsByType.entrySet()) {
            long typeId = e.getKey();
            List<UUID> ids = e.getValue();
            if (typeId == User.TYPE_ID) {
                for (UUID id : ids) {
                    if (deleteUser(id)) {
                        users++; total++;
                        byType.merge(labelOf(typeId), 1, Integer::sum);
                    }
                }
            } else {
                int n = deleteAllOfType(typeId, ids);
                total += n;
                if (typeId == Activity.TYPE_ID) events += n;
                if (n > 0) byType.merge(labelOf(typeId), n, Integer::sum);
            }
        }

        genLog.deleteAllInBatch(chunk);   // bulk-видалення журналу однієї порції
        return new PurgeChunkResult(total, users, events, byType);
    }

    /** Legacy-зачистка: дані старої версії, помічені {@link #GEN_FLAG} у назві. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PurgeChunkResult purgeLegacy() {
        Map<String, Integer> byType = new LinkedHashMap<>();
        int events = 0, users = 0;

        List<Activity> genEvents = activityRepo.findAllSorted().stream()
                .filter(ev -> ev.getTitle() != null && ev.getTitle().contains(GEN_FLAG))
                .toList();
        for (Activity ev : genEvents) {
            activityRepo.deleteById(ev.getId());
            events++;
            byType.merge(labelOf(Activity.TYPE_ID), 1, Integer::sum);
        }
        List<User> genUsers = userRepo.findAll().stream()
                .filter(u -> u.getName() != null && u.getName().contains(GEN_FLAG))
                .toList();
        for (User u : genUsers) {
            accessRoleService.clearRole(u.getId());
            userService.deleteUser(u.getId());
            users++;
            byType.merge(labelOf(User.TYPE_ID), 1, Integer::sum);
        }
        return new PurgeChunkResult(events + users, users, events, byType);
    }

    private boolean deleteUser(UUID id) {
        if (userRepo.findById(id).isEmpty()) return false;
        accessRoleService.clearRole(id);
        userService.deleteUser(id);
        return true;
    }

    /** Bulk-видалення наявних записів типу за id; повертає, скільки існувало. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private int deleteAllOfType(long typeId, List<UUID> ids) {
        AggregateRepository<?, ?> repo = repositories.byTypeId(typeId);
        if (repo == null) return 0;
        JpaRepository raw = (JpaRepository) repo;
        // Рахуємо реально наявні (журнал може посилатися на вже видалене вручну).
        List existing = raw.findAllById(ids);
        if (existing.isEmpty()) return 0;
        raw.deleteAllByIdInBatch(ids);   // одна bulk-операція, без перевірок наявності
        return existing.size();
    }

    @SuppressWarnings({"rawtypes"})
    private boolean existsGeneric(long typeId, UUID id) {
        AggregateRepository<?, ?> repo = repositories.byTypeId(typeId);
        if (repo == null) return false;
        return ((JpaRepository) repo).existsById(id);
    }

    /** Пріоритет видалення: менший — раніше. ТЧ/регістри перед довідниками, користувачі — останні. */
    private int deletionPriority(long typeId) {
        if (typeId == User.TYPE_ID) return 3;
        TypeDescriptor td = typeRegistry.byTypeId(typeId);
        if (td == null) return 1;
        if (td.isTabularPart()) return 0;
        return td.isReference() ? 2 : 1;
    }

    // ==================================================================
    //  Helpers
    // ==================================================================

    /** Вмикає JDBC-батчинг у поточній сесії Hibernate (локально для генерації). */
    private void enableBatching() {
        em.unwrap(Session.class).setJdbcBatchSize(CHUNK);
    }

    private void validateCount(int count) {
        if (count <= 0) {
            throw ValidationFailedException.ofField("count", "The count must be greater than zero");
        }
        if (count > MAX_COUNT) {
            throw ValidationFailedException.ofField("count",
                    "At most " + MAX_COUNT + " objects at a time");
        }
    }

    /** Усі id записів типу (рядкове представлення); кешується в межах виклику. */
    private List<String> idsOf(long typeId, Map<Long, List<String>> idCache) {
        return idCache.computeIfAbsent(typeId, t -> {
            AggregateRepository<?, ?> repo = repositories.byTypeId(t);
            if (repo == null) return List.of();
            List<String> ids = new ArrayList<>();
            for (Object o : repo.findAll()) {
                Object id = ((AbstractAggregate<?>) o).getId();
                if (id != null) ids.add(id.toString());
            }
            return ids;
        });
    }

    private String labelOf(long typeId) {
        TypeDescriptor td = typeRegistry.byTypeId(typeId);
        return td != null ? td.pluralLabel() : ("typeId=" + typeId);
    }

    private static Object newInstance(Class<?> cls) {
        try {
            var ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Could not instantiate " + cls.getName()
                            + " (a no-argument constructor is required)", e);
        }
    }

    /** Призначає випадковий {@link UUID} полю {@code @Id} і повертає його. */
    private static UUID assignId(Object inst) {
        for (Class<?> c = inst.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(Id.class)) {
                    if (f.getType() != UUID.class) {
                        throw new IllegalStateException("Expected a UUID @Id in " + inst.getClass());
                    }
                    UUID id = UUID.randomUUID();
                    try {
                        f.setAccessible(true);
                        f.set(inst, id);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException("Could not set the id", e);
                    }
                    return id;
                }
            }
        }
        throw new IllegalStateException("No @Id field found in " + inst.getClass());
    }

    /** Службові поля, що не є реквізитами для генерації. */
    private static boolean isInternal(String name) {
        return name.equals("version") || name.equals("ownAccess") || name.equals("access")
                || name.equals("createdBy") || name.equals("updatedBy")
                || name.equals("ownerRef")
                || name.equals("passwordHash") || name.equals("password");
    }

    private static boolean isInternalType(FieldDescriptor fd) {
        String tn = fd.javaType().getSimpleName();
        return tn.equals("AccessMetric");
    }

    // ==================================================================
    //  Shapes
    // ==================================================================

    /** Опис типу, доступного для генерації (для UI). */
    public record GenTarget(
            long typeId, String slug, String singularLabel, String pluralLabel,
            String iconHint,
            /** REFERENCE | REGISTER | TABULAR */
            String kind,
            /** Для TABULAR — назва типу-власника (множина), інакше {@code null}. */
            String ownerLabel) {}

    /** Звіт про зачистку. {@code byType} — скільки видалено за кожним типом. */
    public record PurgeReport(int total, int usersDeleted, int eventsDeleted,
                              Map<String, Integer> byType) {}

    /** Внутрішній результат однієї порції зачистки (public — потрібно проксі AOP). */
    public record PurgeChunkResult(int total, int users, int events,
                                   Map<String, Integer> byType) {}

    /**
     * Опції генерації.
     *
     * @param benchmark швидке наповнення (≤{@link #BENCHMARK_REF_FANOUT} кешованих
     *                  ссилок round-robin, скаляри на порцію)
     * @param noLogging НЕ писати {@link DataGenLog} по рядку (½ вставок, але точковий
     *                  {@link #purge()} для цих рядків недоступний — лише {@link #purgeType(long)})
     */
    public record GenOptions(boolean benchmark, boolean noLogging) {}
}
