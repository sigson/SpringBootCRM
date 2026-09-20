package domain.core.ddd.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Декларативная регистрация доменного callback'а на жизненный цикл агрегата.
 *
 * <p>Метод-обработчик имеет 1 или 2 параметра: первый — наследник {@code AbstractAggregate},
 * второй (опциональный) — {@code AccessContext}. Сигнатура валидируется в
 * {@code CallbackDispatcher.validateSignature}.
 *
 * <p>{@code requiresContext} — если true (дефолт), при отсутствии {@code AccessContext}
 * в потоке вызов callback'а БРОСАЕТ {@code IllegalStateException}.
 *
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainCallback {

    long typeId();

    Phase phase();

    Kind kind();

    boolean requiresContext() default true;

    enum Phase {
        PRE_FLUSH,
        BEFORE_COMMIT,
        AFTER_COMMIT
    }

    enum Kind {
        CREATE,
        UPDATE,
        DELETE,
        SOFT_DELETE,
        ANY
    }
}
