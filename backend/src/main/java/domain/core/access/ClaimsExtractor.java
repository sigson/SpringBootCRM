package domain.core.access;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Извлекает значения JWT-claim'а (например, "orgIds") из {@link Authentication}.
 * Используется в {@code AccessFilterActivator} и {@code PostLoadAccessCheckListener}.
 *
 * <p>Дефолтная реализация умеет:
 * <ul>
 *   <li>извлекать из {@link Jwt}-principal'а (production-сценарий с OAuth2);</li>
 *   <li>извлекать из {@link Authentication#getDetails()} если это {@link Map}
 *       (демо-сценарий со stub'ом без реального JWT).</li>
 * </ul>
 */
public interface ClaimsExtractor {

    Set<String> extract(Authentication auth, String claimName);

    @Component
    class DefaultClaimsExtractor implements ClaimsExtractor {

        @Override
        public Set<String> extract(Authentication auth, String claimName) {
            if (auth == null) return Set.of();
            Object claimsSource;
            if (auth.getPrincipal() instanceof Jwt jwt) {
                claimsSource = jwt.getClaim(claimName);
            } else if (auth.getDetails() instanceof Map<?, ?> m) {
                claimsSource = m.get(claimName);
            } else {
                return Set.of();
            }
            return toStringSet(claimsSource);
        }

        private Set<String> toStringSet(Object src) {
            if (src == null) return Set.of();
            if (src instanceof Collection<?> col) {
                Set<String> out = new HashSet<>(col.size());
                for (Object v : col) {
                    if (v != null) out.add(String.valueOf(v));
                }
                return Set.copyOf(out);
            }
            return Set.of(String.valueOf(src));
        }
    }
}
