package domain.core.access;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * Двухфазный детерминированный hash для cache-ключей.
 *
 * <ol>
 *   <li>{@link #lightHash(Authentication)} — только JWT (sub + authorities + claims).
 *       Стабильный per-token. Используется для lookup'а {@link AccessMetric}.</li>
 *   <li>{@link #fullHash(Authentication, AccessMetric)} — JWT + {@code metric.payload().hashCode()}.
 *       Меняется при {@code grantRole}/{@code grantTypeFlags}/{@code grantGlobalFlags} —
 *       cache-ключи инвалидируются автоматически.</li>
 * </ol>
 *
 * <p>В {@code lightHash} НЕ включены {@code exp}/{@code iat}/{@code jti} — иначе каждый
 * refresh ломает кеш.
 */
@Component
public class AccessKeyHasher {

    public String lightHash(Authentication auth) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            updateJwtPart(md, auth);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String fullHash(Authentication auth, AccessMetric metric) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            updateJwtPart(md, auth);
            md.update((byte) 2);
            int payloadHash = (metric == null) ? 0 : metric.payload().hashCode();
            md.update(Integer.toString(payloadHash).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void updateJwtPart(MessageDigest md, Authentication auth) {
        String subject = extractSubject(auth);
        md.update(subject.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        for (String a : authoritiesSorted(auth)) {
            md.update(a.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
        }
        md.update((byte) 1);
        for (String c : claimsSorted(auth)) {
            md.update(c.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
        }
    }

    private String extractSubject(Authentication auth) {
        if (auth.getPrincipal() instanceof Jwt jwt) return jwt.getSubject();
        return String.valueOf(auth.getPrincipal());
    }

    private List<String> authoritiesSorted(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
    }

    private List<String> claimsSorted(Authentication auth) {
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return List.of();
        List<String> out = new ArrayList<>();
        for (UserClaim c : UserClaim.values()) {
            Object v = jwt.getClaim(c.jwtName);
            if (v instanceof Collection<?> col) {
                col.stream().map(String::valueOf).sorted()
                        .forEach(s -> out.add(c.name() + "=" + s));
            } else if (v != null) {
                out.add(c.name() + "=" + v);
            }
        }
        out.sort(String::compareTo);
        return out;
    }
}
