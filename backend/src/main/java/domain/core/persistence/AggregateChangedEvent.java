package domain.core.persistence;

import domain.core.ddd.LifecyclePhase;

/**
 * Spring-event, публикуемый {@code LifecycleProcessor}'ом ПОСЛЕ commit'а TX
 * (через {@code TransactionSynchronization}). Подписчики могут обрабатывать его в любом
 * виде, но обычно используется в read-side projection'ах для cache-invalidation.
 *
 */
public record AggregateChangedEvent(long typeId, String idRaw, LifecyclePhase phase) {
}
