package domain.core.ddd.annotations;

import domain.core.ddd.AbstractAggregate;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает агрегат как <b>табличную часть</b> другого агрегата и декларирует тип владельца.
 * Ставится на {@code @Entity}, наследующий {@code AbstractTabularPart}; каждая строка несёт
 * встроенную ссылку {@code ownerRef} (id + typeId) на экземпляр-владелец.
 *
 * <p>{@code owner()} — класс владельца. {@code MetadataBootstrapper} fail-fast проверяет,
 * что он {@code @TypeId @Entity}, и сохраняет связь {@code typeId(ТЧ) -> typeId(владелец)} в
 * {@code MetadataSnapshot}. По этой связи {@code AccessResolver} наследует права ТЧ от
 * владельца (см. {@code AbstractTabularPart}).
 *
 * <p>{@code ownerField()} — имя property-ссылки на владельца; по умолчанию {@code "ownerRef"}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TabularPart {

    /** Тип агрегата-владельца (должен быть зарегистрирован как {@code @TypeId @Entity}). */
    Class<? extends AbstractAggregate<?>> owner();

    /** Имя property-ссылки на владельца в строке ТЧ. */
    String ownerField() default "ownerRef";
}
