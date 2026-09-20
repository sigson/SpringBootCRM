package domain.core.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ограничение формата email. Пустое значение пропускается
 * (обязательность — через {@link Required}).
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EmailFormat {

    String message() default "Невірний формат email";
}
