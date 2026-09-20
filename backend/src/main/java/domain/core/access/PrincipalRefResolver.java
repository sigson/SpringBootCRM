package domain.core.access;

import domain.core.ddd.AggregateReference;
import domain.core.ddd.AggregateReferenceFactory;
import domain.core.ddd.UserAggregate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.UUID;

/** Резолвит principalRef из {@link Authentication}. */
public interface PrincipalRefResolver {

    AggregateReference<? extends UserAggregate<?>, ?> resolve(Authentication auth);

    /**
     * Дефолтная реализация: парсит JWT.sub как UUID. Подходит для большинства приложений
     * с UUID-ID для пользователей. Для других id-типов приложение предоставляет свой bean
     * @{link Primary}-помеченный.
     */
    @Component
    class JwtPrincipalRefResolver implements PrincipalRefResolver {

        private final AggregateReferenceFactory refs;
        private final UserAggregateClassProvider userClass;

        public JwtPrincipalRefResolver(AggregateReferenceFactory refs,
                                       UserAggregateClassProvider userClass) {
            this.refs = refs;
            this.userClass = userClass;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public AggregateReference<? extends UserAggregate<?>, ?> resolve(Authentication auth) {
            String sub = extractSubject(auth);
            Class uc = userClass.userClass();
            // Пытаемся UUID; если не получается — сохраняем как String (через ofRaw).
            try {
                UUID id = UUID.fromString(sub);
                return (AggregateReference) refs.of(uc, (Serializable) id);
            } catch (IllegalArgumentException e) {
                // String/Long-id — обращаемся через системную фабрику с raw-id
                return (AggregateReference) refs.system((Class) uc, sub);
            }
        }

        private String extractSubject(Authentication auth) {
            if (auth.getPrincipal() instanceof Jwt jwt) return jwt.getSubject();
            return String.valueOf(auth.getName() == null ? auth.getPrincipal() : auth.getName());
        }
    }
}
