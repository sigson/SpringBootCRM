package domain.core.ddd.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Декларирует, что агрегат требует {@code AccessContext} для любых write-операций.
 *
 * <p>{@code strict=true} (по умолчанию): попытка write без {@code AccessContext} в потоке
 * — {@code AccessAwarePreUpdateListener}/{@code AccessAwarePreInsertListener} бросает
 * {@code AccessDeniedException}.
 *
 * <p>{@code strict=false}: при отсутствии {@code AccessContext} проверки молча
 * пропускаются — ТОЛЬКО для системных агрегатов, требует Javadoc-обоснования
 * (ArchUnit-контракт #16).
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AccessChecked {

    boolean strict() default true;
}
