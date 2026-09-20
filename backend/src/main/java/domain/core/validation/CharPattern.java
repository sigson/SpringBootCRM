package domain.core.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ограничение по регулярному выражению (допустимые символы / формат).
 *
 * <p>Пустое значение пропускается (обязательность — через {@link Required}).
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CharPattern {

    /** Java-регулярка; значение должно полностью ей соответствовать ({@code matches}). */
    String regex();

    String message() default "Недопустимий формат значення";
}
