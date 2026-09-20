package domain.core.access;

/**
 * Политика bypass'а для {@code @AccessFiltered}-фильтра.
 */
public enum BypassPolicy {

    /** Только bypass по {@code referencedTypeId}. Дефолт. */
    EXPLICIT_ONLY,

    /**
     * Транзитивно: если для {@code referencedTypeId} есть bypass — применяется;
     * если нет — проверяется и для всех типов, в которые referenced-тип ссылается
     * через {@code AggregateReference}-поля (рекурсивно по {@code AccessFilterGraph}).
     */
    AUTO_TRANSITIVE
}
