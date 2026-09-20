package app.springbootcrm.auth;

import domain.core.access.AccessContext;
import domain.core.access.AccessContextHolder;
import domain.core.access.AccessFlags;
import domain.core.access.AccessResolver;
import domain.core.access.PermissionRequirement;
import domain.core.access.StructuredAccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Утилита проверки admin/root-привилегий текущего пользователя.
 *
 * <p>{@code requireAdmin} и {@code requireAdminOrSelf} бросают
 * {@link StructuredAccessDeniedException} с описанием «нужен ADMIN_READ-флаг», и
 * фронтенд показывает конкретное требование в центральной модалке ошибок.
 *
 * <p>«Админ» определяется наличием {@code ADMIN_READ} (минимальный порог — флаги
 * {@code ADMIN_WRITE / ROOT_READ / ROOT_WRITE} раскрываются через
 * {@link AccessFlags#expand}).
 */
@Component
public class AdminCheck {

    private final AccessContextHolder holder;
    private final AccessResolver resolver;

    public AdminCheck(AccessContextHolder holder, AccessResolver resolver) {
        this.holder = holder;
        this.resolver = resolver;
    }

    /** True, если у текущего пользователя есть хоть какой-то admin/root-флаг. */
    public boolean isAdmin() {
        return holder.tryGet().map(this::hasAdminFlags).orElse(false);
    }

    /** True, если у текущего пользователя есть {@code ROOT_*}-флаги. */
    public boolean isRoot() {
        return holder.tryGet().map(ctx -> {
            int g = AccessFlags.expand(resolver.userMetricFor(ctx).globalFlags());
            return (g & (AccessFlags.ROOT_READ | AccessFlags.ROOT_WRITE)) != 0;
        }).orElse(false);
    }

    /** Бросает {@link StructuredAccessDeniedException}, если текущий пользователь не admin. */
    public void requireAdmin() {
        if (!isAdmin()) {
            throw new StructuredAccessDeniedException(
                    "This action is restricted to administrators",
                    PermissionRequirement.global(AccessFlags.ADMIN_READ));
        }
    }

    /**
     * Бросает {@link StructuredAccessDeniedException}, если текущий пользователь
     * не admin и не {@code targetUserId}.
     */
    public void requireAdminOrSelf(UUID targetUserId) {
        if (isAdmin()) return;
        UUID self = currentUserIdOrNull();
        if (self == null || !self.equals(targetUserId)) {
            throw new StructuredAccessDeniedException(
                    "This action is allowed only for your own profile, or for an administrator",
                    PermissionRequirement.global(AccessFlags.ADMIN_READ));
        }
    }

    /** UUID текущего пользователя из bound AccessContext'а; null для системного/анонимного. */
    public UUID currentUserIdOrNull() {
        return holder.tryGet()
                .filter(ctx -> !ctx.isSystem())
                .map(ctx -> ctx.principalRef().targetIdRaw())
                .map(raw -> {
                    try { return UUID.fromString(raw); }
                    catch (IllegalArgumentException e) { return null; }
                })
                .orElse(null);
    }

    private boolean hasAdminFlags(AccessContext ctx) {
        var m = resolver.userMetricFor(ctx);
        int g = AccessFlags.expand(m.globalFlags());
        return (g & AccessFlags.ADMIN_READ) != 0;
    }
}
