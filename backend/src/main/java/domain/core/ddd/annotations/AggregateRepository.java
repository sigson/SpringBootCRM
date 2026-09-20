package domain.core.ddd.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Маркер для AOP-pointcut'а {@code AccessFilterActivator}'а.
 *
 * <p>Все репозитории-наследники {@code domain.core.persistence.AggregateRepository}
 * <b>обязаны</b> нести этот маркер. ArchUnit-правило:
 * {@code @AggregateRepository-marker проставлен на каждый AggregateRepository-наследник}.
 *
 * <p>AOP-pointcut в {@code AccessFilterActivator} использует этот маркер
 * либо тип-проверку {@code this(domain.core.persistence.AggregateRepository)} —
 * это работает независимо от пакета приложения.
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AggregateRepository {
}
