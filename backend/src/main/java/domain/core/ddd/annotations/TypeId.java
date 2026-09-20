package domain.core.ddd.annotations;

import domain.core.access.DefaultAccess;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Маркирует агрегат и его репозиторий стабильным числовым ID типа.
 *
 * <p>{@code value()} — глобально-уникальный ID; используется в outbox-сообщениях,
 * cache-ключах, FK-чек-constraint'ах. Имя класса может меняться, {@code value()} — нет.
 *
 * <p>Ставится <b>и на агрегат, и на интерфейс репозитория</b> со совпадающим {@code value()}.
 * Несовпадение — fail-fast на старте.
 *
 * <p>{@code defaultRepoAccess()} — дефолтный уровень репо-доступа: для агрегатов,
 * которые читаются всеми, выставляется {@link DefaultAccess#READ_ONLY};
 * для системных или строгих типов — {@link DefaultAccess#HIDDEN} (deny-by-default).
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TypeId {

    long value();

    DefaultAccess defaultRepoAccess() default DefaultAccess.HIDDEN;
}
