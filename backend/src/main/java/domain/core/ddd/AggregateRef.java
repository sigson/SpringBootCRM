package domain.core.ddd;

/**
 * Простая ссылка на агрегат в outbox-payload'е и domain-event'ах.
 * (typeId, idRaw) — достаточно для роутинга в Kafka (partition key) и резолва типа.
 *
 * <p>В отличие от {@link AggregateReference}, не привязан к Hibernate и не требует mapping'а.
 */
public record AggregateRef(long typeId, String idRaw) {
}
