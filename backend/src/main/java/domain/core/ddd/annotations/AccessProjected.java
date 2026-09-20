package domain.core.ddd.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Применяется на REST-методы; {@code AccessProjectionAdvice} перехватывает ответ
 * и оборачивает в envelope с {@code _access} и {@code _accessOverrides}.
 *
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AccessProjected {
}
