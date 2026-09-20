package domain.core.ddd.annotations;

import domain.core.access.DefaultAccess;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Маркирует поле агрегата (или {@code @Embeddable}-элемента) стабильным числовым ID.
 *
 * <p>{@code value()} — глобально-уникальный ID поля в пределах агрегата
 * (включая поля внутри {@code @Embeddable} рекурсивно). Уникальность валидируется
 * {@code MetadataBootstrapper}'ом — fail-fast при коллизии.
 *
 * <p>{@code defaultAccess()} — базовый уровень видимости поля. Дефолт —
 * {@link DefaultAccess#HIDDEN} (deny-by-default).
 *
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FieldId {

    /** Глобально-уникальный неизменяемый ID поля (включая поля внутри {@code @Embeddable}). */
    long value();

    DefaultAccess defaultAccess() default DefaultAccess.HIDDEN;

    Class<?>[] groups() default {};
}
