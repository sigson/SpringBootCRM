package app.springbootcrm.access;

import app.springbootcrm.reference.Reference;

import app.springbootcrm.auth.AdminCheck;
import app.springbootcrm.reference.CodeGenerator;
import app.springbootcrm.user.User;
import app.springbootcrm.user.UserRepository;
import domain.core.access.AccessMetric;
import domain.core.access.CaffeineUserAccessProvider;
import domain.core.web.ValidationFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * CRUD-сервис {@link AccessRole} + назначение/снятие ролей пользователям.
 *
 * <p>При создании роли без явного code код выделяется через {@link CodeGenerator}
 * (префикс «ROL», codeWidth=8 из {@code @Reference} на AccessRole). Ошибки
 * валидации — через {@link ValidationFailedException}.
 */
@Service
public class AccessRoleService {

    private final AccessRoleRepository roles;
    private final UserRepository users;
    private final AdminCheck admin;
    private final CaffeineUserAccessProvider userAccessCache;
    private final CodeGenerator codeGen;

    public AccessRoleService(AccessRoleRepository roles, UserRepository users,
                             AdminCheck admin,
                             CaffeineUserAccessProvider userAccessCache,
                             CodeGenerator codeGen) {
        this.roles = roles;
        this.users = users;
        this.admin = admin;
        this.userAccessCache = userAccessCache;
        this.codeGen = codeGen;
    }

    // -------- CRUD --------

    @Transactional(readOnly = true)
    public List<AccessRoleDto> list() {
        return roles.findAll().stream().map(AccessRoleDto::of).toList();
    }

    @Transactional(readOnly = true)
    public AccessRoleDto get(UUID id) {
        return AccessRoleDto.of(roles.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Role not found: " + id)));
    }

    @Transactional
    public AccessRoleDto create(CreateRoleRequest req) {
        admin.requireAdmin();
        if (req.name == null || req.name.isBlank()) {
            throw ValidationFailedException.ofField("name", "Role name is required");
        }
        String code = resolveCodeForCreate(req.code);
        AccessRole r = new AccessRole(
                UUID.randomUUID(),
                code,
                req.name,
                req.description,
                buildMetric(req.accessTemplate),
                req.enabled
        );
        return AccessRoleDto.of(roles.save(r));
    }

    @Transactional
    public AccessRoleDto update(UUID id, UpdateRoleRequest req) {
        admin.requireAdmin();
        AccessRole r = roles.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Role not found: " + id));
        if (req.name != null) {
            if (req.name.isBlank()) {
                throw ValidationFailedException.ofField("name", "Name is required");
            }
            r.setName(req.name);
        }
        if (req.description != null) r.setDescription(req.description);
        if (req.accessTemplate != null) r.setAccessTemplate(buildMetric(req.accessTemplate));
        if (req.enabled != null) r.setEnabled(req.enabled);
        AccessRole saved = roles.save(r);
        recomputeAccessForUsersWithRole(id);
        return AccessRoleDto.of(saved);
    }

    @Transactional
    public void delete(UUID id) {
        admin.requireAdmin();
        if (!roles.existsById(id)) {
            throw new NoSuchElementException("Role not found: " + id);
        }
        // Роль одна на пользователя: у всех пользователей с этой ролью снимаем её
        // (role → null) и пересчитываем access на пустой.
        for (User u : users.findByRoleId(id)) {
            applyRoleToUser(u, null);
        }
        roles.deleteById(id);
    }

    // -------- Назначение/снятие (одна роль на пользователя) --------

    /**
     * Назначить пользователю роль (ровно одну). {@code roleId == null} — снять роль.
     * Предыдущая роль полностью замещается новой.
     */
    @Transactional
    public void setRoleForUser(UUID userId, UUID roleId) {
        admin.requireAdmin();
        User u = users.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
        if (roleId != null && !roles.existsById(roleId)) {
            throw new NoSuchElementException("Role not found: " + roleId);
        }
        applyRoleToUser(u, roleId);
    }

    /** Снять роль с пользователя (role → null, access → empty). */
    @Transactional
    public void clearRole(UUID userId) {
        admin.requireAdmin();
        User u = users.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
        applyRoleToUser(u, null);
    }

    // -------- internals --------

    private String resolveCodeForCreate(String userSupplied) {
        if (userSupplied != null && !userSupplied.isBlank()) {
            String trimmed = userSupplied.trim();
            if (trimmed.length() > 50) {
                throw ValidationFailedException.ofField("code",
                        "Code must not exceed 50 characters");
            }
            if (roles.findByCode(trimmed).isPresent()) {
                throw ValidationFailedException.ofField("code",
                        "A role with this code already exists");
            }
            return trimmed;
        }
        return codeGen.nextFor(AccessRole.class, c -> roles.findByCode(c).isEmpty());
    }

    /**
     * Применяет роль к пользователю: проставляет ссылку {@code role} и
     * материализует {@code access} из шаблона роли. {@code roleId == null} либо
     * выключенная/несуществующая роль → {@code access = empty}.
     */
    private void applyRoleToUser(User user, UUID roleId) {
        AccessMetric merged = AccessMetric.empty();
        UUID effectiveRoleId = null;
        if (roleId != null) {
            AccessRole r = roles.findById(roleId).orElse(null);
            if (r != null && r.isEnabled()) {
                merged = r.getAccessTemplate();
                effectiveRoleId = roleId;
            } else if (r != null) {
                // Роль существует, но выключена — оставляем ссылку, права пустые.
                effectiveRoleId = roleId;
            }
        }
        user.setRoleId(effectiveRoleId);
        user.setAccess(merged);
        users.save(user);
        userAccessCache.evictUser(String.valueOf(User.TYPE_ID), user.getId().toString());
    }

    private void recomputeAccessForUsersWithRole(UUID roleId) {
        for (User u : users.findByRoleId(roleId)) {
            applyRoleToUser(u, roleId);
        }
    }

    private AccessMetric buildMetric(Map<String, Object> template) {
        if (template == null) return AccessMetric.empty();
        AccessMetric m = AccessMetric.empty();
        Object g = template.get("globalFlags");
        if (g instanceof Number n) m = m.withGlobalFlags(n.intValue());
        Object tflags = template.get("typeFlags");
        if (tflags instanceof Map<?,?> map) {
            for (var e : map.entrySet()) {
                long typeId = Long.parseLong(String.valueOf(e.getKey()));
                int flags = ((Number) e.getValue()).intValue();
                m = m.withTypeFlags(typeId, flags);
            }
        }
        return m;
    }

    // -------- Request records --------

    public record CreateRoleRequest(
            String code,
            String name,
            String description,
            Map<String, Object> accessTemplate,
            boolean enabled) {}

    public record UpdateRoleRequest(
            String name,
            String description,
            Map<String, Object> accessTemplate,
            Boolean enabled) {}
}
