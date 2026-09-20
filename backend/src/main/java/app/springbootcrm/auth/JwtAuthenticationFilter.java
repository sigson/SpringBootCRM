package app.springbootcrm.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Парсит JWT из:
 * <ol>
 *   <li>cookie {@code springbootcrm_token} — основной способ для веб-клиента;</li>
 *   <li>заголовка {@code Authorization: Bearer ...} — для API-клиентов / curl.</li>
 * </ol>
 *
 * <p>При успехе кладёт в {@link SecurityContextHolder} {@link CrmAuthentication} —
 * в свою очередь {@code AccessContextFilter} из {@code domain.core.web} прочитает его,
 * построит {@code AccessContext} и забиндит на ThreadLocal.
 *
 * <p><b>Регистрация:</b> этот фильтр вставляется ВНУТРЬ Spring-Security'шной цепочки
 * через {@code http.addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)}
 * — см. {@link SecurityConfig}. Авто-регистрация как обычного servlet-filter'а отключена
 * через {@code FilterRegistrationBean.setEnabled(false)}, иначе {@code SecurityContextHolderFilter}
 * Spring Security 6 затирал бы выставленный нами {@code Authentication} «deferred context'ом».
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService tokens;
    private final JwtProperties props;

    public JwtAuthenticationFilter(JwtTokenService tokens, JwtProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(req);
        if (token == null) {
            chain.doFilter(req, resp);
            return;
        }
        Claims claims = tokens.parseOrNull(token);
        if (claims == null) {
            // Невалидный токен — обнулим cookie (защита от «битых» сессий)
            chain.doFilter(req, resp);
            return;
        }

        Map<String, Object> details = new HashMap<>();
        // claim'ы для access-фильтрации (см. domain.core.access.UserClaim).
        details.put("typeId", claims.get("typeId"));
        details.put("username", claims.get("username"));
        // SELF_IDS — UUID самого пользователя. Используется row-level фильтрами
        // для агрегатов с per-user owner'ом (например, Activity).
        details.put("selfIds", java.util.List.of(claims.getSubject()));

        CrmAuthentication auth = new CrmAuthentication(
                claims.getSubject(), claims.get("username", String.class), details,
                Collections.<GrantedAuthority>singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            chain.doFilter(req, resp);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String extractToken(HttpServletRequest req) {
        // 1) cookie
        Cookie[] cs = req.getCookies();
        if (cs != null) {
            for (Cookie c : cs) {
                if (props.getCookieName().equals(c.getName()) && c.getValue() != null
                        && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        // 2) Authorization header
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            return h.substring(7).trim();
        }
        return null;
    }

    /**
     * {@code Authentication}-обёртка над JWT.
     * Principal — UUID пользователя (String — для совместимости с {@code PrincipalRefResolver}).
     * Details — Map с claim'ами (читается {@code DefaultClaimsExtractor}'ом core'а).
     */
    public static final class CrmAuthentication extends AbstractAuthenticationToken {

        private final String subject;        // UUID
        private final String username;
        private final Map<String, Object> claims;

        public CrmAuthentication(String subject, String username,
                                   Map<String, Object> claims,
                                   Collection<? extends GrantedAuthority> authorities) {
            super(authorities);
            this.subject = subject;
            this.username = username;
            this.claims = claims;
        }

        @Override public Object getCredentials() { return null; }
        @Override public Object getPrincipal()   { return subject; }
        @Override public Object getDetails()     { return claims; }
        @Override public String getName()        { return subject; }
        public String getUsername()              { return username; }
    }
}
