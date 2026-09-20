package domain.core.ddd.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Политика блокировок агрегата для {@code findByIdLocked}.
 *
 * <p>{@link LockMode#OPTIMISTIC} — дефолт: проверка version'а.
 * <p>{@link LockMode#OPTIMISTIC_FORCE_INCREMENT} — для агрегатов, где конкурентные
 *   изменения должны сериализоваться (например, {@code UserAggregate} при concurrent grant'ах).
 * <p>{@link LockMode#PESSIMISTIC_WRITE} — SELECT FOR UPDATE.
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AggregateLockingPolicy {

    LockMode value() default LockMode.OPTIMISTIC;

    long pessimisticTimeoutMs() default 3000;

    enum LockMode {
        NONE,
        OPTIMISTIC,
        OPTIMISTIC_FORCE_INCREMENT,
        PESSIMISTIC_READ,
        PESSIMISTIC_WRITE
    }
}
