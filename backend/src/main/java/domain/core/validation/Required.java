package domain.core.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Декларативное ограничение «поле обязательно для заполнения».
 *
 * <p>Часть единой системы валидации (см. {@link FieldValidationRegistry}):
 * ограничения вешаются прямо на поле {@code Entity}, реестр собирает их по
 * {@code (typeId, имя реквизита)}, а {@link ValidationService} применяет — как
 * для интерактивной проверки одного поля, так и для полной проверки объекта.
 *
 * <p>Пустое значение (null / пустая строка / пробелы) считается незаполненным.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Required {

    /** Сообщение об ошибке (показывается рядом с полем). */
    String message() default "Поле обов'язкове для заповнення";
}
