package domain.core.bootstrap;

import domain.core.access.AccessLevel;
import domain.core.access.GlobalGrants;
import domain.core.access.GrantsProperties;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.AggregateReference;
import domain.core.ddd.annotations.AccessFiltered;
import domain.core.ddd.annotations.FieldId;
import domain.core.ddd.annotations.PrefetchDepth;
import domain.core.ddd.annotations.TypeId;
import domain.core.ddd.annotations.ValidAggregateRef;
import domain.core.ddd.annotations.ValidAggregateRefValidator;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Сканирует пакеты, строит {@link MetadataSnapshot}, валидирует fail-fast контракты и
 * публикует его через {@link MetadataSnapshotProvider}.
 *
 * <p>Запускается из {@code DomainCoreInitializer.afterPropertiesSet} — максимально рано,
 * ДО создания {@code EntityManagerFactory}: это критично, т.к. {@code AccessAware}-listener'ы
 * регистрируются через {@code IntegratorProvider} на основе snapshot'а.
 *
 * <p>Snapshot строится один раз, без валидации репозиториев — она вынесена в
 * {@code RepositoryRegistry.@PostConstruct} (избегаем цикла с {@code EntityManagerFactory}).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MetadataBootstrapper {

    private static final Logger log = LoggerFactory.getLogger(MetadataBootstrapper.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final MetadataSnapshotProvider provider;
    private final BootstrapProperties props;
    private final GrantsProperties grantsProps;

    public MetadataBootstrapper(MetadataSnapshotProvider provider,
                                BootstrapProperties props,
                                GrantsProperties grantsProps) {
        this.provider = provider;
        this.props = props;
        this.grantsProps = grantsProps;
    }

    public void run() {
        Map<Long, AggregateDescriptor> aggregates = new HashMap<>();
        Map<Class<?>, Long> typeIdByClass = new HashMap<>();
        Map<Long, Class<? extends Serializable>> idClassByTypeId = new HashMap<>();
        Map<Long, List<AccessFilterDef>> filters = new HashMap<>();
        // typeId табличной части -> Java-класс владельца (из @TabularPart);
        // резолвится в typeId владельца после полного скана (когда известны все @TypeId).
        Map<Long, Class<?>> tabularOwnerClassByTypeId = new HashMap<>();

        // -------- 1. Сканируем @TypeId+@Entity --------
        try (ScanResult sr = new ClassGraph()
                .acceptPackages(props.getBootstrap().getScanPackages().toArray(String[]::new))
                .enableAllInfo()
                .scan()) {

            for (var ci : sr.getClassesWithAnnotation(TypeId.class.getName())) {
                Class<?> cls = ci.loadClass();
                if (!cls.isAnnotationPresent(Entity.class)) continue;
                if (!AbstractAggregate.class.isAssignableFrom(cls)) {
                    throw new BootstrapValidationException(
                            "@TypeId-annotated entity must extend AbstractAggregate: " + cls.getName());
                }
                long typeId = cls.getAnnotation(TypeId.class).value();

                if (typeIdByClass.containsKey(cls)) {
                    throw new BootstrapValidationException(
                            "Duplicate class registration: " + cls);
                }
                if (typeIdByClass.containsValue(typeId)) {
                    Class<?> dup = typeIdByClass.entrySet().stream()
                            .filter(e -> e.getValue() == typeId)
                            .map(Map.Entry::getKey).findFirst().orElse(null);
                    throw new BootstrapValidationException(
                            "Duplicate @TypeId(" + typeId + ") on " + cls + " and " + dup);
                }
                typeIdByClass.put(cls, typeId);

                AggregateDescriptor desc = buildAggregateDescriptor(cls, typeId);
                aggregates.put(typeId, desc);
                idClassByTypeId.put(typeId, desc.idClass());

                // Табличная часть: запоминаем класс владельца из @TabularPart
                // (а если аннотации нет — берём дженерик-цель ownerRef'а позже).
                domain.core.ddd.annotations.TabularPart tp =
                        cls.getAnnotation(domain.core.ddd.annotations.TabularPart.class);
                if (domain.core.ddd.AbstractTabularPart.class.isAssignableFrom(cls)) {
                    if (tp == null) {
                        throw new BootstrapValidationException(
                                "Tabular part " + cls.getName() +
                                        " must be annotated with @TabularPart(owner=...)");
                    }
                    tabularOwnerClassByTypeId.put(typeId, tp.owner());
                } else if (tp != null) {
                    throw new BootstrapValidationException(
                            "@TabularPart on " + cls.getName() +
                                    " requires extending AbstractTabularPart");
                }

                AccessFiltered[] anns = cls.getAnnotationsByType(AccessFiltered.class);
                AccessFiltered.PerType[] perTypes = cls.getAnnotationsByType(AccessFiltered.PerType.class);
                if (anns.length > 0 || perTypes.length > 0) {
                    List<AccessFilterDef> defs = new ArrayList<>(anns.length + perTypes.length);
                    for (AccessFiltered af : anns) {
                        validateSqlIdentifier(af.filterField(),
                                "filter field name (on " + cls.getSimpleName() + ")");
                        defs.add(buildFilterDef(af, cls, desc));
                    }
                    for (AccessFiltered.PerType pt : perTypes) {
                        validateSqlIdentifier(pt.filterField(),
                                "per-type filter field name (on " + cls.getSimpleName() + ")");
                        defs.add(buildPerTypeFilterDef(pt, cls, desc));
                    }
                    filters.put(typeId, List.copyOf(defs));
                }
            }
        }

        // -------- 2. Резолвим referencedTypeIds в FieldDescriptor'ах AggregateReference-полей --------
        resolveAggregateRefTypeIds(aggregates, typeIdByClass, idClassByTypeId,
                tabularOwnerClassByTypeId);

        // -------- 2b. Резолвим класс владельца табличной части -> typeId владельца --------
        Map<Long, Long> tabularOwnerByTypeId = new HashMap<>();
        for (var e : tabularOwnerClassByTypeId.entrySet()) {
            Long ownerTypeId = typeIdByClass.get(e.getValue());
            if (ownerTypeId == null) {
                throw new BootstrapValidationException(
                        "@TabularPart.owner=" + e.getValue().getName() +
                                " (на typeId=" + e.getKey() + ") не зарегистрирован как @TypeId @Entity");
            }
            tabularOwnerByTypeId.put(e.getKey(), ownerTypeId);
        }

        // -------- 3. Транзитивное замыкание AccessFilterGraph --------
        AccessFilterGraph graph = AccessFilterGraph.build(aggregates);
        Map<Long, List<AccessFilterDef>> filtersClosed = closeTransitive(filters, graph);

        // -------- 4. Финальная fail-fast валидация --------
        runFailFastValidations(aggregates, filtersClosed);

        // -------- 5. Публикация --------
        GlobalGrants gg = GlobalGrants.fromConfig(grantsProps);
        MetadataSnapshot snapshot = new MetadataSnapshot(
                aggregates, typeIdByClass, idClassByTypeId,
                filtersClosed, gg, graph, tabularOwnerByTypeId, Instant.now());
        provider.publish(snapshot);
        ValidAggregateRefValidator.setProvider(provider);

        log.info("MetadataBootstrapper: published snapshot with {} aggregates, {} filters",
                aggregates.size(), filtersClosed.values().stream().mapToInt(List::size).sum());
    }

    // ====================================================================
    //  AggregateDescriptor build
    // ====================================================================

    private AggregateDescriptor buildAggregateDescriptor(Class<?> cls, long typeId) {
        String tableName = extractTableName(cls);
        validateSqlIdentifier(tableName, "table name");

        Class<? extends Serializable> idClass = extractIdClass(cls);

        boolean softDelete = isAnnotationPresentByName(cls, "org.hibernate.annotations.SoftDelete");
        boolean accessFiltered = cls.isAnnotationPresent(AccessFiltered.class)
                || cls.isAnnotationPresent(AccessFiltered.List.class)
                || cls.isAnnotationPresent(AccessFiltered.PerType.class)
                || cls.isAnnotationPresent(AccessFiltered.PerType.List.class);

        AccessLevel defaultRepoAccess = cls.getAnnotation(TypeId.class).defaultRepoAccess().toLevel();

        PrefetchDepth pd = cls.getAnnotation(PrefetchDepth.class);
        int prefetchDepth = (pd != null) ? pd.value() : props.getRefprefetch().getDefaultDepth();

        List<FieldDescriptor> flatFields = collectFieldsRecursive(cls, new long[0]);

        Set<Long> seen = new HashSet<>();
        for (FieldDescriptor fd : flatFields) {
            if (!seen.add(fd.fieldId())) {
                throw new BootstrapValidationException(
                        "Duplicate @FieldId(" + fd.fieldId() + ") in " + cls.getSimpleName() +
                                ": collision on " + fd.name());
            }
        }

        // Fail-fast на namespacing FieldId — см. validateFieldIdNamespacing.
        validateFieldIdNamespacing(cls, typeId, flatFields);

        wireParentChains(flatFields);

        Map<Long, FieldDescriptor> byFid = new HashMap<>();
        Map<String, FieldDescriptor> byProp = new HashMap<>();
        Map<String, FieldDescriptor> byShort = new HashMap<>();
        for (FieldDescriptor fd : flatFields) {
            byFid.put(fd.fieldId(), fd);
            byProp.put(fd.propertyName(), fd);
            byShort.put(fd.shortName(), fd);
        }
        return new AggregateDescriptor(typeId, cls, tableName, idClass, softDelete,
                accessFiltered, defaultRepoAccess, prefetchDepth,
                flatFields, byFid, byProp, byShort);
    }

    private List<FieldDescriptor> collectFieldsRecursive(Class<?> cls, long[] parentChain) {
        List<FieldDescriptor> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                FieldId fid = f.getAnnotation(FieldId.class);
                if (fid == null) continue;
                if (Modifier.isStatic(f.getModifiers())) continue;

                AccessLevel defaultAccess = fid.defaultAccess().toLevel();
                long fieldId = fid.value();
                String shortName = f.getName();
                String fullName = parentChain.length == 0
                        ? shortName
                        : Arrays.stream(parentChain).mapToObj(String::valueOf)
                                .collect(Collectors.joining(".")) + "." + shortName;
                long parentFieldId = parentChain.length == 0 ? -1L : parentChain[parentChain.length - 1];

                boolean isRef = AggregateReference.class.isAssignableFrom(f.getType());
                // referencedTypeIds: пусто для не-ref; sentinel {-2} для ref (резолвится в фазе 2).
                long[] referencedTypeIds = isRef ? new long[]{-2L} : new long[0];

                boolean isElementCollection = f.isAnnotationPresent(ElementCollection.class);
                Class<?> elementType = null;
                if (isElementCollection) {
                    elementType = extractCollectionElementType(f);
                }

                FieldDescriptor fd = new FieldDescriptor(
                        fieldId, fullName, shortName, /*propertyName*/ shortName,
                        parentChain, parentFieldId,
                        f.getType(), f, defaultAccess,
                        isRef, referencedTypeIds,
                        isElementCollection, elementType);
                out.add(fd);

                // Рекурсия для @Embedded
                boolean embedded = f.isAnnotationPresent(Embedded.class)
                        || f.getType().isAnnotationPresent(Embeddable.class);
                if (embedded && !isRef) {  // не спускаемся внутрь AggregateReference
                    long[] childChain = Arrays.copyOf(parentChain, parentChain.length + 1);
                    childChain[parentChain.length] = fieldId;
                    out.addAll(collectFieldsRecursive(f.getType(), childChain));
                }
            }
        }
        return out;
    }

    private void wireParentChains(List<FieldDescriptor> all) {
        Map<Long, FieldDescriptor> byFid = all.stream()
                .collect(Collectors.toMap(FieldDescriptor::fieldId, fd -> fd));
        for (FieldDescriptor fd : all) {
            long[] chain = fd.parentChain();
            FieldDescriptor[] parents = new FieldDescriptor[chain.length];
            for (int i = 0; i < chain.length; i++) parents[i] = byFid.get(chain[i]);
            fd.setParentDescriptorChain(parents);
        }
    }

    /**
     * Fail-fast: каждый FieldId, объявленный непосредственно на concrete-агрегате (не
     * унаследованный от абстрактного супертипа), должен лежать в namespace своего typeId —
     * {@code [typeId*100 ; typeId*100+99]}. Это enforce'ит конвенцию литералов
     * {@code <typeId>_<seq>L} (напр. {@code 9001_10L}). Поля абстрактных супертипов
     * освобождены — у них зарезервированы низкие ID (1, 2, …).
     */
    private void validateFieldIdNamespacing(Class<?> entityCls, long typeId,
                                             List<FieldDescriptor> fields) {
        long expectedLow  = typeId * 100;
        long expectedHigh = expectedLow + 99;
        for (FieldDescriptor fd : fields) {
            Class<?> declaring = fd.rawField().getDeclaringClass();
            if (Modifier.isAbstract(declaring.getModifiers())) continue;     // абстрактные парент'ы — OK
            long fid = fd.fieldId();
            if (fid < expectedLow || fid > expectedHigh) {
                throw new BootstrapValidationException(
                        "@FieldId(" + fid + ") на " + entityCls.getSimpleName() + "." + fd.name() +
                                " вне namespace typeId=" + typeId +
                                " (ожидается [" + expectedLow + "; " + expectedHigh + "]). " +
                                "Конвенция: <typeId>_<seq>L, например " + typeId + "_10L.");
            }
        }
    }

    private void resolveAggregateRefTypeIds(Map<Long, AggregateDescriptor> aggregates,
                                            Map<Class<?>, Long> typeIdByClass,
                                            Map<Long, Class<? extends Serializable>> idClassByTypeId,
                                            Map<Long, Class<?>> tabularOwnerClassByTypeId) {
        for (AggregateDescriptor desc : aggregates.values()) {
            for (FieldDescriptor fd : desc.fields()) {
                if (!fd.isAggregateReference()) continue;
                long[] cur = fd.referencedTypeIds();
                if (cur.length != 1 || cur[0] != -2L) continue;   // не sentinel — уже резолвлено
                ValidAggregateRef var = fd.rawField().getAnnotation(ValidAggregateRef.class);
                if (var == null) {
                    // Поля-ссылки без @ValidAggregateRef — это framework-поля:
                    //  * ownerRef табличной части (declaringClass = AbstractTabularPart):
                    //    цель = тип владельца из @TabularPart;
                    //  * createdBy/updatedBy (declaringClass = AbstractAuditedAggregate):
                    //    цель = тип UserAggregate-наследника.
                    if (fd.rawField().getDeclaringClass()
                            == domain.core.ddd.AbstractTabularPart.class) {
                        Class<?> ownerCls = tabularOwnerClassByTypeId.get(desc.typeId());
                        Long ownerTid = ownerCls == null ? null : typeIdByClass.get(ownerCls);
                        setReferencedTypeIds(fd, ownerTid != null ? new long[]{ownerTid} : new long[0]);
                        continue;
                    }
                    Long userTid = typeIdByClass.entrySet().stream()
                            .filter(e -> domain.core.ddd.UserAggregate.class.isAssignableFrom(e.getKey()))
                            .map(Map.Entry::getValue)
                            .findFirst()
                            .orElse(null);
                    setReferencedTypeIds(fd, userTid != null ? new long[]{userTid} : new long[0]);
                    continue;
                }
                Class<? extends AbstractAggregate<?>>[] targets = var.targets();
                if (targets.length == 0) {
                    throw new BootstrapValidationException(
                            "@ValidAggregateRef.targets is empty on " + fd.name() +
                                    " in " + desc.javaClass().getName() + " — нужен ≥1 целевой тип");
                }
                // Маркер AnyReference: ссылка на любой тип. Не раскрываем конкретные typeId'ы
                // (иначе access-граф и union-view раздувались бы на все агрегаты), а помечаем
                // поле флагом — валидатор примет любой зарегистрированный targetTypeId с
                // подходящим id-типом, а UI раскроет выбор среди всех типов.
                boolean anyTarget = false;
                for (Class<? extends AbstractAggregate<?>> t : targets) {
                    if (t == domain.core.ddd.AnyReference.class) { anyTarget = true; break; }
                }
                if (anyTarget) {
                    fd.setAnyReference(true);
                    setReferencedTypeIds(fd, new long[0]);
                    continue;
                }
                long[] ids = new long[targets.length];
                for (int i = 0; i < targets.length; i++) {
                    Long tid = typeIdByClass.get(targets[i]);
                    if (tid == null) {
                        throw new BootstrapValidationException(
                                "@ValidAggregateRef.targets[" + i + "]=" + targets[i].getName() +
                                        " on " + fd.name() + " is not a registered @TypeId-class");
                    }
                    // Union возможен только для целей с одинаковым idType (две колонки
                    // type_id+id_raw физически не вместят разные id-типы).
                    Class<? extends Serializable> targetIdClass = idClassByTypeId.get(tid);
                    if (targetIdClass != null && !targetIdClass.equals(var.idType())) {
                        throw new BootstrapValidationException(
                                "@ValidAggregateRef on " + fd.name() + ": idType=" + var.idType().getSimpleName() +
                                        " не совпадает с id-классом цели " + targets[i].getSimpleName() +
                                        " (" + targetIdClass.getSimpleName() + "). " +
                                        "Все targets union-ссылки обязаны иметь одинаковый idType.");
                    }
                    ids[i] = tid;
                }
                setReferencedTypeIds(fd, ids);
            }
        }
    }

    /** У FieldDescriptor нет setter'а для referencedTypeIds — проставляем рефлексией (bootstrap-time). */
    private void setReferencedTypeIds(FieldDescriptor fd, long[] typeIds) {
        try {
            Field f = FieldDescriptor.class.getDeclaredField("referencedTypeIds");
            f.setAccessible(true);
            f.set(fd, typeIds.clone());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("FieldDescriptor.referencedTypeIds reflection failed", e);
        }
    }

    private AccessFilterDef buildFilterDef(AccessFiltered ann, Class<?> aggClass, AggregateDescriptor desc) {
        FieldDescriptor fd = desc.fieldByName(ann.filterField());
        if (fd == null) {
            throw new BootstrapValidationException(
                    "Filter field '" + ann.filterField() + "' not found in " + aggClass.getName() +
                            ". Available: " + desc.fields().stream().map(FieldDescriptor::shortName).toList());
        }
        // Имя колонки извлекается через @Column.name() или @AttributeOverride на AggregateReference-поле.
        // Для ref-полей берём AttributeOverride с name="targetIdRaw".
        String filterColumn = extractFilterColumn(fd);
        validateSqlIdentifier(filterColumn, "filter column");

        String filterName = ann.filterName().isEmpty()
                ? "filter_" + aggClass.getSimpleName().toLowerCase(Locale.ROOT) + "_" + ann.filterField()
                : ann.filterName();

        return new AccessFilterDef(
                ann.filterField(),
                filterColumn,
                ann.userClaim(),
                ann.referencedTypeId(),
                filterName,
                ann.bypassPolicy(),
                new long[0]);
    }

    /** Строит {@link AccessFilterDef} из {@code @AccessFiltered.PerType} (union row-filter). */
    private AccessFilterDef buildPerTypeFilterDef(AccessFiltered.PerType ann, Class<?> aggClass, AggregateDescriptor desc) {
        FieldDescriptor fd = desc.fieldByName(ann.filterField());
        if (fd == null) {
            throw new BootstrapValidationException(
                    "@AccessFiltered.PerType filter field '" + ann.filterField() + "' not found in " +
                            aggClass.getName() + ". Available: " +
                            desc.fields().stream().map(FieldDescriptor::shortName).toList());
        }
        if (ann.filterName().isEmpty()) {
            throw new BootstrapValidationException(
                    "@AccessFiltered.PerType on " + aggClass.getSimpleName() + "." + ann.filterField() +
                            " must declare an explicit filterName (matching a @FilterDef/@Filter on the entity)");
        }
        String filterColumn = extractFilterColumn(fd);
        validateSqlIdentifier(filterColumn, "per-type filter column");
        long refTid = ann.referencedTypeId() < 0 ? ann.typeId() : ann.referencedTypeId();
        return new AccessFilterDef(
                ann.filterField(),
                filterColumn,
                ann.userClaim(),
                refTid,
                ann.filterName(),
                ann.bypassPolicy(),
                new long[0],
                ann.typeId());
    }

    private String extractFilterColumn(FieldDescriptor fd) {
        Field f = fd.rawField();
        // @AttributeOverrides с name = "targetIdRaw"
        jakarta.persistence.AttributeOverrides overrides = f.getAnnotation(jakarta.persistence.AttributeOverrides.class);
        if (overrides != null) {
            for (jakarta.persistence.AttributeOverride a : overrides.value()) {
                if ("targetIdRaw".equals(a.name())) {
                    String col = a.column().name();
                    if (!col.isEmpty()) return col;
                }
            }
        }
        // одиночный @AttributeOverride
        jakarta.persistence.AttributeOverride single = f.getAnnotation(jakarta.persistence.AttributeOverride.class);
        if (single != null && "targetIdRaw".equals(single.name())) {
            String col = single.column().name();
            if (!col.isEmpty()) return col;
        }
        // @Column на самом поле
        jakarta.persistence.Column c = f.getAnnotation(jakarta.persistence.Column.class);
        if (c != null && !c.name().isEmpty()) return c.name();
        // fallback: имя поля + "_id" по convention
        return fd.shortName() + "_id";
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private Map<Long, List<AccessFilterDef>> closeTransitive(
            Map<Long, List<AccessFilterDef>> filters, AccessFilterGraph graph) {
        Map<Long, List<AccessFilterDef>> out = new HashMap<>();
        for (var e : filters.entrySet()) {
            List<AccessFilterDef> closed = new ArrayList<>(e.getValue().size());
            for (AccessFilterDef f : e.getValue()) {
                if (f.bypassPolicy() == domain.core.access.BypassPolicy.AUTO_TRANSITIVE) {
                    long[] reach = graph.reachableFrom(f.referencedTypeId());
                    closed.add(f.withTransitiveBypass(reach));
                } else {
                    closed.add(f);
                }
            }
            out.put(e.getKey(), List.copyOf(closed));
        }
        return out;
    }

    private void runFailFastValidations(Map<Long, AggregateDescriptor> aggregates,
                                        Map<Long, List<AccessFilterDef>> filters) {
        // 5: @AccessFiltered.referencedTypeId указывает на существующий зарегистрированный тип
        for (var e : filters.entrySet()) {
            for (AccessFilterDef f : e.getValue()) {
                if (!aggregates.containsKey(f.referencedTypeId())) {
                    throw new BootstrapValidationException(
                            "@AccessFiltered.referencedTypeId=" + f.referencedTypeId() +
                                    " not registered (used in typeId=" + e.getKey() + ")");
                }
            }
        }
        // 12: каждое AggregateReference-поле имеет @ValidAggregateRef (кроме framework-полей:
        //     createdBy/updatedBy на AbstractAuditedAggregate и ownerRef на AbstractTabularPart)
        for (AggregateDescriptor desc : aggregates.values()) {
            for (FieldDescriptor fd : desc.fields()) {
                if (!fd.isAggregateReference()) continue;
                Class<?> declaring = fd.rawField().getDeclaringClass();
                if (declaring == domain.core.ddd.AbstractAuditedNoAclAggregate.class) continue;
                if (declaring == domain.core.ddd.AbstractTabularPart.class) continue;
                if (fd.rawField().getAnnotation(ValidAggregateRef.class) == null) {
                    throw new BootstrapValidationException(
                            "AggregateReference field " + fd.name() + " in " +
                                    desc.javaClass().getName() + " missing @ValidAggregateRef");
                }
            }
        }
    }

    private static String extractTableName(Class<?> cls) {
        Table t = cls.getAnnotation(Table.class);
        if (t != null && !t.name().isEmpty()) return t.name();
        return cls.getSimpleName().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Serializable> extractIdClass(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(Id.class)) {
                    Class<?> t = f.getType();
                    if (!Serializable.class.isAssignableFrom(t)) {
                        throw new BootstrapValidationException(
                                "Id type must implement Serializable: " + t);
                    }
                    return (Class<? extends Serializable>) t;
                }
            }
        }
        // fallback по generic-сигнатуре AbstractAggregate<ID>
        Type sup = cls.getGenericSuperclass();
        while (sup instanceof Class<?> sc && sc != Object.class) {
            sup = sc.getGenericSuperclass();
        }
        if (sup instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length > 0 && args[0] instanceof Class<?> idCls
                    && Serializable.class.isAssignableFrom(idCls)) {
                return (Class<? extends Serializable>) idCls;
            }
        }
        throw new BootstrapValidationException("Cannot determine id-class for " + cls);
    }

    private static Class<?> extractCollectionElementType(Field f) {
        Type t = f.getGenericType();
        if (t instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length > 0 && args[0] instanceof Class<?> c) {
                return c;
            }
        }
        return Object.class;
    }

    private static boolean isAnnotationPresentByName(Class<?> cls, String fqn) {
        for (var a : cls.getAnnotations()) {
            if (a.annotationType().getName().equals(fqn)) return true;
        }
        return false;
    }

    private static void validateSqlIdentifier(String name, String kind) {
        if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new BootstrapValidationException(
                    "Unsafe SQL " + kind + ": '" + name + "'. Allowed: [a-zA-Z_][a-zA-Z0-9_]*");
        }
    }
}
