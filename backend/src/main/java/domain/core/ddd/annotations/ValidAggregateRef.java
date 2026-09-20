package domain.core.ddd.annotations;

import app.springbootcrm.user.User;

import domain.core.ddd.AbstractAggregate;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.io.Serializable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Валидация {@code AggregateReference<T,ID>}-поля.
 *
 * <p>{@link #targets()} — допустимые целевые типы: один класс для моно-ссылки, несколько
 * для union-ссылки ({@code targets = {User.class, Organization.class}}). Конкретный тип
 * выбирается в рантайме (хранится в {@code targetTypeId}).
 *
 * <p>{@code targets()} и {@code idType()} обязательны. {@code MetadataBootstrapper}
 * на старте fail-fast проверяет: каждый {@code targets[i]} — {@code @TypeId @Entity};
 * {@code idType} одинаков у всех целей (иначе непредставимо в двух колонках); Hibernate
 * {@code @Type} согласован с {@code idType}. В рантайме {@code ValidAggregateRefValidator}
 * проверяет, что {@code targetTypeId} входит в множество, а {@code targetIdRaw} декодируем.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidAggregateRefValidator.class)
public @interface ValidAggregateRef {

    /**
     * Допустимые целевые типы. Один элемент — обычная (моно) ссылка; несколько —
     * union-ссылка. Должен быть указан хотя бы один.
     */
    Class<? extends AbstractAggregate<?>>[] targets();

    Class<? extends Serializable> idType();

    String message() default "invalid aggregate reference";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
