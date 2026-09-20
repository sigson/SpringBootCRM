package domain.core.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Числовое ограничение диапазона: {@code min ≤ value ≤ max}.
 *
 * <p>Например, «больше 0.1 и меньше 1000»:
 * {@code @NumberRange(min = 0.1, max = 1000, minInclusive = false, maxInclusive = false)}.
 *
 * <p>Доп-информация ({@code min}/{@code max}/inclusive) попадает в {@code meta}
 * результата валидации — фронтенд показывает её в оверлей-панели над полем.
 *
 * <p>Пустое значение пропускается (обязательность — через {@link Required}).
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NumberRange {

    double min() default Double.NEGATIVE_INFINITY;

    double max() default Double.POSITIVE_INFINITY;

    /** Включать ли нижнюю границу ({@code >=} vs {@code >}). */
    boolean minInclusive() default true;

    /** Включать ли верхнюю границу ({@code <=} vs {@code <}). */
    boolean maxInclusive() default true;

    /** Кастомное сообщение; пустое — генерируется автоматически из границ. */
    String message() default "";
}
