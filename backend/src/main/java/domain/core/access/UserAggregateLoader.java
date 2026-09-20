package domain.core.access;

import domain.core.ddd.AggregateReference;
import domain.core.ddd.UserAggregate;

import java.util.Optional;

/**
 * Application-specific «как загрузить UserAggregate по AggregateReference».
 *
 * <p>Реализуется конкретным приложением (демо- или прод-сервисом) через инжекцию
 * соответствующего репозитория. Дефолтная реализация в этом core-модуле НЕ
 * предоставляется — приложение обязано предоставить bean.
 *
 * <p>В демо-приложении реализация делает {@code userRepo.findById(...)}; в production'е —
 * аналогично.
 */
public interface UserAggregateLoader {

    Optional<? extends UserAggregate<?>> findByRef(AggregateReference<? extends UserAggregate<?>, ?> ref);
}
