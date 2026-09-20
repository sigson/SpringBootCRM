package domain.core.access;

/** Резолвер {@link AccessMetric} по {@link AccessContext}. */
public interface UserAccessProvider {

    /** Возвращает {@link AccessMetric} для пользователя из контекста. */
    AccessMetric metricFor(AccessContext ctx);

    /** Эвикт записи из L1 (вызывается из {@code GrantService} после grant'а). */
    void evictUser(String typeId, String idRaw);

    /** Догрев L1 после ручной загрузки {@code UserAggregate}. */
    void warmCache(long typeId, String idRaw, AccessMetric metric);
}
