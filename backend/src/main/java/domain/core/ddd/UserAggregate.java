package domain.core.ddd;

import app.springbootcrm.access.AccessRole;
import app.springbootcrm.access.AccessRoleService;

import domain.core.access.AccessMetric;

import java.io.Serializable;

/**
 * Маркерный супертип для всех агрегатов-пользователей; каждое приложение определяет свой
 * {@code MyUserAggregate extends UserAggregate<UUID>}. Используется в audit-ссылках
 * createdBy/updatedBy, в {@code UserAccessProvider.metricFor} и в {@code SystemAccessContexts}.
 *
 * <p>Конкретный класс должен быть {@code @TypeId}+{@code @Entity}, иметь поле
 * {@code AccessMetric access} с {@code @FieldId} и геттер {@link #getAccess()}.
 * Рекомендуется {@code @AggregateLockingPolicy(OPTIMISTIC_FORCE_INCREMENT)} —
 * сериализует concurrent grant'ы.
 */
public abstract class UserAggregate<ID extends Serializable> extends AbstractAuditedAggregate<ID> {

    /** Возвращает права пользователя, хранящиеся как поле агрегата. */
    public abstract AccessMetric getAccess();

    /**
     * Сеттер для grant-сервисов: {@code GrantService}/{@code AccessRoleService} собирают
     * итоговый AccessMetric как union шаблонов всех назначенных пользователю {@code AccessRole}.
     */
    public abstract void setAccess(AccessMetric access);
}
