package app.springbootcrm.auth;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Конфигурация JWT — связана с {@code app.auth.jwt.*} в {@code application.yml}.
 */
@ConfigurationProperties(prefix = "app.auth.jwt")
@Validated
public class JwtProperties {

    /** Base64-encoded HMAC ключ (минимум 32 байта, рекомендация — 64). */
    @NotBlank
    private String secret;

    /** issuer для проверки {@code iss}-claim'а. */
    @NotBlank
    private String issuer = "springbootcrm";

    /** Время жизни токена, в секундах. */
    @Min(60)
    private long ttlSeconds = 28_800;

    /** Имя cookie, в которое мы кладём JWT (помимо Authorization-header'а). */
    @NotBlank
    private String cookieName = "springbootcrm_token";

    /** {@code Secure} flag для cookie (true в prod'е, false в dev'е). */
    private boolean cookieSecure = false;

    /** {@code SameSite} для cookie. */
    @NotBlank
    private String cookieSameSite = "Lax";

    public String getSecret()         { return secret; }
    public void setSecret(String s)   { this.secret = s; }
    public String getIssuer()         { return issuer; }
    public void setIssuer(String i)   { this.issuer = i; }
    public long getTtlSeconds()       { return ttlSeconds; }
    public void setTtlSeconds(long t) { this.ttlSeconds = t; }
    public String getCookieName()     { return cookieName; }
    public void setCookieName(String c){ this.cookieName = c; }
    public boolean isCookieSecure()   { return cookieSecure; }
    public void setCookieSecure(boolean s) { this.cookieSecure = s; }
    public String getCookieSameSite() { return cookieSameSite; }
    public void setCookieSameSite(String s) { this.cookieSameSite = s; }
}
