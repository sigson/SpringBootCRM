package app.springbootcrm.auth;

import app.springbootcrm.user.User;
import app.springbootcrm.user.UserDto;
import domain.core.web.ErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST API аутентификации.
 *
 * <p>Две бизнес-ошибки ({@link AuthService.BadCredentialsException} — 401 и
 * {@link AuthService.UsernameAlreadyExistsException} — 409) обрабатываются здесь
 * собственными handler'ами: глобальный {@code ErrorEnvelopeAdvice} отобразил бы
 * их как 500. Остальное (валидация через {@code @Valid}, прочие исключения)
 * обрабатывает глобальный advice.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final JwtTokenService tokens;
    private final JwtProperties jwtProps;

    public AuthController(AuthService auth, JwtTokenService tokens, JwtProperties jwtProps) {
        this.auth = auth;
        this.tokens = tokens;
        this.jwtProps = jwtProps;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest req,
                                                     HttpServletResponse resp) {
        User u = auth.authenticate(req.username(), req.password());
        String token = tokens.issue(u.getId(), u.getUsername());
        setAuthCookie(resp, token);
        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", UserDto.of(u)
        ));
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req,
                                                        HttpServletResponse resp) {
        User u = auth.register(req.username(), req.email(), req.displayName(), req.password());
        String token = tokens.issue(u.getId(), u.getUsername());
        setAuthCookie(resp, token);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "token", token,
                "user", UserDto.of(u)
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse resp) {
        clearAuthCookie(resp);
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    // -------- Собственные exception handlers (специфические HTTP-коды) --------

    @ExceptionHandler(AuthService.BadCredentialsException.class)
    public ResponseEntity<ErrorEnvelope> handleBadCreds(
            AuthService.BadCredentialsException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorEnvelope.unauthorized(e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(AuthService.UsernameAlreadyExistsException.class)
    public ResponseEntity<ErrorEnvelope> handleConflict(
            AuthService.UsernameAlreadyExistsException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorEnvelope.conflict(e.getMessage(), req.getRequestURI()));
    }

    // -------- helpers --------

    private void setAuthCookie(HttpServletResponse resp, String token) {
        ResponseCookie c = ResponseCookie.from(jwtProps.getCookieName(), token)
                .httpOnly(true)
                .secure(jwtProps.isCookieSecure())
                .path("/")
                .maxAge(jwtProps.getTtlSeconds())
                .sameSite(jwtProps.getCookieSameSite())
                .build();
        resp.addHeader(HttpHeaders.SET_COOKIE, c.toString());
    }

    private void clearAuthCookie(HttpServletResponse resp) {
        ResponseCookie c = ResponseCookie.from(jwtProps.getCookieName(), "")
                .httpOnly(true)
                .secure(jwtProps.isCookieSecure())
                .path("/")
                .maxAge(0)
                .sameSite(jwtProps.getCookieSameSite())
                .build();
        resp.addHeader(HttpHeaders.SET_COOKIE, c.toString());
    }

    // -------- DTO --------

    public record LoginRequest(
            @NotBlank(message = "Username is required") String username,
            @NotBlank(message = "Password is required") String password) {}

    public record RegisterRequest(
            @NotBlank(message = "Username is required")
            @Size(min = 3, max = 100, message = "Username: 3-100 characters") String username,
            @Email(message = "Invalid email format") String email,
            @Size(max = 200) String displayName,
            @NotBlank(message = "Password is required")
            @Size(min = 6, max = 200, message = "Password: at least 6 characters") String password) {}
}
