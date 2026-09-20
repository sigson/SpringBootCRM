package domain.core.ddd;

/**
 * Контракт доменного события: знает, к какому агрегату оно относится.
 * Используется {@code OutboxWriter} для извлечения partition key и роутинга в Kafka.
 *
 */
public interface DomainEventEnvelope {

    AggregateRef aggregateRef();
}
