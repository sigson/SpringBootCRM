package domain.core.access;

import domain.core.ddd.UserAggregate;

/**
 * Application-specific provider, сообщающий core'у конкретный класс {@link UserAggregate}.
 *
 * <p>Реализуется приложением. Нужен для {@link SystemAccessContexts} (создание системного
 * principalRef'а) и для {@code PrincipalRefResolver}'а (типизация ref'а).
 *
 * <p>Демо-приложение реализует этот bean возвращая свой {@code DemoUser.class}.
 */
public interface UserAggregateClassProvider {

    Class<? extends UserAggregate<?>> userClass();
}
