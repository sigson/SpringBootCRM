package domain.core.access;

import domain.core.bootstrap.MetadataSnapshot;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.AggregateReference;
import domain.core.ddd.UserAggregate;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

/**
 * Обогащённый контекст пользователя — явная структура, передаваемая через явные API
 * (НЕ через {@code SecurityContextHolder}, который не работает в @Async/VT/Reactor).
 *
 * <p>{@code accessKeyHash} — детерминированный хэш для cache-ключей; меняется при
 * grant'е (за счёт включения {@code AccessMetricPayload.hashCode()} в full-hash).
 *
 */
public record AccessContext(
        Authentication auth,
        AggregateReference<? extends UserAggregate<?>, ?> principalRef,
        AccessResolver resolver,
        MetadataSnapshot snapshot,
        ProjectionDirection direction,
        String accessKeyHash) {

    public static final String SYSTEM_PRINCIPAL_ID = "__SYSTEM__";

    public AccessLevel resolve(long typeId, long fieldId, @Nullable AbstractAggregate<?> i) {
        return resolver.resolve(typeId, fieldId, i, this);
    }

    public AccessLevel resolveRepository(long typeId) {
        return resolver.resolveRepository(typeId, this);
    }

    public boolean isSystem() {
        return principalRef != null && SYSTEM_PRINCIPAL_ID.equals(principalRef.targetIdRaw());
    }

    public boolean isSystemMaxPrivileged() {
        if (!isSystem()) return false;
        return auth instanceof SystemAuthentication sa && sa.isMaxPrivileged();
    }

    public boolean isBootstrapForType(long typeId) {
        return auth instanceof SystemAuthentication sa && sa.isBootstrapFor(typeId);
    }
}
