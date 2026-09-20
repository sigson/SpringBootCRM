package domain.core.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ограничение длины текстового реквизита: {@code min ≤ length ≤ max}.
 *
 * <p>Пустое значение пропускается (обязательность — отдельно через {@link Required}),
 * чтобы не показывать «слишком коротко» на ещё не заполненном поле.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TextLength {

    int min() default 0;

    int max() default Integer.MAX_VALUE;

    /** Кастомное сообщение; пустое — генерируется автоматически из min/max. */
    String message() default "";
}
