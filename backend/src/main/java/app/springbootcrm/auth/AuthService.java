package app.springbootcrm.auth;

import app.springbootcrm.reference.CodeGenerator;
import app.springbootcrm.user.User;
import app.springbootcrm.user.UserRepository;
import domain.core.access.AccessContextHolder;
import domain.core.access.AccessMetric;
import domain.core.access.SystemAccessContexts;
import domain.core.web.ValidationFailedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.UUID;

/**
 * Бизнес-логика аутентификации.
 *
 * <p>{@code register} выделяет справочный код через {@link CodeGenerator} и
 * проставляет {@code name}.
 *
 * <p>Собственные exception-классы {@link BadCredentialsException} и
 * {@link UsernameAlreadyExistsException} логически различны (401 vs 409); чтобы
 * глобальный {@code ErrorEnvelopeAdvice} не отдавал их как 500, в
 * {@code AuthController} есть отдельные {@code @ExceptionHandler}'ы.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AccessContextHolder holder;
    private final SystemAccessContexts systems;
    private final CodeGenerator codeGen;

    public AuthService(UserRepository users, PasswordEncoder encoder,
                       AccessContextHolder holder, SystemAccessContexts systems,
                       CodeGenerator codeGen) {
        this.users = users;
        this.encoder = encoder;
        this.holder = holder;
        this.systems = systems;
        this.codeGen = codeGen;
    }

    @Transactional(readOnly = true)
    public User authenticate(String username, String rawPassword) {
        try (var ignored = holder.bind(systems.maxPrivileges())) {
            User u = users.findByUsername(username)
                    .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));
            if (!u.isEnabled()) {
                throw new BadCredentialsException("This user is disabled");
            }
            if (!encoder.matches(rawPassword, u.getPasswordHash())) {
                throw new BadCredentialsException("Invalid username or password");
            }
            return u;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public User register(String username, String email, String displayName, String rawPassword) {
        try (var ignored = holder.bind(systems.maxPrivileges())) {
            if (users.findByUsername(username).isPresent()) {
                throw new UsernameAlreadyExistsException(
                        "A user with the username \u00ab" + username + "\u00bb already exists");
            }
            // CodeGenerator вызывается внутри try-with-resources, но сам bumpAndGet
            // выполняется в REQUIRES_NEW-транзакции с собственным commit'ом — это
            // безопасно под systemMaxPrivileges (новая транзакция не наследует
            // AccessContext, но CodeGenerator и не вызывает access-aware listener'ы).
            String code = codeGen.nextFor(User.class, c -> users.findByCode(c).isEmpty());
            String name = (displayName != null && !displayName.isBlank())
                    ? displayName.trim() : username;
            User u = new User(
                    UUID.randomUUID(),
                    code, name,
                    username,
                    email,
                    displayName,
                    encoder.encode(rawPassword),
                    true,
                    AccessMetric.empty()
            );
            return users.saveAndFlush(u);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // -------- Исключения, которые контроллер транслирует в HTTP-коды --------

    public static class BadCredentialsException extends RuntimeException {
        public BadCredentialsException(String m) { super(m); }
    }

    public static class UsernameAlreadyExistsException extends RuntimeException {
        public UsernameAlreadyExistsException(String m) { super(m); }
    }
}
