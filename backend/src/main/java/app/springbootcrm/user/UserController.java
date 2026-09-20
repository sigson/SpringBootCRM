package app.springbootcrm.user;

import app.springbootcrm.access.AccessRoleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST API над пользователями.
 *
 * <p>Собственных {@code @ExceptionHandler}'ов нет — глобальный {@code ErrorEnvelopeAdvice}
 * обрабатывает всё ({@code @Valid} → VALIDATION envelope, {@code AccessDenied} → ACCESS_DENIED
 * envelope с {@code requirements} и т.д.): единый формат ошибок доступа и валидации.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService users;
    private final AccessRoleService roles;

    public UserController(UserService users, AccessRoleService roles) {
        this.users = users;
        this.roles = roles;
    }

    @GetMapping
    public List<UserDto> list() { return users.list(); }

    @GetMapping("/me")
    public UserDto me() { return users.me(); }

    @GetMapping("/{id}")
    public UserDto get(@PathVariable UUID id) { return users.get(id); }

    @PostMapping
    public ResponseEntity<UserDto> create(@Valid @RequestBody CreateRequest req) {
        UserDto dto = users.createUser(new UserService.CreateUserRequest(
                req.code, req.name, req.username, req.email,
                req.displayName, req.password, req.enabled));
        // Одна роль через ссылочное поле. Назначаем после создания
        // (AccessRoleService материализует access из шаблона роли).
        if (req.roleId != null) {
            roles.setRoleForUser(dto.id(), req.roleId);
            dto = users.get(dto.id());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/{id}")
    public UserDto updateAdmin(@PathVariable UUID id, @Valid @RequestBody UpdateRequest req) {
        UserDto dto = users.updateUser(id, new UserService.UpdateUserRequest(
                req.name, req.email, req.displayName, req.password, req.enabled));
        // Роль меняется отдельной операцией, т.к. живёт в собственном агрегате прав.
        // null roleId в PUT трактуем как «снять роль» только если клиент явно передал
        // поле; чтобы избежать случайного сброса — используем флаг roleProvided
        // (Boolean в request: null = не трогать).
        if (Boolean.TRUE.equals(req.roleProvided)) {
            roles.setRoleForUser(id, req.roleId);   // roleId == null → снять роль
            dto = users.get(id);
        }
        if (Boolean.TRUE.equals(req.interfaceLayoutProvided)) {
            users.setInterfaceLayout(id, req.interfaceLayoutId);   // null → снять интерфейс
            dto = users.get(id);
        }
        return dto;
    }

    @PatchMapping("/me")
    public UserDto updateSelf(@Valid @RequestBody SelfUpdateRequest req) {
        return users.updateSelf(new UserService.SelfUpdateRequest(
                req.email, req.displayName, req.password));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        users.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    // -------- Request-records --------

    // Generic-редактор шлёт плоское тело по метаданным; оно может содержать ключи
    // вне этого контракта (например, interfaceLayoutId — задаётся только при
    // обновлении). Игнорируем неизвестные свойства, чтобы не падать на 400
    // (forward-совместимость).
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateRequest(
            @Size(max = 50) String code,
            @Size(max = 200) String name,
            @NotBlank(message = "Username is required")
            @Size(min = 3, max = 100, message = "Username: 3-100 characters") String username,
            @Email(message = "Invalid email format") String email,
            @Size(max = 200) String displayName,
            @NotBlank(message = "Password is required")
            @Size(min = 6, max = 200, message = "Password: 6-200 characters") String password,
            boolean enabled,
            /** Одна роль доступа (nullable — без роли). */
            UUID roleId) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateRequest(
            @Size(max = 200) String name,
            @Email(message = "Invalid email format") String email,
            @Size(max = 200) String displayName,
            @Size(min = 6, max = 200, message = "Password: 6-200 characters") String password,
            Boolean enabled,
            /** Новое значение роли (nullable = снять роль, если {@code roleProvided=true}). */
            UUID roleId,
            /** {@code true} — обновить роль (включая снятие); {@code null}/false — не трогать. */
            Boolean roleProvided,
            /** Новое значение интерфейса (nullable = снять, если {@code interfaceLayoutProvided=true}). */
            UUID interfaceLayoutId,
            /** {@code true} — обновить назначенный интерфейс; {@code null}/false — не трогать. */
            Boolean interfaceLayoutProvided) {}

    public record SelfUpdateRequest(
            @Email(message = "Invalid email format") String email,
            @Size(max = 200) String displayName,
            @Size(min = 6, max = 200, message = "Password: 6-200 characters") String password) {}
}
