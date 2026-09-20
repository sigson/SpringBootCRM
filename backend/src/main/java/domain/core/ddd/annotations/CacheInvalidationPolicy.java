package domain.core.ddd.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Политика инвалидации кеша при изменении агрегата.
 *
 * <p>{@code async=true} (дефолт): инвалидация Redis в фоне; отказ Redis НЕ блокирует commit.
 * <p>{@code async=false}: синхронная инвалидация — для критичных read-after-write сценариев.
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheInvalidationPolicy {

    boolean async() default true;

    int maxLagMs() default 100;
}
