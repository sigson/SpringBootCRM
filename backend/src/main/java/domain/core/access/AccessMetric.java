package domain.core.access;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Иммутабельная единица хранения прав пользователя, упакованная в одну JSON-колонку.
 *
 * <p>Все {@code with*}-методы возвращают НОВЫЙ экземпляр (полностью иммутабельный)
 * — критично для корректного сравнения в {@code AccessAwarePreUpdateListener}.
 *
 * <p>{@link #union(AccessMetric)} — каноническое объединение двух AccessMetric'ов
 * (OR глобальных и type-flag'ов, union множеств). Используется при назначении
 * пользователю набора ролей: материализация = reduce(metrics, AccessMetric.empty(), AccessMetric::union).
 *
 * <p>{@link Object#equals(Object)} / {@link Object#hashCode()} основаны на {@link AccessMetricPayload}
 * (record-level equality).
 *
 */
@Embeddable
public final class AccessMetric implements Serializable {

    @Column(name = "access_metric", columnDefinition = "varchar(4000)")
    @Convert(converter = AccessMetricJsonConverter.class)
    private AccessMetricPayload payload;

    /** JPA-конструктор. */
    protected AccessMetric() {
        this.payload = AccessMetricPayload.empty();
    }

    public AccessMetric(AccessMetricPayload p) {
        this.payload = (p == null) ? AccessMetricPayload.empty() : p;
    }

    private AccessMetricPayload payloadOrEmpty() {
        return payload == null ? AccessMetricPayload.empty() : payload;
    }

    public AccessMetricPayload payload()              { return payloadOrEmpty(); }
    public int globalFlags()                          { return payloadOrEmpty().globalFlags(); }
    public Map<Long, Integer> typeFlags()             { return payloadOrEmpty().typeFlags(); }
    public Map<Long, Set<String>> instanceWriteAcl()  { return payloadOrEmpty().instanceWriteAcl(); }

    // -------- ИММУТАБЕЛЬНЫЕ with-методы --------

    public AccessMetric withTypeFlags(long typeId, int flags) {
        return new AccessMetric(payloadOrEmpty().addTypeFlags(typeId, flags));
    }

    public AccessMetric withGlobalFlags(int flags) {
        return new AccessMetric(payloadOrEmpty().addGlobalFlags(flags));
    }

    /**
     * Добавляет запись в построчный whitelist. Смысл пары зависит от того, чья это
     * метрика, — и обе стороны читает {@code AccessResolver.effectiveOwnAccessFor}:
     * <ul>
     *   <li>в {@code ownAccess} <b>экземпляра</b>: {@code typeId} — тип субъекта
     *       (напр. typeId пользователя), {@code instanceId} — его id. «Эту запись
     *       может писать вот этот пользователь»;</li>
     *   <li>в метрике <b>пользователя или роли</b>: {@code typeId} — тип цели,
     *       {@code instanceId} — id конкретной записи. «Этому субъекту выдано право
     *       на вот эту запись».</li>
     * </ul>
     * Пустой whitelist на экземпляре означает «построчных ограничений нет», а не
     * «запрещено всем».
     */
    public AccessMetric withInstanceWhitelist(long typeId, String instanceId) {
        return new AccessMetric(payloadOrEmpty().addInstanceWhitelist(typeId, instanceId));
    }

    /**
     * Каноническое объединение прав двух метрик: OR-склейка globalFlags + typeFlags + instanceWriteAcl.
     * Делегирует в {@link AccessMetricPayload#union(AccessMetricPayload)}.
     */
    public AccessMetric union(AccessMetric other) {
        if (other == null) return this;
        return new AccessMetric(this.payloadOrEmpty().union(other.payloadOrEmpty()));
    }

    public static AccessMetric empty() {
        return new AccessMetric(AccessMetricPayload.empty());
    }

    // -------- equals/hashCode --------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AccessMetric a)) return false;
        return Objects.equals(this.payloadOrEmpty(), a.payloadOrEmpty());
    }

    @Override
    public int hashCode() {
        return payloadOrEmpty().hashCode();
    }

    @Override
    public String toString() {
        return "AccessMetric{" + payloadOrEmpty() + "}";
    }
}
