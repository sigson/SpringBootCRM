package app.springbootcrm.user;

import domain.core.access.UserAggregateClassProvider;
import domain.core.ddd.UserAggregate;
import org.springframework.stereotype.Component;

/**
 * Сообщает domain-core'у, какой именно класс реализует {@link UserAggregate}.
 * Этот бин обязателен — {@code SystemAccessContexts} и {@code AggregateReferenceFactory}
 * используют его для построения principal'ов.
 */
@Component
public class CrmUserClassProvider implements UserAggregateClassProvider {

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Class<? extends UserAggregate<?>> userClass() {
        return (Class) User.class;
    }
}
