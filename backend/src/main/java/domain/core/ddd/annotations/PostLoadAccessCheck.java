package domain.core.ddd.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Включает {@code PostLoadAccessCheckListener} для агрегата (или его репозитория).
 *
 * <p>Поведение по умолчанию для {@code @AccessFiltered}-агрегатов:
 * <ul>
 *   <li>аннотация НЕ проставлена → подразумевается ВКЛЮЧЕНО (управляется
 *       глобальным toggle {@code app.ddd.access.implicit-post-load-check}, default true);</li>
 *   <li>явное {@code @PostLoadAccessCheck(false)} → ОТКЛЮЧЕНО (требует Javadoc-обоснования);</li>
 *   <li>явное {@code @PostLoadAccessCheck} → ВКЛЮЧЕНО.</li>
 * </ul>
 *
 * <p>Listener имеет два режима через {@code PostLoadModeHolder}:
 * <ul>
 *   <li>{@code THROW} для single-row контрактов ({@code findById}, {@code findByIdLocked}, ...);</li>
 *   <li>{@code MARK_AND_DROP} для коллекций ({@code findAll}, {@code Page<T>}, ...).</li>
 * </ul>
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PostLoadAccessCheck {

    boolean value() default true;
}
