package app.springbootcrm.user;

import app.springbootcrm.auth.AdminCheck;
import app.springbootcrm.reference.CodeGenerator;
import domain.core.access.AccessContextHolder;
import domain.core.access.AccessMetric;
import domain.core.access.SystemAccessContexts;
import domain.core.web.ValidationFailedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Бизнес-логика над {@link User}.
 *
 * <p>Пользователи — полноценный справочник с code+name; коды выделяются при
 * создании через {@link CodeGenerator}.
 */
@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AdminCheck admin;
    private final AccessContextHolder holder;
    private final SystemAccessContexts systems;
    private final CodeGenerator codeGen;

    public UserService(UserRepository users, PasswordEncoder encoder,
                       AdminCheck admin, AccessContextHolder holder,
                       SystemAccessContexts systems,
                       CodeGenerator codeGen) {
        this.users = users;
        this.encoder = encoder;
        this.admin = admin;
        this.holder = holder;
        this.systems = systems;
        this.codeGen = codeGen;
    }

    // ============ READ ============

    @Transactional(readOnly = true)
    public List<UserDto> list() {
        boolean isAdmin = admin.isAdmin();
        UUID self = admin.currentUserIdOrNull();
        List<UserDto> out = new ArrayList<>();
        for (User u : users.findAll()) out.add(project(u, isAdmin, self));
        return out;
    }

    @Transactional(readOnly = true)
    public UserDto get(UUID id) {
        User u = users.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        return project(u, admin.isAdmin(), admin.currentUserIdOrNull());
    }

    /**
     * Полная проекция — самому себе и администратору, урезанная — всем остальным.
     * См. {@link UserDto} о том, какие реквизиты скрываются.
     */
    private UserDto project(User u, boolean isAdmin, UUID selfId) {
        boolean full = isAdmin || (selfId != null && selfId.equals(u.getId()));
        return full ? UserDto.of(u) : UserDto.basic(u);
    }

    @Transactional(readOnly = true)
    public UserDto me() {
        UUID id = admin.currentUserIdOrNull();
        if (id == null) {
            throw new NoSuchElementException("Could not determine the current user");
        }
        return get(id);
    }

    // ============ ADMIN-WRITE ============

    /**
     * Назначить/снять кастомизированный интерфейс пользователю.
     * {@code layoutId == null} — снять (вернуть дефолтный режим навигации).
     * Это простая ссылка-реквизит (в отличие от роли не материализует прав),
     * поэтому не требует пересчёта access или инвалидации кеша.
     */
    @Transactional
    public void setInterfaceLayout(UUID userId, UUID layoutId) {
        admin.requireAdmin();
        User u = users.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
        u.setInterfaceLayoutId(layoutId);
        users.save(u);
    }

    @Transactional
    public UserDto createUser(CreateUserRequest req) {
        admin.requireAdmin();
        if (req.username == null || req.username.isBlank()) {
            throw ValidationFailedException.ofField("username", "Username is required");
        }
        if (users.findByUsername(req.username).isPresent()) {
            throw ValidationFailedException.ofField("username",
                    "A user with the username \u00ab" + req.username + "\u00bb already exists");
        }
        String code = resolveCodeForCreate(req.code);
        String name = resolveNameForCreate(req.name, req.username, req.displayName);
        User u = new User(
                UUID.randomUUID(),
                code, name,
                req.username, req.email, req.displayName,
                encoder.encode(req.password), req.enabled,
                AccessMetric.empty()
        );
        return UserDto.of(users.save(u));
    }

    @Transactional
    public UserDto updateUser(UUID id, UpdateUserRequest req) {
        admin.requireAdmin();
        User u = users.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        if (req.name != null) {
            if (req.name.isBlank()) {
                throw ValidationFailedException.ofField("name", "Name is required");
            }
            u.setName(req.name.trim());
        }
        if (req.email != null) u.setEmail(req.email);
        if (req.displayName != null) u.setDisplayName(req.displayName);
        if (req.enabled != null) u.setEnabled(req.enabled);
        if (req.password != null && !req.password.isBlank()) {
            if (req.password.length() < 6) {
                throw ValidationFailedException.ofField("password",
                        "The password must contain at least 6 characters");
            }
            u.setPasswordHash(encoder.encode(req.password));
        }
        return UserDto.of(users.save(u));
    }

    @Transactional
    public void deleteUser(UUID id) {
        admin.requireAdmin();
        if (!users.existsById(id)) {
            throw new NoSuchElementException("User not found: " + id);
        }
        users.deleteById(id);
    }

    // ============ SELF-UPDATE ============

    @Transactional
    public UserDto updateSelf(SelfUpdateRequest req) {
        UUID id = admin.currentUserIdOrNull();
        if (id == null) throw new NoSuchElementException("Could not determine the current user");
        try (var ignored = holder.bind(systems.maxPrivileges())) {
            User u = users.findById(id)
                    .orElseThrow(() -> new NoSuchElementException("User not found"));
            if (req.email != null) u.setEmail(req.email);
            if (req.displayName != null) u.setDisplayName(req.displayName);
            if (req.password != null && !req.password.isBlank()) {
                if (req.password.length() < 6) {
                    throw ValidationFailedException.ofField("password",
                            "The password must contain at least 6 characters");
                }
                u.setPasswordHash(encoder.encode(req.password));
            }
            return UserDto.of(users.saveAndFlush(u));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ============ Helpers ============

    private String resolveCodeForCreate(String userSupplied) {
        if (userSupplied != null && !userSupplied.isBlank()) {
            String trimmed = userSupplied.trim();
            if (trimmed.length() > 50) {
                throw ValidationFailedException.ofField("code",
                        "Code must not exceed 50 characters");
            }
            if (users.findByCode(trimmed).isPresent()) {
                throw ValidationFailedException.ofField("code",
                        "A user with this code already exists");
            }
            return trimmed;
        }
        return codeGen.nextFor(User.class, c -> users.findByCode(c).isEmpty());
    }

    private String resolveNameForCreate(String name, String username, String displayName) {
        if (name != null && !name.isBlank()) return name.trim();
        if (displayName != null && !displayName.isBlank()) return displayName.trim();
        return username;
    }

    // ============ DTO-records ============

    public record CreateUserRequest(
            String code,
            String name,
            String username,
            String email,
            String displayName,
            String password,
            boolean enabled) {}

    public record UpdateUserRequest(
            String name,
            String email,
            String displayName,
            String password,
            Boolean enabled) {}

    public record SelfUpdateRequest(
            String email,
            String displayName,
            String password) {}
}
