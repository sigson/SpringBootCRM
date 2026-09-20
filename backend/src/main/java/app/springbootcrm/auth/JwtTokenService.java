package app.springbootcrm.auth;

import app.springbootcrm.user.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Выпуск и верификация JWT (jjwt 0.12.x API).
 *
 * <p>Claims:
 * <ul>
 *   <li>{@code sub} — UUID пользователя (String);</li>
 *   <li>{@code username} — login;</li>
 *   <li>{@code typeId} — typeId агрегата User (9001), нужно core'у для построения principalRef;</li>
 *   <li>{@code iss}, {@code iat}, {@code exp} — стандартные.</li>
 * </ul>
 */
@Component
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    private final JwtProperties props;
    private final SecretKey signingKey;

    public JwtTokenService(JwtProperties props) {
        this.props = props;
        byte[] keyBytes = Base64.getDecoder().decode(props.getSecret());
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT secret too short: need >= 32 bytes after base64-decode " +
                    "(generate via `openssl rand -base64 64`)");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /** Выпускает токен для авторизованного пользователя. */
    public String issue(UUID userId, String username) {
        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofSeconds(props.getTtlSeconds()));

        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);
        claims.put("typeId", 9001L);    // User.TYPE_ID, но без cyclic-import

        return Jwts.builder()
                .issuer(props.getIssuer())
                .subject(userId.toString())
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(exp))
                .claims(claims)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Проверяет токен и возвращает извлечённые claims.
     * @return {@code null} если токен невалиден (подпись, exp, iss и т.п.).
     */
    public Claims parseOrNull(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(props.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return null;
        }
    }
}
