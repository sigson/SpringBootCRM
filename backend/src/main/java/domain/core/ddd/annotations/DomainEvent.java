package domain.core.ddd.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Стабильное имя доменного события для outbox/Kafka header'ов.
 *
 * <p>{@code stableName} должен содержать версию: {@code "OrderPlaced.v1"},
 * {@code "ChargeRequested.v2"}. Уникальность валидируется на bootstrap'е через
 * {@code DomainEventRegistry}.
 *
 * <p>{@code Class.forName(eventType)} — refactoring-hostile: при переносе класса
 * события в другой пакет старые сообщения в Kafka не десериализуются. Поэтому
 * outbox'ом и SCS-консьюмерами используется {@code stableName} вместо FQN.
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DomainEvent {

    String stableName();

    int version() default 1;
}
