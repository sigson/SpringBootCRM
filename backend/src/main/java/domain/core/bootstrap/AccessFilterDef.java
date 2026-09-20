package domain.core.bootstrap;

import domain.core.access.BypassPolicy;
import domain.core.access.UserClaim;

/**
 * Иммутабельный runtime-дескриптор одной {@code @AccessFiltered}-аннотации.
 *
 * <p>Заполняется на bootstrap'е, хранится в {@link MetadataSnapshot}, используется
 * {@code AccessFilterActivator}-ом и {@code PostLoadAccessCheckListener}-ом.
 */
public final class AccessFilterDef {

    private final String filterField;            // "organization"
    private final String filterColumn;           // "org_id"
    private final UserClaim userClaim;
    private final long referencedTypeId;
    private final String filterName;             // имя Hibernate-фильтра
    private final BypassPolicy bypassPolicy;
    private final long[] transitiveBypassTypeIds;
    /**
     * Дискриминатор union-поля: вариант, к которому применяется этот фильтр
     * (для {@code @AccessFiltered.PerType}). Для обычного {@code @AccessFiltered}
     * совпадает с {@link #referencedTypeId} (нет ветвления по типу).
     */
    private final long discriminatorTypeId;

    public AccessFilterDef(String filterField,
                           String filterColumn,
                           UserClaim userClaim,
                           long referencedTypeId,
                           String filterName,
                           BypassPolicy bypassPolicy,
                           long[] transitiveBypassTypeIds) {
        this(filterField, filterColumn, userClaim, referencedTypeId, filterName,
                bypassPolicy, transitiveBypassTypeIds, referencedTypeId);
    }

    public AccessFilterDef(String filterField,
                           String filterColumn,
                           UserClaim userClaim,
                           long referencedTypeId,
                           String filterName,
                           BypassPolicy bypassPolicy,
                           long[] transitiveBypassTypeIds,
                           long discriminatorTypeId) {
        this.filterField = filterField;
        this.filterColumn = filterColumn;
        this.userClaim = userClaim;
        this.referencedTypeId = referencedTypeId;
        this.filterName = filterName;
        this.bypassPolicy = bypassPolicy;
        this.transitiveBypassTypeIds = transitiveBypassTypeIds == null
                ? new long[0] : transitiveBypassTypeIds.clone();
        this.discriminatorTypeId = discriminatorTypeId;
    }

    public String filterField()              { return filterField; }
    public String filterColumn()             { return filterColumn; }
    public UserClaim userClaim()             { return userClaim; }
    public long referencedTypeId()           { return referencedTypeId; }
    public String filterName()               { return filterName; }
    public BypassPolicy bypassPolicy()       { return bypassPolicy; }
    public long[] transitiveBypassTypeIds()  { return transitiveBypassTypeIds.clone(); }
    public long discriminatorTypeId()        { return discriminatorTypeId; }

    /** Возвращает копию с заполненным транзитивным замыканием bypass'ов. */
    public AccessFilterDef withTransitiveBypass(long[] reach) {
        return new AccessFilterDef(filterField, filterColumn, userClaim,
                referencedTypeId, filterName, bypassPolicy, reach, discriminatorTypeId);
    }
}
