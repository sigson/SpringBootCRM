package domain.core.persistence;

import domain.core.bootstrap.MetadataSnapshotProvider;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.IdCodec;
import domain.core.ddd.LifecyclePhase;
import domain.core.ddd.annotations.DomainCallback;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Центральный диспетчер lifecycle-фаз. Вызывается из {@link AggregateLifecycleListener}'а
 * на PRE_FLUSH; регистрирует {@link TransactionSynchronization} для BEFORE_COMMIT и AFTER_COMMIT.
 *
 * <p>Гарантии:
 * <ul>
 *   <li>onPreFlush — БД-операции запрещены ({@link AggregateLifecycleListener#IN_FLUSH});</li>
 *   <li>onBeforeCommit — выполняется внутри той же TX, можно писать в БД (outbox);</li>
 *   <li>onAfterCommit — TX уже зафиксирована, нельзя писать в БД, только side-effect'ы.</li>
 * </ul>
 *
 * <p><strong>Имя бина задано явно</strong> ({@code "aggregateLifecycleProcessor"}), потому что
 * имя {@code "lifecycleProcessor"} зарезервировано Spring'ом за
 * {@link org.springframework.context.LifecycleProcessor} — авто-сгенерированное по имени класса
 * имя бина ({@code "lifecycleProcessor"}) конфликтовало бы с системным во время
 * {@code AbstractApplicationContext.initLifecycleProcessor()}.
 *
 */
@Component("aggregateLifecycleProcessor")
public class LifecycleProcessor {

    private final CallbackDispatcher dispatcher;
    private final MetadataSnapshotProvider snapshots;
    private final ApplicationEventPublisher events;

    public LifecycleProcessor(CallbackDispatcher dispatcher,
                               MetadataSnapshotProvider snapshots,
                               ApplicationEventPublisher events) {
        this.dispatcher = dispatcher;
        this.snapshots = snapshots;
        this.events = events;
    }

    public void runPreFlush(AbstractAggregate<?> agg, LifecyclePhase phase) {
        agg.__invokePreFlush(phase);
        long typeId = snapshots.get().typeIdOf(agg.getClass());
        dispatcher.dispatch(agg, typeId, phase, DomainCallback.Phase.PRE_FLUSH);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new LifecycleSync(agg, typeId, phase));
        }
    }

    private final class LifecycleSync implements TransactionSynchronization {

        private final AbstractAggregate<?> agg;
        private final long typeId;
        private final LifecyclePhase phase;

        LifecycleSync(AbstractAggregate<?> agg, long typeId, LifecyclePhase phase) {
            this.agg = agg;
            this.typeId = typeId;
            this.phase = phase;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            agg.__invokeBeforeCommit(phase);
            dispatcher.dispatch(agg, typeId, phase, DomainCallback.Phase.BEFORE_COMMIT);
        }

        @Override
        public void afterCommit() {
            agg.__invokeAfterCommit(phase);
            dispatcher.dispatch(agg, typeId, phase, DomainCallback.Phase.AFTER_COMMIT);

            // Spring-event для cache-invalidator'ов и projection'ов
            try {
                String idRaw = IdCodec.encode(agg.getId());
                events.publishEvent(new AggregateChangedEvent(typeId, idRaw, phase));
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }
}
