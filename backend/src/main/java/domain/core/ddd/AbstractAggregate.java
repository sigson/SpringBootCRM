package domain.core.ddd;

import domain.core.access.AccessMetric;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;

import java.io.Serializable;

/**
 * Lean-корень для всех записываемых объектов системы: несёт только универсальное —
 * идентификатор, доменные хуки жизненного цикла, безопасный {@link #toString()} и
 * пустые дефолты access/аудита, которые переопределяет аудируемый подтип.
 *
 * <p>Три семейства, проходящие через один {@code AggregateRepository} и общие
 * access-listener'ы: {@link AbstractAuditedAggregate} (версия, аудит, per-instance ACL),
 * <b>регистр</b> (технический объект без версии/автора — этот класс напрямую) и
 * {@link AbstractTabularPart} (регистр со ссылкой на агрегат-владелец).
 *
 * <p>Здесь навешивается только {@code AggregateLifecycleListener};
 * {@code AuditingEntityListener} — ниже, на {@link AbstractAuditedAggregate}, чтобы
 * технические объекты не получали отсутствующих у них аудит-полей.
 */
@MappedSuperclass
@EntityListeners({
        domain.core.persistence.AggregateLifecycleListener.class
})
public abstract class AbstractAggregate<ID extends Serializable> {

    public abstract ID getId();

    /**
     * Per-instance ACL. Lean-дефолт — без ограничений (регистры/ТЧ его не несут);
     * аудируемые агрегаты переопределяют персистентным {@link AccessMetric}-полем.
     */
    public AccessMetric ownAccess() {
        return AccessMetric.empty();
    }

    /**
     * Автор создания. На lean-корне отсутствует ({@code null});
     * {@link AbstractAuditedAggregate} переопределяет ссылкой на пользователя.
     */
    @SuppressWarnings("rawtypes")
    public AggregateReference getCreatedBy() { return null; }

    /** Автор последнего изменения. См. {@link #getCreatedBy()}. */
    @SuppressWarnings("rawtypes")
    public AggregateReference getUpdatedBy() { return null; }

    // -------- Доменные хуки --------

    /** Перед flush'ем; БД-операции отсюда запрещены ({@code InFlushDatabaseAccessGuard}). */
    protected void onPreFlush(LifecyclePhase phase) {}

    /** Перед commit'ом TX. Подходит для записи в outbox. */
    protected void onBeforeCommit(LifecyclePhase phase) {}

    /** После commit'а TX. Подходит для нерекурсивного логирования / метрик. */
    protected void onAfterCommit(LifecyclePhase phase) {}

    // Мост к жизненному циклу — internal API, вызывается LifecycleProcessor'ом.

    public final void __invokePreFlush(LifecyclePhase phase)    { onPreFlush(phase); }
    public final void __invokeBeforeCommit(LifecyclePhase phase) { onBeforeCommit(phase); }
    public final void __invokeAfterCommit(LifecyclePhase phase)  { onAfterCommit(phase); }

    /** Null-safe; маскирует HIDDEN/WRITE_ONLY поля через {@link domain.core.persistence.SafeToString}. */
    @Override
    public String toString() {
        return domain.core.persistence.SafeToString.render(this);
    }
}
