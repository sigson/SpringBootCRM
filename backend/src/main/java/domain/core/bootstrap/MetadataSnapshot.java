package domain.core.bootstrap;

import domain.core.access.GlobalGrants;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Иммутабельная единая структура метаданных, построенная на bootstrap'е. */
public final class MetadataSnapshot {

    private final Map<Long, AggregateDescriptor> aggregatesByTypeId;
    private final Map<Class<?>, Long> typeIdByClass;
    private final Map<Long, Class<? extends Serializable>> idClassByTypeId;
    private final Map<Long, List<AccessFilterDef>> filtersByTypeId;
    private final GlobalGrants globalGrants;
    private final AccessFilterGraph filterGraph;
    /** typeId табличной части -> typeId её агрегата-владельца. Пусто для регистров/агрегатов. */
    private final Map<Long, Long> tabularOwnerByTypeId;
    private final Instant builtAt;

    public MetadataSnapshot(Map<Long, AggregateDescriptor> aggregatesByTypeId,
                            Map<Class<?>, Long> typeIdByClass,
                            Map<Long, Class<? extends Serializable>> idClassByTypeId,
                            Map<Long, List<AccessFilterDef>> filtersByTypeId,
                            GlobalGrants globalGrants,
                            AccessFilterGraph filterGraph,
                            Map<Long, Long> tabularOwnerByTypeId,
                            Instant builtAt) {
        this.aggregatesByTypeId = Map.copyOf(aggregatesByTypeId);
        this.typeIdByClass = Map.copyOf(typeIdByClass);
        this.idClassByTypeId = Map.copyOf(idClassByTypeId);
        // filters могут быть пустыми списками — копируем глубоко
        this.filtersByTypeId = Map.copyOf(filtersByTypeId);
        this.globalGrants = (globalGrants == null) ? GlobalGrants.empty() : globalGrants;
        this.filterGraph = filterGraph;
        this.tabularOwnerByTypeId = tabularOwnerByTypeId == null
                ? Map.of() : Map.copyOf(tabularOwnerByTypeId);
        this.builtAt = (builtAt == null) ? Instant.now() : builtAt;
    }

    public AggregateDescriptor aggregate(long typeId) {
        AggregateDescriptor d = aggregatesByTypeId.get(typeId);
        if (d == null) throw new IllegalStateException("No descriptor for typeId=" + typeId);
        return d;
    }

    public long typeIdOf(Class<?> cls) {
        Long t = typeIdByClass.get(cls);
        if (t == null) throw new IllegalStateException(
                "Class " + cls.getName() + " is not annotated with @TypeId or not registered");
        return t;
    }

    public Class<? extends Serializable> idClassByTypeId(long typeId) {
        Class<? extends Serializable> c = idClassByTypeId.get(typeId);
        if (c == null) throw new IllegalStateException("No id class for typeId=" + typeId);
        return c;
    }

    /** {@code true}, если {@code typeId} зарегистрирован как агрегат. */
    public boolean hasTypeId(long typeId) {
        return aggregatesByTypeId.containsKey(typeId);
    }

    /** id-класс типа, либо {@code null} если тип не зарегистрирован (без выброса исключения). */
    public Class<? extends Serializable> idClassByTypeIdOrNull(long typeId) {
        return idClassByTypeId.get(typeId);
    }

    public List<AccessFilterDef> filtersForTypeId(long typeId) {
        return filtersByTypeId.getOrDefault(typeId, List.of());
    }

    public Collection<AggregateDescriptor> allAggregates() {
        return aggregatesByTypeId.values();
    }

    public GlobalGrants globalGrants() { return globalGrants; }
    public AccessFilterGraph filterGraph() { return filterGraph; }
    public Instant builtAt() { return builtAt; }

    /**
     * typeId агрегата-владельца для табличной части, либо {@code null}, если
     * {@code typeId} — не табличная часть (обычный агрегат или свободный регистр).
     * Используется {@code AccessResolver} для автоматического проброса флагов
     * доступа владельца на его табличную часть.
     */
    public Long tabularOwnerTypeId(long typeId) {
        return tabularOwnerByTypeId.get(typeId);
    }

    /** {@code true}, если {@code typeId} — табличная часть какого-либо агрегата. */
    public boolean isTabularPart(long typeId) {
        return tabularOwnerByTypeId.containsKey(typeId);
    }
}
