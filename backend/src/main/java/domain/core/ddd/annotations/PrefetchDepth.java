package domain.core.ddd.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Конфигурируемая глубина batch-prefetch'а для {@code RefBatchPrefetcher}.
 *
 * <p>Default = 3. При превышении инкрементится метрика
 * {@code ddd.refprefetch.truncated{typeId}}.
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PrefetchDepth {

    int value() default 3;
}
