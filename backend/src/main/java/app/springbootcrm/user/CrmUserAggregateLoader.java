package app.springbootcrm.user;

import domain.core.access.UserAggregateLoader;
import domain.core.ddd.AggregateReference;
import domain.core.ddd.IdCodec;
import domain.core.ddd.UserAggregate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Резолвер {@link UserAggregate} по {@link AggregateReference}.
 *
 * <p>Используется в {@code CaffeineUserAccessProvider.loadFromDb} — когда в request'е
 * приходит JWT, core'у нужно подгрузить {@code AccessMetric} пользователя из БД
 * (один раз; потом всё кешируется на 5 минут в Caffeine).
 *
 * <p>Этот метод вызывается уже внутри {@code SystemAccessContexts.systemReadOnlyForType(9001)},
 * установленного {@code CaffeineUserAccessProvider}'ом — обычные access-проверки на User'е
 * не активируются (иначе была бы рекурсия).
 */
@Component
public class CrmUserAggregateLoader implements UserAggregateLoader {

    private final UserRepository users;

    public CrmUserAggregateLoader(UserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<? extends UserAggregate<?>> findByRef(
            AggregateReference<? extends UserAggregate<?>, ?> ref) {
        if (ref == null) return Optional.empty();
        try {
            UUID id = IdCodec.decode(ref.targetIdRaw(), UUID.class);
            return users.findById(id);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
